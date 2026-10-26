"""Generate our own sine wave. No commercial recording/network fixture is downloaded."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from yono_core.control import Control
from yono_core.media import Media, file_hash, probe, tag
from yono_core.model import Song, EngineError
from support import PNG


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'Host FFmpeg and FFprobe required')
class RealAudioTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(); cls.root=Path(cls.temp.name)
        cls.source=cls.root/'own-sine.wav'
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-f','lavfi','-i','sine=frequency=440:duration=4',str(cls.source)],check=True)
    @classmethod
    def tearDownClass(cls): cls.temp.cleanup()
    def setUp(self):
        self.work=Path(tempfile.mkdtemp(dir=self.root))
        self.token=Control('audio')
        self.song=Song('Synthetic sine', ['Yono test fixture'],4,album='Tests',youtube_id='00000000000',sid='0123456789012345678901')
        self.media=Media(self.token,ffmpeg=shutil.which('ffmpeg'),ffprobe=shutil.which('ffprobe'))
        self.media.artwork=lambda song: PNG
    def tearDown(self): shutil.rmtree(self.work)
    def test_real_mp3_duration_tags_and_cover(self):
        from mutagen.mp3 import MP3
        source=self.work/'own-original.mp3'
        subprocess.run(['ffmpeg','-v','error','-i',str(self.source),'-c:a','libmp3lame',str(source)],check=True)
        result=self.media.finish(source,self.song,self.work)
        self.assertEqual(Path(result['path']),source)
        audio=MP3(result['path'])
        self.assertTrue(result['tags_embedded'] and result['artwork_embedded'])
        self.assertGreater(audio.info.length,3.9)
        self.assertLess(audio.info.length,4.2)
        self.assertEqual(str(audio.tags['TIT2']),'Synthetic sine')
        self.assertEqual(audio.tags.getall('APIC')[0].data,PNG)
        subprocess.run(['ffmpeg','-v','error','-xerror','-i',result['path'],'-map','0:a:0','-f','null','-'],check=True,capture_output=True)
    def test_m4a_tags_and_embedded_artwork(self):
        from mutagen.mp4 import MP4
        path=self.work/'own-sine.m4a'
        subprocess.run(['ffmpeg','-v','error','-i',str(self.source),'-c:a','aac',str(path)],check=True)
        result=self.media.finish(path,self.song,self.work,output_format='original')
        audio=MP4(result['path'])
        self.assertEqual(audio['\xa9nam'],['Synthetic sine'])
        self.assertEqual(bytes(audio['covr'][0]),PNG)
        self.assertGreater(result['duration'],3.9)
    def test_wrong_duration_rejected_before_publication(self):
        with self.assertRaises(EngineError) as error: self.media.finish(self.source,Song('Wrong',['Artis'],240),self.work)
        self.assertEqual(error.exception.code,'DURATION_MISMATCH')
    def test_artwork_repair_preserves_audio_packets_no_audio_download(self):
        source=self.work/'own-original.mp3'
        subprocess.run(['ffmpeg','-v','error','-i',str(self.source),'-c:a','libmp3lame',str(source)],check=True)
        result=self.media.finish(source,self.song,self.work)
        before=Path(result['path'])
        second=self.work/'repair'; second.mkdir()
        self.media.transfer=lambda *args: self.fail('audio redownload must not happen')
        repaired=self.media.repair(before,self.song,second)
        def audio_hash(path):
            return subprocess.run(['ffmpeg','-v','error','-i',str(path),'-map','0:a:0','-c:a','copy','-f','hash','-hash','sha256','-'],check=True,capture_output=True).stdout
        self.assertEqual(audio_hash(before),audio_hash(repaired['path']))
        self.assertGreater(probe(repaired['path'],self.token,self.media.ffprobe)['duration'],3.9)
    def test_webm_and_opus_keep_original_packets_and_save_cover(self):
        from mutagen.oggopus import OggOpus
        import base64
        from mutagen.flac import Picture
        def packets(path):
            return subprocess.run(['ffmpeg','-v','error','-i',str(path),'-map','0:a:0','-c:a','copy','-f','hash','-hash','sha256','-'],check=True,capture_output=True).stdout
        for ext in ('webm','opus'):
            with self.subTest(ext=ext):
                path=self.work/('original.'+ext)
                subprocess.run(['ffmpeg','-v','error','-i',str(self.source),'-c:a','libopus',str(path)],check=True)
                before=packets(path)
                result=self.media.finish(path,self.song,self.work)
                self.assertEqual(result['path'],str(path))
                self.assertEqual(result['extension'],ext)
                self.assertEqual(before,packets(path))
                self.assertTrue(result['artwork_downloaded'])
                self.assertEqual(Path(result['artwork_path']).read_bytes(),PNG)
                self.assertEqual(result['artwork_embedded'],ext=='opus')
                if ext=='opus':
                    cover=Picture(base64.b64decode(OggOpus(path)['metadata_block_picture'][0]))
                    self.assertEqual(cover.data,PNG)
    def test_no_cover_is_reported_without_faking_success(self):
        self.media.artwork=lambda song: None
        result=self.media.finish(self.source,self.song,self.work)
        self.assertFalse(result['artwork_downloaded'])
        self.assertNotIn('artwork_path',result)
    def test_conversion_rejected_even_when_host_encoder_exists(self):
        with self.assertRaises(EngineError) as error:
            self.media.finish(self.source,self.song,self.work,output_format='mp3')
        self.assertEqual(error.exception.code,'ORIGINAL_ONLY')
    def test_doctor_real_runtime(self):
        from yono_engine_api import capabilities
        report=capabilities({'ffmpeg':shutil.which('ffmpeg'),'runtime':{'node':{'path':shutil.which('node')}}},self.token)
        self.assertFalse(report['mp3'])
        self.assertEqual(report['output_formats'], ['original'])
        self.assertTrue(report['javascript'])
        self.assertFalse(report['live_youtube_verified'])

    def _direct_tagged_fixture(self):
        from mutagen.easyid3 import EasyID3
        target = self.work / 'Artist - filename guess.mp3'
        subprocess.run(['ffmpeg','-hide_banner','-loglevel','error','-i',str(self.source),str(target)],check=True)
        tags=EasyID3(target);tags['title']=['All I Ask'];tags['artist']=['Adele','Second fixture credit'];tags['album']=['Fixture tag album'];tags.save()
        return target

    def test_direct_transfer_keeps_verified_source_fields_over_conflicting_tags(self):
        target=self._direct_tagged_fixture();before=file_hash(target)
        song=Song('Verified recording',['Verified artist','Verified second credit'],4,album='Verified album',source_url='https://example.test/owned.mp3',direct_audio=True)
        with patch('yono_engine.transfer',return_value=json.dumps({'path':str(target)})):
            result=self.media.transfer(song,self.work)
        self.assertEqual(result,target);self.assertEqual(file_hash(target),before)
        self.assertEqual(song.title,'Verified recording');self.assertEqual(song.artists,['Verified artist','Verified second credit']);self.assertEqual(song.album,'Verified album')

    def test_direct_filename_fallback_yields_to_real_tags_and_keeps_all_artists(self):
        target=self._direct_tagged_fixture()
        song=Song('Artist - filename guess',[],4,source_url='https://example.test/owned.mp3',direct_audio=True,metadata_origin='filename')
        with patch('yono_engine.transfer',return_value=json.dumps({'path':str(target)})):
            self.media.transfer(song,self.work)
        self.assertEqual(song.title,'All I Ask');self.assertEqual(song.artists,['Adele','Second fixture credit']);self.assertEqual(song.album,'Fixture tag album')
        self.assertEqual(song.metadata_origin,'verified')

    def test_artist_filename_fallback_never_splits_or_replaces_a_correct_title_tag(self):
        from mutagen.easyid3 import EasyID3
        target=self._direct_tagged_fixture();tags=EasyID3(target);del tags['artist'];tags['title']=['Intro - a correct tag title'];tags.save()
        song=Song('Filename artist - Filename title',[],4,source_url='https://example.test/owned.mp3',direct_audio=True,metadata_origin='filename')
        with patch('yono_engine.transfer',return_value=json.dumps({'path':str(target)})):
            self.media.transfer(song,self.work)
        self.assertEqual(song.title,'Intro - a correct tag title');self.assertEqual(song.artists,['Filename artist'])

if __name__ == '__main__': unittest.main()
