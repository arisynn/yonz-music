import json
import unittest
from yono_search import normalize, run, execute, video_id
from yono_core.control import Control, Stopped

VIDEO = 'aqz-KE-bpKQ'
def row(i=VIDEO):
    return {'id':i,'title':'Original audio','channel':'Uploader','duration':221,'thumbnails':[{'url':'https://i.ytimg.com/vi/'+i+'/mqdefault.jpg'}]}
class SearchTests(unittest.TestCase):
    def test_metadata_does_not_guess_artist_album_or_hd(self):
        r=normalize(row());self.assertEqual([],r['artists']);self.assertEqual('',r['album']);self.assertEqual('Uploader',r['channel']);self.assertEqual(221000,r['duration_ms']);self.assertIn('mqdefault',r['artwork'])
    def test_real_credits_and_invalid_duration(self):
        r=row();r.update(artists=['One','Two'],duration=float('nan'));self.assertEqual(['One','Two'],normalize(r)['artists']);self.assertEqual(0,normalize(r)['duration_ms'])
    def test_invalid_ids_and_live_items(self):
        self.assertIsNone(normalize(row('../bad')));r=row();r['is_live']=True;self.assertIsNone(normalize(r))
    def test_artwork_does_not_contact_arbitrary_hosts(self):
        r=row();r['thumbnails']=[{'url':'https://i.ytimg.com.evil.test/a'}];self.assertEqual('',normalize(r)['artwork'])
    def test_links_are_strict_and_no_credentials(self):
        self.assertEqual(VIDEO,video_id('https://youtu.be/'+VIDEO));self.assertEqual(VIDEO,video_id('https://www.youtube.com/shorts/'+VIDEO))
        for url in ['https://u:p@youtube.com/watch?v='+VIDEO,'https://youtube.com.evil.test/watch?v='+VIDEO,'http://youtube.com/watch?v='+VIDEO]:self.assertIsNone(video_id(url))
    def test_search_is_flat_no_download_and_pages_are_bounded(self):
        calls=[]
        class Fake:
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def extract_info(self,target,download):calls.append((target,download));return {'entries':[row('abcdefghij'+str(i%10)) for i in range(25)]}
        def factory(c,**opts):self.assertEqual({'flat':True},opts);return Fake()
        r=run({'query':'original audio','page':0},Control('fixture'),factory);self.assertFalse(calls[0][1]);self.assertEqual('ytsearch13:original audio',calls[0][0]);self.assertEqual('1',r['next']);self.assertLessEqual(len(r['songs']),12)
        with self.assertRaises(ValueError):run({'query':'xxy','page':999},Control('fixture'),factory)
    def test_exact_link_never_substitutes_other_recording(self):
        class Fake:
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def extract_info(self,*a,**kw):return {'entries':[row('abcdefghijk'),row()]}
        r=run({'query':'https://youtu.be/'+VIDEO,'page':0},Control('fixture'),lambda *a,**kw:Fake());self.assertEqual(['engine:'+VIDEO],[r['id']for r in r['songs']])
    def test_cancelled_request_never_returns_results(self):
        c=Control('fixture');c.cancel()
        class Fake:
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def extract_info(self,*a,**kw):return {'entries':[row()]}
        with self.assertRaises(Stopped):run({'query':'original','page':0},c,lambda *a,**kw:Fake())
    def test_invalid_link_has_no_network(self):
        r=run({'query':'https://127.0.0.1/music','page':0},Control('fixture'),lambda *a,**kw:self.fail('must not call'));self.assertFalse(r['ok'])
    def test_json_invalid_input_is_structured(self):
        self.assertEqual('INVALID_REQUEST',json.loads(execute('{}','{}',None))['error']['code'])
