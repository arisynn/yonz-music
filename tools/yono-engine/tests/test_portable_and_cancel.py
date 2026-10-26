import json,tempfile,unittest
from pathlib import Path
from unittest.mock import Mock
from support import candidate,engine_at,PNG
from yono_core.control import Control
from yono_core.media import file_hash

class PortableAndCancelTests(unittest.TestCase):
 def setUp(self):
  self.temp=tempfile.TemporaryDirectory();self.token=Control('portable')
  self.engine,self.index,self.yt,self.storage=engine_at(Path(self.temp.name),[candidate(),candidate(1)],self.token)
 def tearDown(self):self.index.close();self.temp.cleanup()
 def prepare(self):return self.engine.prepare({'kind':'url','url':'https://music.youtube.com/playlist?list=PL123456789012345'})
 def test_manifest_bound_to_exact_published_bytes_no_android_uri_or_signed_query(self):
  p=self.prepare();r=self.engine.acquire(p['id'],[p['entries'][0]['id']]);row=r['entries'][0]
  m=json.loads(self.storage.path(row['metadata_uri']).read_text())
  self.assertEqual(m['type'],'yono.music.metadata');self.assertEqual(m['audio']['sha256'],file_hash(self.storage.path(row['uri'])))
  self.assertEqual(m['metadata']['artists'],['Artis']);self.assertNotIn('content://',json.dumps(m))
 def test_single_item_cancel_keeps_following_item_running(self):
  p=self.prepare();cancelled={p['entries'][0]['id']}
  class Callback:
   def isCancelled(self):return False
   def isEntryCancelled(self,identity):return identity in cancelled
   def onEvent(self,raw):pass
  self.token.callback=Callback()
  r=self.engine.acquire(p['id'],[x['id'] for x in p['entries']])
  self.assertEqual(r['entries'][0]['status'],'cancelled');self.assertEqual(r['entries'][1]['status'],'downloaded')
  self.assertEqual(self.engine.media.transfers,1)
 def test_cancel_during_transfer_is_not_swallowed_by_broad_handler(self):
  p=self.prepare();cancelled=set();original=self.engine.media.transfer
  class Callback:
   def isCancelled(self):return False
   def isEntryCancelled(self,identity):return identity in cancelled
   def onEvent(self,raw):pass
  self.token.callback=Callback()
  def transfer(song,work):
   if song.youtube_id==p['entries'][0]['song']['youtube_id']:
    cancelled.add(p['entries'][0]['id']);self.token.check()
   return original(song,work)
  self.engine.media.transfer=transfer
  r=self.engine.acquire(p['id'],[x['id'] for x in p['entries']])
  self.assertEqual(r['entries'][0]['status'],'cancelled');self.assertEqual(r['entries'][1]['status'],'downloaded')
 def test_manifest_failure_recovers_receipt_without_another_transfer(self):
  p=self.prepare();original=self.storage.commit;calls=[0]
  def commit(path,folder,name,mime,key,digest):
   if key.endswith('-metadata') and calls[0]==0:
    calls[0]+=1;raise OSError('fixture storage temporarily disconnected')
   return original(path,folder,name,mime,key,digest)
  self.storage.commit=commit
  first=self.engine.acquire(p['id'],[p['entries'][0]['id']]);self.assertEqual(first['entries'][0]['status'],'failed')
  again=self.engine.acquire(p['id'],[p['entries'][0]['id']]);self.assertEqual(again['entries'][0]['status'],'downloaded');self.assertEqual(self.engine.media.transfers,1)
 def test_cover_repair_refreshes_portable_reference_without_audio_transfer(self):
  p=self.prepare();r=self.engine.acquire(p['id'],[p['entries'][0]['id']]);row=r['entries'][0];old_hash=file_hash(self.storage.path(row['uri']))
  self.engine.media.artwork=lambda song:PNG;self.engine.media.transfer=Mock(side_effect=AssertionError('must not download'))
  r=self.engine.repair_artwork(p['id'],row['id']);m=json.loads(self.storage.path(r['entries'][0]['metadata_uri']).read_text())
  self.assertEqual(m['artwork']['sha256'],file_hash(self.storage.path(r['entries'][0]['artwork_uri'])))
  self.assertEqual(old_hash,file_hash(self.storage.path(row['uri'])))
