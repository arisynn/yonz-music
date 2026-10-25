"""CLI-style release selection: catalogue rows + all credits + version + duration + ATV."""
from __future__ import annotations
from .control import Stopped
from .model import (EngineError, Song, artist_keys, candidate_rejection, candidate_song,
                    duration_matches, norm, primary_artist, title_key, variants, verification_rejection)
from .network import session


class Catalogue:
    def __init__(self, control, client=None):
        self.control = control
        self._client = client
        self._session = None
        self.last_diagnostics = []

    @property
    def client(self):
        if self._client is None:
            from ytmusicapi import YTMusic
            self._session = session(self.control, {"music.youtube.com", "www.youtube.com", "youtube.com"})
            self._client = YTMusic(requests_session=self._session, language="en", location="US")
        return self._client

    def close(self):
        if self._session:
            self._session.close()

    def call(self, method, *args, **kwargs):
        self.control.check()
        try:
            value = getattr(self.client, method)(*args, **kwargs)
        except Exception as error:
            self.control.check()
            raise EngineError("CATALOGUE_NETWORK", "Katalog belum dapat dibaca. Coba lagi atau gunakan daftar lokal.", retryable=True) from error
        self.control.check()
        return value

    def verify(self, song, row):
        self.control.emit("verifying", title=song.title, artists=song.artists, message="Memeriksa rilisan, versi, dan durasi…")
        details = self.call("get_song", row["videoId"])
        return verification_rejection(song, row, details)

    def resolve(self, request):
        self.last_diagnostics = []
        if request.source_url and not request.youtube_id and (request.direct_audio or request.source_url.split("?", 1)[0].lower().rsplit(".", 1)[-1] in {"mp3", "flac", "m4a", "wav", "ogg", "opus", "aac", "webm", "weba"}):
            from .network import public_https
            public_https(request.source_url, self.control)
            return request, {"direct": True}
        if request.youtube_id and request.artists and request.duration:
            row = {"title": request.title, "artists": [{"name": a} for a in request.artists],
                   "duration_seconds": request.duration, "videoId": request.youtube_id,
                   "resultType": "song", "isExplicit": request.explicit}
            reason = self.verify(request, row)
            if not reason:
                return request, row
            self.last_diagnostics.append({"video_id": request.youtube_id, "reason": reason})
        queries = [f"{' '.join(request.artists)} {request.title}".strip()]
        if request.album:
            queries.append(f"{request.title} {' '.join(request.artists)} {request.album}")
        rows, seen = [], set()
        for query in queries:
            self.control.emit("matching", title=request.title, message="Mencari rilisan katalog…")
            for row in self.call("search", query, filter="songs", limit=25, ignore_spelling=True):
                self.control.check()
                identity = row.get("videoId")
                if identity in seen:
                    continue
                seen.add(identity)
                rows.append(row)
        choices = []
        for row in rows:
            self.control.check()
            try:
                found = candidate_song(row)
            except EngineError:
                continue
            if (not found.artists or not found.duration or title_key(found.title) != title_key(request.title)
                    or variants(found.title) != variants(request.title)):
                continue
            expected, actual = artist_keys(request.artists), artist_keys(found.artists)
            # Text without a duration may omit a featured credit, as the CLI did.
            if expected != actual and not (not request.duration and (not expected or (
                    expected.issubset(actual) and primary_artist(request.artists[0]) == primary_artist(found.artists[0])))):
                self.last_diagnostics.append({"video_id": found.youtube_id, "reason": "kredit artis berbeda atau tidak lengkap"})
                continue
            wanted = request if request.duration and request.artists else found
            if request.explicit is not None:
                wanted = Song.from_dict({**wanted.to_dict(), "explicit": request.explicit})
            reason = candidate_rejection(wanted, row)
            if not reason:
                reason = self.verify(wanted, row)
            if reason:
                self.last_diagnostics.append({"video_id": found.youtube_id, "reason": reason})
                continue
            result = Song.from_dict({**found.to_dict(),
                "sid": request.sid, "isrc": request.isrc, "date": request.date,
                "album": request.album or found.album, "source_url": request.source_url,
                "artwork_url": request.artwork_url or found.artwork_url})
            equivalent = any(artist_keys(other.artists) == actual and other.explicit == result.explicit
                             and duration_matches(other.duration, result.duration) for other, _ in choices)
            if not equivalent:
                choices.append((result, row))
            if len(choices) >= 8:
                break
        if not choices:
            raise EngineError("NO_OFFICIAL_MATCH", "Rilisan audio katalog dengan judul, artis, versi, dan durasi yang cocok belum ditemukan.")
        if len(choices) > 1:
            # Exact-duration metadata can rank equivalent albums, but distinct recordings require review.
            raise EngineError("AMBIGUOUS", "Ada beberapa rekaman yang cocok. Pilih rekaman atau lengkapi nama artis.",
                              choices=[song.to_dict() for song, _ in choices])
        return choices[0]
