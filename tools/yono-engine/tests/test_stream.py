import unittest
from yono_stream import inspect_video
from yono_core.control import Control
from yono_core.model import EngineError

class StreamTests(unittest.TestCase):
    def extract(self,change=None):
        calls=[]
        data={'id':'aqz-KE-bpKQ','title':'Original fixture','duration':20,'acodec':'opus','vcodec':'none','url':'https://r1.googlevideo.com/videoplayback?expire=2000000000','channel':'Uploader','http_headers':{'User-Agent':'Fixture','Cookie':'must-not-leak'}}
        if change:data.update(change)
        class Fake:
            params={}
            def __enter__(self):return self
            def __exit__(self,*args):pass
            def extract_info(self,url,download):calls.append((url,download));return data
        result=inspect_video('aqz-KE-bpKQ',Control('fixture'),factory=lambda *a,**k:Fake())
        return result,calls
    def test_resolution_is_read_only_and_does_not_guess_artist(self):
        r,c=self.extract();self.assertEqual([],r['song']['artists']);self.assertEqual('',r['song']['album']);self.assertFalse(c[0][1]);self.assertNotIn('Cookie',r['headers'])
    def test_never_substitutes_other_video(self):
        with self.assertRaises(EngineError):self.extract({'id':'abcdefghijk'})
    def test_rejects_drm_private_live_and_merged_formats(self):
        for change in [{'has_drm':True},{'availability':'private'},{'is_live':True},{'requested_formats':[{},{}]},{'vcodec':'avc1'}]:
            with self.subTest(change=change),self.assertRaises(EngineError):self.extract(change)
    def test_rejects_arbitrary_stream_hosts_and_credentials(self):
        for url in ['http://r1.googlevideo.com/a','https://googlevideo.com.evil.test/a','https://u:p@r1.googlevideo.com/a','https://127.0.0.1/a','https://r1.googlevideo.com:8443/a']:
            with self.subTest(url=url),self.assertRaises(EngineError):self.extract({'url':url})
    def test_invalid_source_identity_never_enters_extractor(self):
        with self.assertRaises(EngineError):inspect_video('../bad',Control('fixture'),factory=lambda *a,**k:self.fail('network must not start'))

    def test_selected_source_does_not_reuse_same_title_from_different_video(self):
        import tempfile
        from pathlib import Path
        from yono_core.index import LibraryIndex
        from yono_core.model import Song
        with tempfile.TemporaryDirectory() as t:
            index=LibraryIndex(Path(t)/'index.db',lambda uri:True)
            old=Song('Same title',['Artist'],20,youtube_id='abcdefghijk')
            index.add('selected',old,'content://owned/original','a'*64)
            wanted=Song('Same title',['Artist'],20,youtube_id='aqz-KE-bpKQ')
            self.assertIsNotNone(index.find(wanted)[0])
            self.assertIsNone(index.find(wanted,exact_source=True)[0])
            self.assertIsNotNone(index.find(old,exact_source=True)[0]);index.close()
