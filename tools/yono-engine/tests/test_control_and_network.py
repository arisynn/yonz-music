import io
import json
from pathlib import Path
import socket
import tempfile
import threading
import time
import unittest
from unittest.mock import patch
from yono_core.control import Control, Stopped
from yono_core.model import EngineError
from yono_core.network import Reader, public_https, youtube_dl
from yono_core.media import command


class ControlTests(unittest.TestCase):
    def test_cancel(self):
        token = Control('t'); token.cancel()
        with self.assertRaises(Stopped) as stopped: token.check()
        self.assertEqual(stopped.exception.code, 'CANCELLED')
    def test_cooperative_deadline(self):
        now = [0.0]; token = Control('t', clock=lambda: now[0], timeout=5)
        now[0] = 6
        with self.assertRaises(Stopped) as stopped: token.check()
        self.assertEqual(stopped.exception.code, 'TIMEOUT')
    def test_cancel_is_not_swallowed_by_except_exception(self):
        self.assertFalse(issubclass(Stopped, Exception))
    def test_java_cancel_flag_before_python_job_registration(self):
        class Bridge:
            def isCancelled(self): return True
        with self.assertRaises(Stopped): Control('t', Bridge()).check()
    def test_transfer_progress_throttled(self):
        events=[]; token=Control('t', lambda raw: events.append(json.loads(raw)))
        for _ in range(1000): token.hook({'status':'downloading', 'downloaded_bytes':100})
        token.hook({'status':'finished', 'downloaded_bytes':100})
        self.assertLessEqual(len(events), 3)
    def test_reader_preserves_complete_read(self):
        data = b'a' * 200000
        self.assertEqual(Reader(io.BytesIO(data), len(data), Control('t')).read(), data)
    def test_reader_none_amount(self):
        self.assertEqual(Reader(io.BytesIO(b'abc'),10,Control('t')).read(None),b'abc')
    def test_reader_enforces_size(self):
        with self.assertRaises(EngineError): Reader(io.BytesIO(b'a' * 20), 10, Control('t')).read()
    def test_reader_stops_on_cancel(self):
        token=Control('t'); reader=Reader(io.BytesIO(b'a'), 10, token); token.cancel()
        with self.assertRaises(Stopped): reader.read()
    def test_subprocess_cancel_terminates_child(self):
        import sys
        token=Control('t'); timer=threading.Timer(.15, token.cancel); timer.start()
        started=time.monotonic()
        try:
            with self.assertRaises(Stopped): command([sys.executable, '-c', 'import time; time.sleep(20)'], token)
        finally: timer.join()
        self.assertLess(time.monotonic()-started, 3)


class NetworkTests(unittest.TestCase):
    def test_requests_redirect_is_checked_before_sending(self):
        import requests
        from yono_core.network import session
        calls=[]
        def send(adapter,request,**kwargs):
            calls.append(request.url)
            response=requests.Response(); response.status_code=302
            response.headers['Location']='https://unapproved.example.test/path'
            response.url=request.url; response.request=request
            response._content=b''; response._content_consumed=True
            return response
        with patch('yono_core.network.public_https',side_effect=lambda value,*args:value):
            with patch('requests.adapters.HTTPAdapter.send',send):
                with session(Control('t'),{'approved.example.test'}) as client:
                    with self.assertRaises(EngineError): client.get('https://approved.example.test/path')
        self.assertEqual(calls,['https://approved.example.test/path'])
    def test_requests_body_bound_applies_to_redirect_response(self):
        import requests
        from yono_core.network import session, MAX_DOCUMENT
        def send(adapter,request,**kwargs):
            response=requests.Response(); response.status_code=302
            response.headers['Location']='https://approved.example.test/next'
            response.url=request.url; response.request=request
            response.raw=io.BytesIO(b'x'*(MAX_DOCUMENT+1))
            return response
        with patch('yono_core.network.public_https',side_effect=lambda value,*args:value):
            with patch('requests.adapters.HTTPAdapter.send',send):
                with session(Control('t'),{'approved.example.test'}) as client:
                    with self.assertRaises(EngineError): client.get('https://approved.example.test/path')
    def test_non_https_rejected(self):
        with self.assertRaises(EngineError): public_https('http://example.test/song')
    def test_embedded_credentials_rejected(self):
        with self.assertRaises(EngineError): public_https('https://user:secret@example.test/song')
    def test_query_token_rejected(self):
        with self.assertRaises(EngineError): public_https('https://example.test/?access_token=secret')
    def test_nonstandard_port_rejected(self):
        with self.assertRaises(EngineError): public_https('https://example.test:8080/song')
    def test_private_network_rejected(self):
        answers=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('127.0.0.1',443))]
        with patch('yono_core.network.socket.getaddrinfo',return_value=answers):
            with self.assertRaises(EngineError): public_https('https://example.test/song')
    def test_ipv6_local_rejected(self):
        answers=[(socket.AF_INET6,socket.SOCK_STREAM,6,'',('::1',443,0,0))]
        with patch('yono_core.network.socket.getaddrinfo',return_value=answers):
            with self.assertRaises(EngineError): public_https('https://example.test/song')
    def test_public_resolution_accepted(self):
        answers=[(socket.AF_INET,socket.SOCK_STREAM,6,'',('8.8.8.8',443))]
        with patch('yono_core.network.socket.getaddrinfo',return_value=answers):
            self.assertEqual(public_https('https://example.test/song'),'https://example.test/song')
    def test_metadata_options_flat_no_50_limit_no_remote_code(self):
        from yt_dlp.networking import _urllib
        original=_urllib.RedirectHandler.redirect_request
        with youtube_dl(Control('t'), flat=True) as downloader:
            self.assertEqual(downloader.params['extract_flat'], 'in_playlist')
            self.assertTrue(downloader.params['lazy_playlist'])
            self.assertTrue(downloader.params['skip_download'])
            self.assertNotIn('playlistend', downloader.params)
            self.assertFalse(downloader.params['remote_components'])
            self.assertFalse(downloader.params['plugin_dirs'])
            handlers=downloader._request_director.handlers
            self.assertEqual(len(handlers),1)
            self.assertIs(_urllib.RedirectHandler.redirect_request,original)
            rh=next(iter(handlers.values()))
            opener=rh._create_instance(proxies={},cookiejar=downloader.cookiejar)
            redirect=next(h for h in opener.handlers if hasattr(h,'redirect_request'))
            import urllib.request
            request=urllib.request.Request('https://example.test/source',headers={'Authorization':'SECRET'})
            with patch('yono_core.network.public_https',side_effect=lambda value,*args:value):
                next_request=redirect.redirect_request(request,None,302,'',{},'https://example.test/target')
            self.assertIsNone(next_request.get_header('Authorization'))
    def test_downloader_progress_hook_cancel(self):
        token=Control('t')
        with youtube_dl(token) as downloader:
            token.cancel()
            with self.assertRaises(Stopped): downloader.params['progress_hooks'][0]({'downloaded_bytes':1})

if __name__ == '__main__': unittest.main()
