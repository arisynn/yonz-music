import dataclasses
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch
from yono_core.catalogue import Catalogue
from yono_core.control import Control, Stopped
from yono_core.engine import atomic_json, identity
from yono_core.index import LibraryIndex
from yono_core.model import EngineError, Song, candidate_song
from yono_core.sources import Sources
from support import candidate, engine_at, FakeMedia, FakeYT

PLAYLIST = 'https://music.youtube.com/playlist?list=PL123456789012345'


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.events = []
        self.control = Control('test', lambda raw: self.events.append(json.loads(raw)))
        self.engine, self.index, self.yt, self.storage = engine_at(self.root, [candidate()], self.control)
    def tearDown(self):
        self.index.close()
        self.temp.cleanup()
    def prepare(self):
        return self.engine.prepare({'kind': 'url', 'url': PLAYLIST})
    def test_single_song(self):
        plan = self.engine.prepare({'kind': 'url', 'url': 'https://youtu.be/00000000000'})
        self.assertEqual((plan['total'], plan['counts']), (1, {'ready': 1}))
    def test_playlist_fifty_and_110_no_truncation(self):
        for size in (3, 50, 110):
            with self.subTest(size=size):
                self.yt.rows = [candidate(i) for i in range(size)]
                self.yt.count = size
                plan = self.prepare()
                self.assertEqual((plan['total'], plan['counts']['ready']), (size, size))
                self.assertIn(('get_playlist', 'PL123456789012345', None), self.yt.calls)
    def test_progress_emits_complete_count_before_verification(self):
        self.prepare()
        stages = [e['stage'] for e in self.events]
        self.assertLess(stages.index('playlist_ready'), stages.index('verifying'))
        self.assertTrue(next(e for e in self.events if e['stage'] == 'playlist_ready')['plan_id'])
        self.assertEqual([e['sequence'] for e in self.events], list(range(1, len(self.events) + 1)))
    def test_incomplete_playlist_downloads_nothing(self):
        self.yt.count = 110
        with self.assertRaises(EngineError) as error: self.prepare()
        self.assertEqual(error.exception.code, 'INCOMPLETE_PLAYLIST')
        self.assertEqual(self.engine.media.transfers, 0)
    def test_unknown_total_rejected(self):
        self.yt.count = None
        with self.assertRaises(EngineError): self.prepare()
    def test_unavailable_rows_counted(self):
        self.yt.rows = [candidate(), {**candidate(1), 'isAvailable': False}]
        self.yt.count = 2
        plan = self.prepare()
        self.assertEqual(plan['total'], 2)
        self.assertEqual(sum(plan['counts'].values()), 2)
        self.assertEqual(plan['counts']['invalid'], 1)
    def test_playlist_ugc_not_accepted(self):
        self.yt.rows[0]['videoType'] = 'MUSIC_VIDEO_TYPE_UGC'
        self.assertEqual(self.prepare()['counts']['unmatched'], 1)
    def test_duplicate_input_only_one_transfer(self):
        self.yt.rows *= 2; self.yt.count = 2
        plan = self.prepare()
        self.assertEqual(plan['counts'], {'ready': 1, 'duplicate': 1})
        result = self.engine.acquire(plan['id'], [r['id'] for r in plan['entries']])
        self.assertEqual(self.engine.media.transfers, 1)
        self.assertEqual(result['entries'][0]['uri'], result['entries'][1]['uri'])
    def test_output_clean_name_and_playlist_folder(self):
        plan = self.prepare()
        result = self.engine.acquire(plan['id'], [plan['entries'][0]['id']])
        path = self.storage.path(result['entries'][0]['uri'])
        self.assertEqual(path.parent.name, 'Playlist uji')
        self.assertEqual(path.name, 'Artis - Lagu 0.mp3')
    def test_repeat_acquisition_skips_existing(self):
        plan = self.prepare(); ids = [plan['entries'][0]['id']]
        self.engine.acquire(plan['id'], ids)
        again = self.prepare()
        self.assertEqual(again['counts'], {'exists': 1})
        self.assertEqual(self.engine.media.transfers, 1)
    def test_unknown_selection_rejected(self):
        plan = self.prepare()
        with self.assertRaises(EngineError): self.engine.acquire(plan['id'], ['unknown'])
    def test_cancel_during_verification_saves_remaining(self):
        self.yt.rows = [candidate(i) for i in range(5)]; self.yt.count = 5
        original = self.yt.get_song
        def stop(identity):
            if identity == '00000000001': self.control.cancel()
            return original(identity)
        self.yt.get_song = stop
        with self.assertRaises(Stopped): self.prepare()
        report = json.loads((self.engine.root / 'last-report.json').read_text())
        plan = self.engine.load_plan(report['plan_id'])
        self.assertEqual(plan['state'], 'cancelled')
        self.assertGreater(plan['counts']['pending'], 0)
        self.assertEqual(sum(plan['counts'].values()), 5)
    def test_retry_includes_interrupted_tail(self):
        plan = self.prepare()
        plan['entries'][0]['status'] = 'pending'; self.engine.save(plan)
        self.assertEqual(len(self.engine.retry_request(plan['id'])['entries']), 1)
    def test_circuit_breaker_retains_unprocessed_rows(self):
        self.yt.rows = [candidate(i) for i in range(8)]; self.yt.count = 8
        self.yt.get_song = Mock(side_effect=TimeoutError())
        plan = self.prepare()
        self.assertEqual(plan['state'], 'paused_network')
        self.assertEqual(plan['counts']['failed'], 3)
        self.assertEqual(plan['counts']['pending'], 5)
    def test_missing_mp3_runtime_fails_before_download(self):
        plan = self.prepare(); self.engine.media.ffmpeg = ''
        with self.assertRaises(EngineError) as error: self.engine.acquire(plan['id'], [plan['entries'][0]['id']])
        self.assertEqual(error.exception.code, 'MP3_RUNTIME_REQUIRED')
        self.assertEqual(self.engine.media.transfers, 0)
    def test_storage_collision_never_overwrites(self):
        plan = self.prepare()
        folder = self.storage.root / plan['folder']; folder.mkdir()
        old = folder / 'Artis - Lagu 0.mp3'; old.write_bytes(b'keep old data')
        self.engine.acquire(plan['id'], [plan['entries'][0]['id']])
        self.assertEqual(old.read_bytes(), b'keep old data')
        self.assertTrue((folder / 'Artis - Lagu 0 (2).mp3').is_file())
    def test_storage_receipt_prevents_repeated_copy(self):
        src = self.root / 'source'; src.write_bytes(b'fixture')
        from yono_core.media import file_hash
        uri = self.storage.commit(str(src), 'Folder', 'Lagu.mp3', 'audio/mpeg', 'abc', file_hash(src))
        self.assertEqual(uri, self.storage.commit(str(src), 'Folder', 'Lagu.mp3', 'audio/mpeg', 'abc', file_hash(src)))
        self.assertEqual(len(list(self.storage.root.rglob('*.mp3'))), 1)
    def test_published_receipt_recovers_saving_state(self):
        plan = self.prepare(); row = plan['entries'][0]
        result = self.engine.acquire(plan['id'], [row['id']]); row = result['entries'][0]
        row['status'] = 'saving'; row['pending_sha256'] = row['audio']['sha256']; row.pop('uri')
        self.engine.save(result)
        again = self.engine.acquire(plan['id'], [row['id']])
        self.assertEqual(again['entries'][0]['status'], 'downloaded')
        self.assertEqual(self.engine.media.transfers, 1)
    def test_transfer_failure_keeps_report(self):
        plan = self.prepare(); self.engine.media.transfer = Mock(side_effect=RuntimeError('network failure'))
        result = self.engine.acquire(plan['id'], [plan['entries'][0]['id']])
        self.assertEqual(result['state'], 'complete_with_errors')
        self.assertEqual(result['counts'], {'failed': 1})
        self.assertEqual(list((self.engine.root / 'work').iterdir()), [])
    def test_m3u_order_preserved(self):
        plan = self.prepare(); self.engine.acquire(plan['id'], [plan['entries'][0]['id']])
        exported = self.engine.export_m3u(plan['id'])
        self.assertIn('#EXTINF:240,Artis — Lagu 0', exported)
        self.assertIn('file://', exported)
    def test_plan_path_traversal_rejected(self):
        with self.assertRaises(EngineError): self.engine.load_plan('../last-report')
    def test_playlist_identity_ignores_tracking(self):
        self.assertEqual(identity(PLAYLIST + '&si=foo', []), identity(PLAYLIST + '&si=bar', []))
    def test_multiline_pasted_urls_expand(self):
        plan = self.engine.prepare({'kind': 'auto', 'content': 'https://youtu.be/00000000000\nhttps://youtu.be/00000000000'})
        self.assertEqual(plan['total'], 2)
    def test_ambiguous_choice_is_verified_and_saved(self):
        self.yt.rows=[candidate(title='Diri',artists=['Tulus']),candidate(1,title='Diri',artists=['Other'])]
        plan=self.engine.prepare({'kind':'text','content':'Diri'})
        self.assertEqual(plan['counts'],{'ambiguous':1})
        result=self.engine.choose(plan['id'],plan['entries'][0]['id'],'00000000001')
        self.assertEqual(result['entries'][0]['song']['artists'],['Other'])
        self.assertEqual(result['entries'][0]['status'],'ready')
    def test_arbitrary_choice_cannot_bypass_ambiguity_review(self):
        plan=self.prepare()
        with self.assertRaises(EngineError): self.engine.choose(plan['id'],plan['entries'][0]['id'],'abcdefghijk')


class IndexTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(); self.root = Path(self.temp.name)
        self.engine, self.index, self.yt, self.store = engine_at(self.root, [candidate()])
        self.song = candidate_song(candidate())
        self.path = self.store.root / 'unrelated-file-name.mp3'; self.path.write_bytes(b'original')
        from yono_core.media import file_hash
        self.digest = file_hash(self.path)
    def tearDown(self):
        self.index.close(); self.temp.cleanup()
    def row(self, **changes):
        return {**self.song.to_dict(), 'uri': self.path.as_uri(), 'sha256': self.digest, **changes}
    def test_metadata_dedup_despite_different_filename(self):
        self.index.snapshot('chosen-folder', [self.row()])
        row, _ = self.index.find(self.song)
        self.assertEqual(row['uri'], self.path.as_uri())
    def test_hash_bindings_survive_rename_and_missing_tags(self):
        row = self.row(); self.index.snapshot('chosen', [row]); self.index.bind(self.song, row)
        renamed = self.path.with_name('renamed-again.mp3'); self.path.rename(renamed)
        self.index.snapshot('chosen', [self.row(uri=renamed.as_uri(), title='unknown', artists=[], youtube_id='')])
        found, reason = self.index.find(self.song)
        self.assertIn('hash', reason)
        self.assertEqual(found['uri'], renamed.as_uri())
    def test_missing_file_not_deduplicated(self):
        self.index.snapshot('chosen', [self.row()]); self.path.unlink()
        self.assertIsNone(self.index.find(self.song)[0])
    def test_modified_file_not_deduplicated(self):
        self.index.snapshot('chosen', [self.row()]); self.path.write_bytes(b'not the same audio')
        self.assertIsNone(self.index.find(self.song)[0])
    def test_different_isrc_not_collapsed(self):
        self.index.snapshot('chosen', [self.row(isrc='USAAA2400001')])
        song = dataclasses.replace(self.song, youtube_id='', isrc='USAAA2400002')
        self.assertIsNone(self.index.find(song)[0])
    def test_snapshot_only_changes_own_scope(self):
        self.index.snapshot('one', [self.row()])
        other = self.path.with_name('other.mp3'); other.write_bytes(b'original')
        self.index.snapshot('two', [self.row(uri=other.as_uri())]); self.index.snapshot('one', [])
        self.assertEqual(len(self.index.records()), 1)
        self.assertEqual(self.index.records()[0]['uri'], other.as_uri())
    def test_invalid_snapshot_does_not_destroy_previous(self):
        self.index.snapshot('one', [self.row()])
        with self.assertRaises(ValueError): self.index.snapshot('one', [self.row(sha256='bad')])
        self.assertEqual(len(self.index.records()), 1)
    def test_ambiguous_local_duration_does_not_guess(self):
        other = self.path.with_name('long.mp3'); other.write_bytes(b'original')
        self.index.snapshot('one', [self.row(), self.row(uri=other.as_uri(), duration=280)])
        self.assertIsNone(self.index.find(Song('Lagu 0', ['Artis']))[0])
    def test_local_text_avoids_network(self):
        self.index.snapshot('one', [self.row()])
        plan = self.engine.prepare({'kind': 'text', 'content': 'Artis - Lagu 0'})
        self.assertEqual(plan['counts'], {'exists': 1})
        self.assertEqual(self.yt.calls, [])


