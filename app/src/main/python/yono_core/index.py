"""SQLite sidecar for acquisition only; the app's Room remains its library authority."""
from __future__ import annotations
import json
import re
import sqlite3
from .model import Song, artist_keys, duration_matches, title_key, variants


class LibraryIndex:
    def __init__(self, path, exists, matches=None):
        self.db = sqlite3.connect(str(path))
        self.db.row_factory = sqlite3.Row
        self.exists = exists
        self.matches = matches
        self._record_cache = None
        self.db.executescript('''
          PRAGMA journal_mode=WAL;
          CREATE TABLE IF NOT EXISTS selected_files(uri TEXT PRIMARY KEY, scope TEXT, digest TEXT, data TEXT);
          CREATE TABLE IF NOT EXISTS bindings(kind TEXT, value TEXT, digest TEXT, PRIMARY KEY(kind,value,digest));
          CREATE INDEX IF NOT EXISTS binding_digest ON bindings(digest);
        ''')

    def close(self):
        self.db.close()

    def snapshot(self, scope, rows):
        """Replace one completed selected-source snapshot, never erase another source."""
        prepared = []
        for row in rows:
            song = Song.from_dict(row)
            digest = row.get("sha256", "")
            if digest and not re.fullmatch(r"[a-f0-9]{64}", digest):
                raise ValueError("invalid sha256")
            prepared.append((str(row["uri"]), str(scope), digest, json.dumps({**song.to_dict(), "uri": row["uri"],
                "sha256": digest, "relative_path": row.get("relative_path", "")}, ensure_ascii=False)))
        with self.db:
            self.db.execute("DELETE FROM selected_files WHERE scope=?", (str(scope),))
            self.db.executemany("INSERT OR REPLACE INTO selected_files VALUES(?,?,?,?)", prepared)
        self._record_cache = None

    def records(self):
        if self._record_cache is None:
            self._record_cache = [json.loads(r[0]) for r in self.db.execute("SELECT data FROM selected_files")]
        return self._record_cache

    @staticmethod
    def identities(song):
        return [(k, v) for k, v in (("spotify", song.sid), ("isrc", song.isrc), ("youtube", song.youtube_id), ("source", song.source_url if not song.youtube_id else "")) if v]

    def bind(self, song, row):
        if not row.get("sha256"):
            return
        with self.db:
            self.db.executemany("INSERT OR IGNORE INTO bindings VALUES(?,?,?)", [(k, v, row["sha256"]) for k, v in self.identities(song)])

    def add(self, scope, song, uri, digest):
        row = {**song.to_dict(), "uri": uri, "sha256": digest}
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO selected_files VALUES(?,?,?,?)", (uri, scope, digest, json.dumps(row)))
        self._record_cache = None
        self.bind(song, row)
        return row

    def find(self, wanted):
        histories = set()
        for kind, value in self.identities(wanted):
            histories.update(r[0] for r in self.db.execute("SELECT digest FROM bindings WHERE kind=? AND value=?", (kind, value)))
        matches = []
        for row in self.records():
            actual = Song.from_dict(row)
            if wanted.duration and not duration_matches(wanted.duration, actual.duration):
                continue
            reason = ""
            if wanted.isrc and actual.isrc and wanted.isrc != actual.isrc:
                continue
            if row.get("sha256") and row["sha256"] in histories and (wanted.duration or (wanted.source_url and not wanted.youtube_id)):
                reason = "hash + riwayat ID"
            elif wanted.sid and wanted.sid == actual.sid and wanted.duration:
                reason = "ID Spotify + durasi"
            elif wanted.youtube_id and wanted.youtube_id == actual.youtube_id and wanted.duration:
                reason = "ID YouTube + durasi"
            elif wanted.isrc and wanted.isrc == actual.isrc and wanted.duration:
                reason = "ISRC + durasi"
            elif (wanted.artists and artist_keys(wanted.artists) == artist_keys(actual.artists)
                  and title_key(wanted.title) == title_key(actual.title)
                  and variants(wanted.title) == variants(actual.title)
                  and (not wanted.duration or duration_matches(wanted.duration, actual.duration, 2.5))
                  and (wanted.explicit is None or actual.explicit is None or wanted.explicit == actual.explicit)):
                reason = "judul + seluruh artis + versi + durasi" if wanted.duration else "judul + seluruh artis + versi"
            if reason and self.exists(row["uri"]):
                if not self.matches or not row.get("sha256") or self.matches(row["uri"], row["sha256"]):
                    matches.append((row, reason))
        # Missing duration requires an unambiguous existing recording.
        if not wanted.duration and len({(artist_keys_key(r), round(r.get("duration", 0)), r.get("explicit")) for r, _ in matches}) > 1:
            return None, ""
        return matches[0] if matches else (None, "")

    def relative(self, path):
        # Exact source-relative names supplied by the selected-tree scanner only.
        matches = [r for r in self.records() if r.get("relative_path") == path and self.exists(r["uri"])]
        return matches[0] if len(matches) == 1 else None

    def retagged(self, old_digest, song, uri, digest):
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO bindings SELECT kind,value,? FROM bindings WHERE digest=?", (digest, old_digest))
        return self.add("download", song, uri, digest)


def artist_keys_key(row):
    return tuple(sorted(artist_keys(row.get("artists", []))))
