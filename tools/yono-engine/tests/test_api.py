import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from yono_core.model import candidate_song
from yono_core.storage_host import DirectoryStore
import yono_engine_api as api
from support import candidate


class ApiTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(); root=Path(self.temp.name)
        self.config={'job_id':'api-test','state_dir':str(root/'state')}
        self.store=DirectoryStore(root/'Music',root/'receipts')
    def tearDown(self): self.temp.cleanup()
    def call(self,action,payload=None,callback=None):
        return json.loads(api.execute(action,json.dumps(payload or {}),json.dumps(self.config),callback,self.store))
    def test_malformed_json_returns_envelope(self):
        value=json.loads(api.execute('prepare','{','{}',None,self.store))
        self.assertEqual(value['error']['code'],'INVALID_REQUEST')
    def test_snapshot_and_offline_prepare(self):
        song=candidate_song(candidate())
        path=self.store.root/'renamed.mp3'; path.write_bytes(b'test bytes')
        from yono_core.media import file_hash
        row={**song.to_dict(),'uri':path.as_uri(),'sha256':file_hash(path)}
        self.assertTrue(self.call('snapshot',{'scope':'one','rows':[row]})['ok'])
        with patch('yono_core.catalogue.Catalogue.resolve',side_effect=AssertionError('must remain offline')):
            result=self.call('prepare',{'kind':'text','content':'Artis - Lagu 0'})
        self.assertTrue(result['ok'])
        self.assertEqual(result['result']['counts'],{'exists':1})
        self.assertEqual(self.call('get_last')['result']['id'],result['result']['id'])
    def test_cancel_before_start_returns_cancelled(self):
        class Callback:
            def isCancelled(self): return True
        self.assertEqual(self.call('prepare',{'kind':'text','content':'Artis - Lagu'},Callback())['error']['code'],'CANCELLED')
        self.assertEqual(api._JOBS,{})
        self.assertFalse(api._SERIAL.locked())
    def test_second_operation_reports_busy(self):
        api._SERIAL.acquire()
        try: self.assertEqual(self.call('get_last')['error']['code'],'BUSY')
        finally: api._SERIAL.release()
    def test_unknown_action(self):
        self.assertEqual(self.call('made_up')['error']['code'],'UNKNOWN_ACTION')
    def test_config_secrets_not_written_to_plan(self):
        self.config['spotify_access_token']='TEST_SECRET_DO_NOT_PERSIST'
        path=self.store.root/'existing.mp3'; path.write_bytes(b'test')
        song=candidate_song(candidate()).to_dict()
        self.call('snapshot',{'scope':'one','rows':[{**song,'uri':path.as_uri()}]})
        result=self.call('prepare',{'kind':'text','content':'Artis - Lagu 0'})
        self.assertTrue(result['ok'])
        root=Path(self.config['state_dir'])
        self.assertFalse(any('TEST_SECRET_DO_NOT_PERSIST' in f.read_text() for f in root.rglob('*.json')))

if __name__ == '__main__': unittest.main()
