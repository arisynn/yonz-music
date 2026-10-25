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
        result=self.media.finish(self.source,self.song,self.work)
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
        result=self.media.finish(self.source,self.song,self.work)
        before=Path(result['path'])
        second=self.work/'repair'; second.mkdir()
        self.media.transfer=lambda *args: self.fail('audio redownload must not happen')
        repaired=self.media.repair(before,self.song,second)
        def audio_hash(path):
            return subprocess.run(['ffmpeg','-v','error','-i',str(path),'-map','0:a:0','-c:a','copy','-f','hash','-hash','sha256','-'],check=True,capture_output=True).stdout
        self.assertEqual(audio_hash(before),audio_hash(repaired['path']))
        self.assertGreater(probe(repaired['path'],self.token,self.media.ffprobe)['duration'],3.9)
    def test_doctor_real_runtime(self):
        from yono_engine_api import capabilities
        report=capabilities({'ffmpeg':shutil.which('ffmpeg'),'runtime':{'node':{'path':shutil.which('node')}}},self.token)
        self.assertTrue(report['mp3'])
        self.assertTrue(report['javascript'])
        self.assertFalse(report['live_youtube_verified'])

if __name__ == '__main__': unittest.main()