class SpotifyPaginationTests(unittest.TestCase):
    def test_110_items_three_pages(self):
        rows = [{'track': {'name': f'Lagu {i}', 'id': f'{i:022d}', 'artists': [{'name': 'Artis'}], 'duration_ms': 240000}}
                for i in range(110)]
        pages = [{'items': rows[i:i+50], 'total': 110, 'next': i+50 < 110} for i in (0,50,100)]
        client = Mock(); client.playlist.return_value = {'name': 'Full', 'tracks': {'total': 110}}
        client.playlist_items.return_value = pages[0]; client.next.side_effect = pages[1:]
        source = Sources(None, Control('t'), spotify_client=client)
        result = source.spotify('0123456789012345678901', 'spotify:playlist:0123456789012345678901')
        self.assertEqual((len(result['entries']), result['count']), (110, 110))
        self.assertEqual(client.next.call_count, 2)
    def test_repeated_page_stops(self):
        row = {'track': {'name':'One'}}
        client = Mock(); client.playlist.return_value = {'tracks': {'total': 2}}
        page = {'items':[row], 'total':2, 'next':'next'}
        client.playlist_items.return_value = page; client.next.return_value = page
        with self.assertRaises(EngineError) as result: Sources(None, Control('t'), spotify_client=client).spotify('x', '')
        self.assertEqual(result.exception.code, 'REPEATED_PAGE')
    def test_token_required_no_hidden_fallback(self):
        with self.assertRaises(EngineError) as result: Sources(None, Control('t')).spotify('x', '')
        self.assertEqual(result.exception.code, 'SPOTIFY_AUTH_REQUIRED')
    def test_changed_count_stops(self):
        client=Mock(); client.playlist.return_value={'tracks':{'total':2}}
        client.playlist_items.return_value={'items':[{}], 'total':3, 'next':False}
        with self.assertRaises(EngineError): Sources(None, Control('t'), spotify_client=client).spotify('x', '')


if __name__ == '__main__': unittest.main()
