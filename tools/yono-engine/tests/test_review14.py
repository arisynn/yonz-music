import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock
from support import candidate, engine_at, PNG
from yono_core.model import EngineError
from yono_core.review import suggested_name, update_review


class Review14Tests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.engine, self.index, self.yt, self.storage = engine_at(Path(self.temp.name), [candidate(), candidate(1)])
    def tearDown(self):
        self.index.close()
        self.temp.cleanup()
    def prepare(self, **options):
        return self.engine.prepare({"kind": "url", "url": "https://music.youtube.com/playlist?list=PL123456789012345", **options})
    def test_playlist_name_from_platform_not_generic_ui_default(self):
        p = self.prepare()
        self.assertEqual(p["name"], "Playlist uji")
        self.assertFalse(p["create_playlist"])
    def test_one_song_goes_directly_to_destination_even_with_playlist_enabled(self):
        p = self.prepare(create_playlist=True)
        r = self.engine.acquire(p["id"], [p["entries"][0]["id"]])
        self.assertEqual(self.storage.path(r["entries"][0]["uri"]).parent, self.storage.root)
        self.assertTrue(r["create_playlist"])
    def test_multiple_without_playlist_go_directly_to_destination(self):
        p = self.prepare()
        r = self.engine.acquire(p["id"], [x["id"] for x in p["entries"]])
        self.assertTrue(all(self.storage.path(x["uri"]).parent == self.storage.root for x in r["entries"]))
    def test_multiple_with_playlist_use_one_named_folder(self):
        p = self.prepare(create_playlist=True)
        r = self.engine.acquire(p["id"], [x["id"] for x in p["entries"]])
        self.assertTrue(all(self.storage.path(x["uri"]).parent == self.storage.root / "Playlist uji" for x in r["entries"]))
    def test_review_keeps_ids_selection_and_name_across_reload(self):
        p = self.prepare()
        update_review(p, {"name": "My chosen name", "create_playlist": True, "entry_ids": [p["entries"][1]["id"]]})
        self.engine.save(p)
        again = self.engine.load_plan(p["id"])
        self.assertEqual(again["selected_ids"], [p["entries"][1]["id"]])
        self.assertEqual(again["name"], "My chosen name")
        self.assertEqual(again["folder"], "")
    def test_foreign_selection_and_non_boolean_option_rejected(self):
        p = self.prepare()
        for payload in ({"entry_ids": ["foreign"]}, {"entry_ids": "all"}, {"create_playlist": "false"}):
            with self.subTest(payload=payload), self.assertRaises(EngineError):
                update_review(copy.deepcopy(p), payload)
    def test_name_does_not_create_path_traversal(self):
        p = self.prepare(create_playlist=True)
        update_review(p, {"name": "../../Other/Music"})
        self.assertNotIn("/", p["folder"])
        self.assertNotIn("\\", p["folder"])
    def test_retry_does_not_move_published_audio_or_toggle_playlist(self):
        p = self.prepare(create_playlist=True)
        ids = [x["id"] for x in p["entries"]]
        r = self.engine.acquire(p["id"], ids)
        folder = r["folder"]
        update_review(r, {"name": "New display name", "entry_ids": [ids[0]]})
        self.assertEqual(r["folder"], folder)
        with self.assertRaises(EngineError):
            update_review(r, {"create_playlist": False})
    def test_accepted_ids_do_not_include_unselected_local_tracks(self):
        p = self.prepare()
        wanted = p["entries"][1]["id"]
        r = self.engine.acquire(p["id"], [wanted])
        self.assertEqual(r["accepted_ids"], [wanted])
        self.assertEqual(r["entries"][0]["status"], "ready")
        self.assertNotIn("uri", r["entries"][0])
    def test_retry_metadata_stays_in_same_plan_and_keeps_review(self):
        p = self.prepare()
        p["entries"][0]["status"] = "failed"
        p["entries"][0]["error"] = {"code": "NETWORK"}
        update_review(p, {"entry_ids": [p["entries"][1]["id"]]})
        self.engine.save(p)
        r = self.engine.retry(p["id"])
        self.assertEqual(r["id"], p["id"])
        self.assertEqual(r["selected_ids"], p["selected_ids"])
        self.assertEqual(r["entries"][0]["status"], "ready")
        self.assertEqual(self.engine.media.transfers, 0)
    def test_same_source_restores_review_without_creating_second_plan(self):
        p = self.prepare()
        update_review(p, {"name": "Edited", "entry_ids": [p["entries"][0]["id"]]})
        self.engine.save(p)
        r = self.prepare(resume_existing=True)
        self.assertEqual(r["id"], p["id"])
        self.assertEqual(r["name"], "Edited")
        self.assertEqual(len(list((self.engine.root / "plans").glob("*.json"))), 1)
    def test_changed_playlist_is_not_mistaken_for_old_review(self):
        p = self.prepare()
        self.yt.rows.append(candidate(2)); self.yt.count = 3
        r = self.prepare(resume_existing=True)
        self.assertNotEqual(r["id"], p["id"])
        self.assertEqual(r["total"], 3)
    def test_hidden_cover_and_nomedia_with_portable_relative_reference(self):
        p = self.prepare()
        self.engine.media.artwork = lambda _: PNG
        r = self.engine.acquire(p["id"], [p["entries"][0]["id"]])
        row = r["entries"][0]
        r = self.engine.repair_artwork(p["id"], row["id"])
        row = r["entries"][0]
        cover = self.storage.path(row["artwork_uri"])
        manifest = json.loads(self.storage.path(row["metadata_uri"]).read_text())
        self.assertEqual(cover.parent.name, ".cover")
        self.assertTrue((cover.parent / ".nomedia").is_file())
        self.assertFalse((self.storage.root / ".nomedia").exists())
        self.assertEqual(manifest["artwork"]["file_name"], ".cover/" + cover.name)
        self.assertEqual(cover.read_bytes(), PNG)
    def test_suggested_names_use_known_metadata_only(self):
        self.assertEqual(suggested_name({}, [{"song": {"title": "Song", "artists": ["Artist"]}}]), "Artist — Song")
        self.assertEqual(suggested_name({}, [{"song": {"title": "One", "artists": ["Artist"]}}, {"song": {"title": "Two", "artists": ["Artist"]}}]), "Koleksi Artist")
        self.assertTrue(suggested_name({}, [{"song": {"title": "One"}}, {"song": {"title": "Two"}}]).startswith("Pilihan Musik · "))
        self.assertEqual(suggested_name({"name": "From platform"}, []), "From platform")
