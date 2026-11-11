import sys
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
sys.path.insert(0, str(Path(__file__).resolve().parents[3] / 'app/src/main/python'))
from yono_discovery import MetadataProvider, ChartsProvider, AudioResolver, track
from yono_core.media import Media
from yono_core.model import Song, EngineError
from yono_core.control import Control

class DiscoveryPreview2Test(unittest.TestCase):
    def test_live_metadata_cannot_replace_studio_recording_and_ambiguous_matches_stay_original(self):
        source={'title':'Song','artists':['Artist'],'duration_ms':180000,'video_id':'abcdefghijk','artwork':'original'}
        provider=MetadataProvider.__new__(MetadataProvider)
        row={'id':'a'*8+'-'+'a'*4+'-'+'a'*4+'-'+'a'*4+'-'+'a'*12,'title':'Song','length':180000,'artist-credit':[{'artist':{'name':'Artist'}}]}
        provider.get=lambda *a,**k:{'recordings':[{**row,'disambiguation':'live'}]}
        self.assertEqual(source,provider.enrich(source))
        provider.get=lambda *a,**k:{'recordings':[row,row]}
        self.assertEqual(source,provider.enrich(source))
        provider.get=lambda *a,**k:{'recordings':[row]}
        self.assertIn('metadata_id',provider.enrich(source))

    def test_chart_uses_real_playlist_and_does_not_invent_artist_rank(self):
        class Catalogue:
            control=Control('chart',None,30)
            def call(self, method, **kwargs):
                self.method=method
                return {'videos':[{'playlistId':'PLactualchart12345'}], 'artists':[{'title':'Artist','browseId':'UCactual','rank':None}]}
        class Downloader:
            params={}
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def extract_info(self,url,download=False):
                self.url=url
                return {'entries':[{'id':'abcdefghijk','title':'Song','duration':180,'artist':'Artist'}]}
        dl=Downloader();catalogue=Catalogue()
        with patch('yono_core.network.youtube_dl',return_value=dl):
            result=ChartsProvider(catalogue).charts('ID')
        self.assertEqual('get_charts',catalogue.method)
        self.assertEqual('https://music.youtube.com/playlist?list=PLactualchart12345',dl.url)
        self.assertEqual(1,len(result['songs']))
        self.assertNotIn('rank',result['items'][0])

    def test_incomplete_playlist_is_reported_instead_of_silently_truncated(self):
        from yono_discovery import execute
        class Partial:
            def call(self,*args,**kwargs):return {'trackCount':600,'tracks':[]}
            def close(self):pass
        with patch('yono_discovery.Catalogue',return_value=Partial()):
            result=json.loads(execute(json.dumps({'operation':'detail','id':'yt:playlist:PLfixture123456'}),json.dumps({'job_id':'partial','timeout_seconds':10}),None))
        self.assertFalse(result['ok']);self.assertEqual('INCOMPLETE_PLAYLIST',result['error']['code'])

    def test_real_opus_remux_tags_and_aac_rejection(self):
        import subprocess
        import mutagen
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp)
            source=folder/'source.webm'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=frequency=440:duration=3','-c:a','libopus',str(source)],check=True)
            media=Media(Control('opus-test',None,40),ffmpeg='/usr/bin/ffmpeg',ffprobe='/usr/bin/ffprobe')
            media.artwork=lambda song:(Path(__file__).resolve().parents[3] / 'app/src/androidTest/assets/original-audio/cover.png').read_bytes()
            result=media.finish(source,Song('Owned test',['Test'],duration=3),folder)
            output=Path(result['path'])
            self.assertEqual(b'OggS',output.read_bytes()[:4]);self.assertEqual('opus',result['extension'])
            tagged=mutagen.File(output)
            self.assertEqual('OggOpus',type(tagged).__name__);self.assertEqual(['Owned test'],tagged['title']);self.assertIn('metadata_block_picture',tagged)
            self.assertTrue(result['artwork_embedded'])
            self.assertAlmostEqual(3,tagged.info.length,delta=.06)
            # The exact compressed packets survive remux and tagging.
            def packets(path):
                raw=subprocess.check_output(['ffprobe','-v','error','-select_streams','a:0','-show_packets','-show_data_hash','sha256','-of','json',str(path)])
                return [p['data_hash'] for p in json.loads(raw)['packets']]
            self.assertEqual(packets(source),packets(output))
            aac=folder/'source.m4a'
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=duration=3','-c:a','aac',str(aac)],check=True)
            with self.assertRaises(EngineError) as caught:media.finish(aac,Song('AAC',['Test'],duration=3),folder)
            self.assertEqual('OPUS_UNAVAILABLE',caught.exception.code)

if __name__=='__main__':unittest.main()
