import base64
import copy
from pathlib import Path
import shutil
from yono_core.catalogue import Catalogue
from yono_core.control import Control
from yono_core.engine import Engine
from yono_core.index import LibraryIndex
from yono_core.media import Media, file_hash
from yono_core.model import EngineError
from yono_core.sources import Sources
from yono_core.storage_host import DirectoryStore

PNG = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a1ioAAAAASUVORK5CYII=")


def candidate(i=0, title=None, artists=None, duration=240):
    return {"title": title or f"Lagu {i}", "artists": [{"name": a} for a in (artists or ["Artis"])],
            "videoId": f"{i:011d}", "duration_seconds": duration, "resultType": "song",
            "videoType": "MUSIC_VIDEO_TYPE_ATV", "isExplicit": False,
            "isAvailable": True, "album": {"name": "Album"}}


def details(row):
    return {"playabilityStatus": {"status": "OK"}, "videoDetails": {
        "videoId": row["videoId"], "title": row["title"], "lengthSeconds": row["duration_seconds"],
        "musicVideoType": "MUSIC_VIDEO_TYPE_ATV"}}


class FakeYT:
    def __init__(self, rows):
        self.rows = copy.deepcopy(rows)
        self.calls = []
        self.count = len(rows)
    def get_playlist(self, identity, limit=None):
        self.calls.append(("get_playlist", identity, limit))
        return {"title": "Playlist uji", "tracks": copy.deepcopy(self.rows), "trackCount": self.count}
    def get_song(self, identity):
        self.calls.append(("get_song", identity))
        return details(next(r for r in self.rows if r["videoId"] == identity))
    def search(self, query, **kwargs):
        self.calls.append(("search", query))
        return copy.deepcopy(self.rows)
    def get_watch_playlist(self, **kwargs):
        return {"tracks": [copy.deepcopy(r) for r in self.rows if r["videoId"] == kwargs["videoId"]]}


class FakeMedia:
    ffmpeg = "fixture-runtime"
    def __init__(self):
        self.transfers = 0
    def transfer(self, song, workspace):
        self.transfers += 1
        path = workspace / "source.fixture"
        path.write_bytes(b"synthetic test fixture; not a playable song")
        return path
    def finish(self, source, song, workspace, **options):
        return {"path": str(source), "extension": "mp3", "mime": "audio/mpeg",
                "sha256": file_hash(source), "size": source.stat().st_size,
                "duration": song.duration, "tags_embedded": True, "artwork_embedded": False}


def engine_at(root, rows, control=None, media=None):
    root = Path(root)
    state = root / "state"
    state.mkdir(exist_ok=True)
    storage = DirectoryStore(root / "Music", state / "receipts")
    index = LibraryIndex(state / "index.sqlite", storage.exists, storage.matches)
    control = control or Control("fixture")
    yt = FakeYT(rows)
    catalogue = Catalogue(control, yt)
    sources = Sources(catalogue, control)
    media = media or FakeMedia()
    engine = Engine(state, index, sources, catalogue, media, storage, control)
    return engine, index, yt, storage
