import json
import unittest
from yono_core.inputs import parse, spotify_playlist, youtube_target
from yono_core.model import (EngineError, Song, artist_keys, candidate_rejection, candidate_song,
                            duration_matches, safe_name, seconds, title_key, verification_rejection)
from yono_core.catalogue import Catalogue
from yono_core.control import Control
from support import FakeYT, candidate, details


class InputTests(unittest.TestCase):
    def test_numbered_markdown(self):
        data = parse('# Pilihan\n```text\n1. **Tulus - Diri**\n- Fourtwnty; Charita Utami — Mangu\n```')
        self.assertEqual(data["count"], 2)
        self.assertEqual(data["entries"][1]["song"]["artists"], ["Fourtwnty", "Charita Utami"])
    def test_title_only(self):
        self.assertEqual(parse("Diri")["entries"][0]["song"]["artists"], [])
    def test_hyphen_in_artist(self):
        row = parse("Jay-Z - Song With - A Subtitle")["entries"][0]["song"]
        self.assertEqual((row["artists"], row["title"]), (["Jay-Z"], "Song With - A Subtitle"))
    def test_markdown_link_preserves_query(self):
        url = "https://music.youtube.com/watch?v=abcdefghijk&list=PL1234567890"
        self.assertEqual(parse(f"[Song]({url})")["entries"][0]["url"], url)
    def test_watch_with_list_is_single(self):
        self.assertEqual(youtube_target("https://music.youtube.com/watch?v=abcdefghijk&list=RD1234567890"), ("track", "abcdefghijk"))
    def test_short_link(self):
        self.assertEqual(youtube_target("https://youtu.be/abcdefghijk"), ("track", "abcdefghijk"))
    def test_shorts(self):
        self.assertEqual(youtube_target("https://youtube.com/shorts/abcdefghijk"), ("track", "abcdefghijk"))
    def test_radio_rejected_before_network(self):
        with self.assertRaisesRegex(EngineError, "radio"):
            youtube_target("https://music.youtube.com/playlist?list=RD123456789012")
    def test_bad_video_id(self):
        with self.assertRaises(EngineError): youtube_target("https://youtube.com/watch?v=oops")
    def test_spotify_tracking_ignored(self):
        self.assertEqual(spotify_playlist("https://open.spotify.com/playlist/0123456789012345678901?si=foo&utm_source=copy"), "0123456789012345678901")
    def test_spotify_uri(self):
        self.assertEqual(spotify_playlist("spotify:playlist:0123456789012345678901"), "0123456789012345678901")
    def test_spotify_placeholder_rejected(self):
        with self.assertRaises(EngineError): spotify_playlist("https://open.spotify.com/playlist/ID_PLAYLIST")
    def test_bad_host_not_spotify(self):
        self.assertIsNone(spotify_playlist("https://open.spotify.com.evil.test/playlist/0123456789012345678901"))
    def test_csv_export_headers(self):
        value = 'Track Name,Artist Name(s),Duration (ms),Track URI\nMangu,Fourtwnty;Charita Utami,240000,spotify:track:0123456789012345678901'
        row = parse(value, kind="csv")["entries"][0]["song"]
        self.assertEqual((row["duration"], row["sid"]), (240, "0123456789012345678901"))
        self.assertEqual(len(row["artists"]), 2)
    def test_csv_missing_duration_can_resolve_catalogue(self):
        row = parse("artist,title\nTulus,Diri", kind="csv")["entries"][0]["song"]
        self.assertEqual(row["duration"], 0)
    def test_json_plain_and_export(self):
        row = parse(json.dumps({"tracks": [{"name": "Diri", "artists": [{"name": "Tulus"}], "duration_ms": 240000}]}), kind="json")["entries"][0]["song"]
        self.assertEqual(row["artists"], ["Tulus"])
    def test_json_spotdl_url_identity(self):
        data=[{'name':'Diri','artists':['Tulus'],'duration':240,'url':'https://open.spotify.com/track/0123456789012345678901'}]
        row=parse(json.dumps(data),kind='spotdl')['entries'][0]['song']
        self.assertEqual(row['sid'],'0123456789012345678901')
    def test_json_invalid_rows_remain_counted(self):
        result = parse('[1,{"title":"Diri","artist":"Tulus"}]', kind="json")
        self.assertEqual(result["count"], 2)
        self.assertIn("error", result["entries"][0])
    def test_m3u_path_does_not_become_search(self):
        self.assertEqual(parse('#EXTM3U\n#EXTINF:240,Diri\nAlbum/Tulus - Diri.mp3', kind="m3u")["entries"][0]["local_path"], "Album/Tulus - Diri.mp3")
    def test_m3u_url(self):
        self.assertIn("url", parse('https://youtu.be/abcdefghijk', kind="m3u8")["entries"][0])
    def test_input_size_limit(self):
        with self.assertRaises(EngineError): parse('a' * (2 * 1024 * 1024 + 1))
    def test_entry_limit_is_error_not_truncation(self):
        with self.assertRaises(EngineError): parse('\n'.join('Artis - Lagu' for _ in range(5001)))
    def test_empty(self):
        with self.assertRaises(EngineError): parse('# kosong\n```')
    def test_unicode_safe_filename(self):
        name = safe_name('../A/B:* 💙' * 50)
        self.assertNotIn('/', name)
        self.assertLessEqual(len(name.encode()), 150)
    def test_nonfinite_duration(self):
        self.assertEqual(seconds('nan'), 0)
        self.assertEqual(seconds('inf'), 0)
    def test_invalid_explicit_type(self):
        with self.assertRaises(EngineError): Song("Lagu", ["Artis"], 240, explicit="false")


