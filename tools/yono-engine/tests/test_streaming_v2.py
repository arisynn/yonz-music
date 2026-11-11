import json
import unittest
from unittest.mock import patch
from yt_dlp import YoutubeDL
from yono_stream import quality_selector, inspect_video
from yono_core.control import Control, Stopped
from yono_core.model import EngineError
from yono_catalog import run, music_song, execute


class StreamingV2Tests(unittest.TestCase):
    def test_real_ytdlp_selector_balances_available_audio_and_never_selects_video(self):
        def audio(bitrate):
            return dict(format_id=str(bitrate), abr=bitrate, url='https://r1.googlevideo.com/audio',
                        protocol='https', acodec='opus', vcodec='none', ext='webm')
        video=dict(audio(999), vcodec='avc1')
        with YoutubeDL({'quiet': True}) as dl:
            def choose(quality, formats):
                return list(dl.build_format_selector(quality_selector(quality))(
                    dict(formats=formats, has_merged_format=False, incomplete_formats=False)))[0]['format_id']
            for quality, expected in [('economy','64'),('balanced','128'),('best','256')]:
                self.assertEqual(expected,choose(quality,[audio(64),audio(128),audio(256),video]))
            self.assertEqual('192',choose('balanced',[audio(192),audio(256)]))
            self.assertEqual('64',choose('balanced',[audio(64)]))
        with self.assertRaises(EngineError): quality_selector('invalid')

    def test_expiring_url_is_not_part_of_stable_cache_identity(self):
        data=dict(id='abcdefghijk',title='Owned fixture',duration=30,format_id='251',ext='webm',acodec='opus',vcodec='none',abr=128)
        class Extractor:
            params={}
            def __enter__(self): return self
            def __exit__(self,*args): pass
            def extract_info(self,*args,**kwargs): return data
        def resolve(query):
            data['url']='https://r1.googlevideo.com/audio?'+query
            return inspect_video('abcdefghijk',Control('test'),factory=lambda *a:Extractor())['revision']
        first=resolve('clen=1000&lmt=123&expire=10&sig=secret')
        self.assertEqual(64,len(first))
        self.assertEqual(first,resolve('clen=1000&lmt=123&expire=20&sig=other'))
        self.assertNotEqual(first,resolve('clen=1001&lmt=123'))
        self.assertNotEqual(first,resolve('clen=1000&lmt=124'))
        self.assertEqual('',resolve('clen=1000'))

    def test_public_radio_is_bounded_deduplicated_and_keeps_recording_ids(self):
        calls=[]
        class Catalog:
            def __init__(self,control): pass
            def call(self,method,**kwargs):
                calls.append((method,kwargs))
                rows=[dict(videoId=f'{n:011d}',title=f'Track {n}',artists=[{'name':'Owner'}],length='3:01') for n in range(40)]
                return {'tracks':rows+rows}
            def close(self): calls.append('closed')
        result=run({'operation':'related','video_id':'00000000000'},Control('test'),Catalog)
        self.assertEqual(20,len(result['songs']))
        self.assertEqual(20,len({r['video_id'] for r in result['songs']}))
        self.assertNotIn('00000000000',[r['video_id'] for r in result['songs']])
        self.assertEqual(181000,result['songs'][0]['duration_ms'])
        self.assertEqual(('get_watch_playlist',dict(videoId='00000000000',radio=True,limit=20)),calls[0])
        self.assertEqual('closed',calls[-1])

    def test_catalog_failure_uses_existing_search_but_cancellation_never_falls_back(self):
        class Broken:
            def __init__(self,control): pass
            def call(self,*args,**kwargs): raise RuntimeError('network')
            def close(self): pass
        with patch('yono_catalog.fallback_search',return_value={'ok':True,'songs':[]}) as fallback:
            self.assertTrue(run({'query':'fixture'},Control('test'),Broken)['ok'])
            fallback.assert_called_once()
        class Cancelled(Broken):
            def call(self,*args,**kwargs): raise Stopped()
        with patch('yono_catalog.fallback_search') as fallback:
            with self.assertRaises(Stopped): run({'query':'fixture'},Control('test'),Cancelled)
            fallback.assert_not_called()

    def test_metadata_and_error_boundary_do_not_invent_credits_or_leak_secrets(self):
        song=music_song({'videoId':'abcdefghijk','title':'Owned','artists':None,'thumbnails':[{'url':'https://evil.test/a'}]})
        self.assertEqual([],song['artists']);self.assertEqual('',song['artwork'])
        self.assertIsNone(music_song({'videoId':'abcdefghijk','title':'Live','isLive':True}))
        with patch('yono_catalog.run',side_effect=RuntimeError('https://secret.test/?password=private')):
            result=execute(json.dumps({'query':'fixture'}),json.dumps({'job_id':'test'}),None)
        self.assertFalse(json.loads(result)['ok']);self.assertNotIn('private',result)
        with patch('yono_catalog.run',side_effect=Stopped()):
            result=json.loads(execute('{}','{"job_id":"test"}',None))
        self.assertEqual('CANCELLED',result['error']['code'])


if __name__=='__main__': unittest.main()
