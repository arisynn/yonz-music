"""Controlled metadata fixtures; no claims about live YouTube availability."""
import unittest
from yono_core.control import Control, Stopped
from yono_discovery import run, song, artwork

ARTIST_ID = "UC" + "a" * 22

def row(i=1, title="Bertaut", credit="Nadin Amizah", **extra):
    return dict(videoId=f"{i:011d}", title=title, artists=[{"name": credit, "id": ARTIST_ID}],
                album={"name": "Selamat Ulang Tahun"}, duration="5:10", resultType="song",
                **extra)

class FakeCatalog:
    def __init__(self, songs=None):
        self.songs = songs or [row()]
        self.calls = []
    def call(self, method, *args, **kwargs):
        self.calls.append((method, args, kwargs))
        if method == "search":
            return ([{"resultType": "artist", "artist": "Nadin Amizah", "browseId": ARTIST_ID}]
                    if kwargs.get("filter") == "artists" else self.songs)
        if method == "get_charts":
            return {"artists": [{"title": "Nadin Amizah", "browseId": ARTIST_ID, "rank": None}], "videos": []}
        if method == "get_watch_playlist":
            return {"tracks": self.songs}
        if method == "get_artist":
            return {"songs": {"results": self.songs}}
        raise AssertionError(method)

class DiscoveryTests(unittest.TestCase):
    def test_music_search_uses_songs_and_artists_never_generic_video_search(self):
        catalog=FakeCatalog()
        result=run({"query":"Bertaut"},Control("fixture"),catalog)
        self.assertEqual("Nadin Amizah",result["songs"][0]["artists"][0])
        self.assertEqual(ARTIST_ID,result["artists"][0]["id"])
        self.assertEqual(["songs","artists"],[c[2]["filter"] for c in catalog.calls])

    def test_named_performer_cover_is_allowed_and_prioritized(self):
        catalog=FakeCatalog([row(),row(2,"Bertaut (Cover)","Tami Aulia")])
        ordinary=run({"query":"Bertaut"},Control("fixture"),catalog)
        requested=run({"query":"Bertaut Tami Aulia"},Control("fixture"),catalog)
        self.assertEqual(["Nadin Amizah"],[r["artists"][0] for r in ordinary["songs"]])
        self.assertEqual("Tami Aulia",requested["songs"][0]["artists"][0])

    def test_music_versions_compilations_and_unavailable_rows_are_filtered(self):
        for title in ["Bertaut (Cover)","Bertaut slowed reverb","Kumpulan Lagu Nadin","Bertaut karaoke","Bertaut DJ mix"]:
            self.assertIsNone(song(row(title=title)))
        self.assertIsNone(song(row(videoType="MUSIC_VIDEO_TYPE_UGC")))
        self.assertIsNone(song(row(isAvailable=False)))
        self.assertIsNone(song(row(duration_seconds=3600)))
        self.assertIsNotNone(song(row(videoType="MUSIC_VIDEO_TYPE_ATV")))

    def test_ordinary_titles_are_not_mistaken_for_recording_markers(self):
        self.assertIsNotNone(song(row(title="Cover Me In Sunshine")))
        self.assertIsNotNone(song(row(title="Live Forever")))
        self.assertIsNone(song(row(title="Bertaut (Live at Studio)")))

    def test_radio_deduplicates_and_excludes_the_seed(self):
        catalog=FakeCatalog([row(),row(2),row(2),row(3,videoType="MUSIC_VIDEO_TYPE_UGC")])
        result=run({"operation":"radio","video_id":"00000000001"},Control("fixture"),catalog)
        self.assertEqual(["00000000002"],[s["video_id"] for s in result["songs"]])
        self.assertTrue(catalog.calls[0][2]["radio"])

    def test_artist_selection_keeps_that_artists_cover(self):
        result=run({"operation":"artist","artist_id":ARTIST_ID},Control("fixture"),FakeCatalog([row(2,"Bertaut (Cover)","Tami Aulia")]))
        self.assertEqual("Tami Aulia",result["songs"][0]["artists"][0])

    def test_charts_do_not_fabricate_rankings_or_song_rows(self):
        catalog=FakeCatalog()
        songs=run({"operation":"popular_songs","region":"ID"},Control("fixture"),catalog)
        artists=run({"operation":"popular_artists","region":"ZZ"},Control("fixture"),catalog)
        self.assertEqual([],songs["songs"])
        self.assertNotIn("rank",artists["artists"][0])
        self.assertEqual("ID",catalog.calls[0][1][0])
        self.assertEqual("ZZ",catalog.calls[1][1][0])

    def test_artwork_url_validation_and_medium_thumbnail(self):
        self.assertEqual("",artwork([{"url":"https://lh3.googleusercontent.com.evil.test/image"}]))
        self.assertEqual("https://lh3.googleusercontent.com/medium",artwork([
            {"url":"https://lh3.googleusercontent.com/original","width":2000},
            {"url":"https://lh3.googleusercontent.com/medium","width":512}]))

    def test_invalid_ids_and_regions_do_not_request_network(self):
        for payload in [{"operation":"radio","video_id":"bad"},{"operation":"artist","artist_id":"../bad"},{"operation":"popular_songs","region":"invalid"}]:
            catalog=FakeCatalog()
            with self.assertRaises(ValueError):run(payload,Control("fixture"),catalog)
            self.assertEqual([],catalog.calls)