class MatchingTests(unittest.TestCase):
    def setUp(self):
        self.row = candidate(title="Diri", artists=["Tulus"])
        self.song = candidate_song(self.row)
    def test_valid_full_verification(self):
        self.assertEqual(verification_rejection(self.song, self.row, details(self.row)), "")
    def test_cover_artist_rejected(self):
        changed = {**self.row, "artists": [{"name": "Penyanyi Cover"}]}
        self.assertIn("artis", candidate_rejection(self.song, changed))
    def test_live_not_plain(self):
        self.assertTrue(candidate_rejection(self.song, {**self.row, "title": "Diri (Live)"}))
    def test_missing_featured_artist_rejected(self):
        song = Song("Diri", ["Tulus", "Guest"], 240)
        self.assertIn("artis", candidate_rejection(song, self.row))
    def test_featured_spelling_normalized(self):
        self.assertEqual(artist_keys(["Fourtwnty Featuring Charita Utami"]), artist_keys(["Fourtwnty", "Charita Utami"]))
    def test_wrong_duration_rejected(self):
        self.assertIn("durasi", candidate_rejection(self.song, {**self.row, "duration_seconds": 100}))
    def test_music_video_not_atv(self):
        data = details(self.row); data["videoDetails"]["musicVideoType"] = "MUSIC_VIDEO_TYPE_OMV"
        self.assertIn("ATV", verification_rejection(self.song, self.row, data))
    def test_official_word_is_not_proof(self):
        data = details(self.row); data["videoDetails"]["musicVideoType"] = "MUSIC_VIDEO_TYPE_UGC"
        self.assertTrue(verification_rejection(self.song, self.row, data))
    def test_explicit_mismatch(self):
        self.assertIn("explicit", candidate_rejection(self.song, {**self.row, "isExplicit": True}))
    def test_changed_video_id(self):
        data = details(self.row); data["videoDetails"]["videoId"] = "abcdefghijk"
        self.assertTrue(verification_rejection(self.song, self.row, data))
    def test_private_playability(self):
        data = details(self.row); data["playabilityStatus"]["status"] = "LOGIN_REQUIRED"
        self.assertTrue(verification_rejection(self.song, self.row, data))
    def test_presentational_title_only_removed(self):
        self.assertEqual(title_key("Diri (Official Audio)"), "diri")
        self.assertNotEqual(title_key("Diri (Live)"), "diri")
    def test_duration_tolerance(self):
        self.assertTrue(duration_matches(240, 243))
        self.assertFalse(duration_matches(240, 250))
    def test_text_single_artist_match(self):
        song, _ = Catalogue(Control("t"), FakeYT([self.row])).resolve(Song("Diri", ["Tulus"]))
        self.assertEqual(song.youtube_id, self.row["videoId"])
    def test_text_featured_credit_expansion(self):
        row = candidate(title="Mangu", artists=["Fourtwnty Featuring Charita Utami"])
        song, _ = Catalogue(Control("t"), FakeYT([row])).resolve(Song("Mangu", ["Fourtwnty"]))
        self.assertEqual(len(artist_keys(song.artists)), 2)
    def test_title_only_ambiguity(self):
        client = FakeYT([self.row, candidate(1, title="Diri", artists=["Other"])])
        with self.assertRaises(EngineError) as result: Catalogue(Control("t"), client).resolve(Song("Diri"))
        self.assertEqual(result.exception.code, "AMBIGUOUS")
        self.assertEqual(len(result.exception.choices), 2)
    def test_equivalent_reissues_not_ambiguous(self):
        client = FakeYT([self.row, {**self.row, "videoId": "abcdefghijk", "album": {"name": "Reissue"}}])
        self.assertTrue(Catalogue(Control("t"), client).resolve(Song("Diri", ["Tulus"]))[0].youtube_id)
    def test_no_match(self):
        with self.assertRaises(EngineError): Catalogue(Control("t"), FakeYT([])).resolve(Song("Unknown", ["Tulus"]))

if __name__ == '__main__': unittest.main()
