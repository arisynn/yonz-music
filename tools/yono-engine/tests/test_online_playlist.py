import unittest
from yono_online_playlist import canonical, read

class Control:
    def check(self):pass
class Fake:
    def __init__(self,data):self.data=data;self.params={};self.calls=[]
    def __enter__(self):return self
    def __exit__(self,*args):pass
    def extract_info(self,url,download=False):self.calls.append((url,download));return self.data

def row(ident='aqz-KE-bpKQ',**kw):return {'id':ident,'title':'Owned song','channel':'Uploader is not verified artist',**kw}
class OnlinePlaylistTests(unittest.TestCase):
    def test_canonical(self):
        self.assertEqual(canonical('https://music.youtube.com/playlist?list=PL1234567890&si=x'),'https://music.youtube.com/playlist?list=PL1234567890')
        self.assertEqual(canonical('https://www.youtube.com/watch?v=aqz-KE-bpKQ&list=PL1234567890'),'https://music.youtube.com/playlist?list=PL1234567890')
    def test_unsafe_dynamic_duplicate_query(self):
        for url in ['http://music.youtube.com/playlist?list=PL1234567890','https://music.youtube.com.evil/playlist?list=PL1234567890','https://user@music.youtube.com/playlist?list=PL1234567890','https://music.youtube.com/playlist?list=RD1234567890','https://music.youtube.com/playlist?list=PL1234567890&list=PLabcdefghij']:
            with self.assertRaises(ValueError):canonical(url)
    def test_exact_order_counts_unavailable_and_duplicate(self):
        f=Fake({'_type':'playlist','title':'Owner playlist','playlist_count':5,'entries':[row(),row('QAbcd000001',artists=['A','B']),None,row(),row('QAxyz000001',availability='private')]})
        r=read('https://music.youtube.com/playlist?list=PL1234567890',Control(),lambda *a,**k:f)
        self.assertEqual([s['video_id']for s in r['songs']],['aqz-KE-bpKQ','QAbcd000001']);self.assertEqual(r['unavailable'],2);self.assertEqual(r['duplicates'],1)
        self.assertEqual(r['songs'][0]['artists'],[]);self.assertEqual(r['songs'][1]['artists'],['A','B']);self.assertFalse(f.calls[0][1]);self.assertEqual(f.params['playlistend'],501)
    def test_incomplete_not_silently_saved(self):
        f=Fake({'_type':'playlist','playlist_count':2,'entries':[row()]})
        with self.assertRaises(ValueError):read('https://music.youtube.com/playlist?list=PL1234567890',Control(),lambda *a,**k:f)
    def test_oversize_not_truncated(self):
        f=Fake({'_type':'playlist','entries':[row()]*501})
        with self.assertRaises(ValueError):read('https://music.youtube.com/playlist?list=PL1234567890',Control(),lambda *a,**k:f)
    def test_cancellation_propagates(self):
        class Stopped:
            def check(self):raise RuntimeError('cancelled fixture')
        f=Fake({'_type':'playlist','entries':[row()]})
        with self.assertRaises(RuntimeError):read('https://music.youtube.com/playlist?list=PL1234567890',Stopped(),lambda *a,**k:f)
    def test_empty_and_wrong_shape_rejected(self):
        for data in [{'_type':'playlist','entries':[]},{'_type':'playlist','entries':[row(title='[Deleted video]')]},{'_type':'video','entries':[row()]},None]:
            with self.assertRaises(ValueError):read('https://music.youtube.com/playlist?list=PL1234567890',Control(),lambda *a,**k:Fake(data))
