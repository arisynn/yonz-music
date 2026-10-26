"""Playlist metadata is enumerated completely before any audio transfer."""
from __future__ import annotations
import hashlib
import json
from urllib.parse import urlsplit
from .inputs import MAX_ENTRIES, parse, spotify_playlist, youtube_target, normalize_link
from .model import EngineError, Song, candidate_song, thumbnail
from .network import public_https, session, youtube_dl


def spotify_song(raw):
    if not isinstance(raw, dict) or raw.get("type", "track") != "track" or raw.get("is_local"):
        raise EngineError("UNAVAILABLE", "Entri lokal, podcast, atau lagu tidak tersedia.")
    if not raw.get("id") or not raw.get("name") or not raw.get("duration_ms"):
        raise EngineError("UNAVAILABLE", "Identitas/durasi metadata lagu tidak lengkap.")
    album = raw.get("album") or {}
    return Song(raw.get("name", ""), [a["name"] for a in raw.get("artists", []) if a.get("name")],
                float(raw.get("duration_ms") or 0) / 1000, sid=raw.get("id", ""),
                isrc=(raw.get("external_ids") or {}).get("isrc", ""), album=album.get("name", ""),
                date=album.get("release_date", ""), explicit=raw.get("explicit"), artwork_url=thumbnail(album.get("images")))


class Sources:
    def __init__(self, catalogue, control, *, spotify_token="", spotify_client=None, runtime=None):
        self.catalogue, self.control = catalogue, control
        self.spotify_token, self.spotify_client, self.runtime = spotify_token, spotify_client, runtime

    def load(self, request):
        if request.get("kind") in {"text", "txt", "csv", "json", "spotdl", "m3u", "m3u8"}:
            return parse(request.get("content", ""), request.get("name", "Playlist"), request.get("kind"))
        value = str(request.get("url") or request.get("content") or "").strip().strip('"\'')
        if "\n" in value:
            return parse(value, request.get("name", "Playlist"))
        value = normalize_link(value) or value
        if urlsplit(value).hostname in {"spotify.link", "spoti.fi"}:
            # Only a user-triggered review follows these known share hosts. Every
            # redirect is checked before forwarding, including private-IP checks.
            allowed = {"spotify.link", "spoti.fi", "open.spotify.com"}
            with session(self.control, allowed) as client:
                response = client.head(value, allow_redirects=True)
                if response.status_code >= 400 or urlsplit(response.url).hostname != "open.spotify.com":
                    raise EngineError("SHARE_LINK", "Tautan singkat belum dapat dibaca. Tempel tautan playlist lengkap atau ekspor daftar.", retryable=True)
                value = normalize_link(response.url) or response.url
        pid = spotify_playlist(value)
        if pid:
            return self.spotify(pid, value)
        target = youtube_target(value)
        if target:
            return self.youtube(target, value)
        if value.startswith("https://"):
            return self.external(value)
        if "://" in value:
            raise EngineError("INVALID_LINK", "Gunakan tautan HTTPS.")
        return parse(value, request.get("name", "Playlist"))

    def youtube(self, target, origin):
        kind, identity = target
        if kind == "playlist":
            self.control.emit("reading_playlist", message="Membaca seluruh daftar playlist…", completed=0, total=None)
            data = self.catalogue.call("get_playlist", identity, limit=None)
            rows, expected = data.get("tracks"), data.get("trackCount")
            if not isinstance(rows, list) or isinstance(expected, bool) or not isinstance(expected, int):
                raise EngineError("UNVERIFIED_COUNT", "Jumlah playlist tidak dapat diverifikasi; gunakan playlist tetap atau ekspor teks.")
            if expected > MAX_ENTRIES:
                raise EngineError("TOO_MANY_ENTRIES", f"Playlist melebihi batas {MAX_ENTRIES}; tidak dipotong.")
            if len(rows) != expected:
                raise EngineError("INCOMPLETE_PLAYLIST", f"Playlist belum lengkap: {len(rows)}/{expected}. Belum ada audio diunduh.", retryable=True)
            entries = []
            for position, row in enumerate(rows, 1):
                self.control.check()
                try:
                    if not isinstance(row, dict) or row.get("isAvailable") is False:
                        raise EngineError("UNAVAILABLE", "Lagu tidak tersedia.")
                    if row.get("videoType") not in {None, "MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"}:
                        raise EngineError("NO_OFFICIAL_MATCH", "Entri bukan rilisan katalog/klip resmi yang dapat dicocokkan.")
                    song = candidate_song(row)
                    song.source_url = origin
                    entries.append({"song": song.to_dict(), "input": song.label})
                except EngineError as error:
                    entries.append({"error": str(error), "code": error.code, "input": f"Entri {position}"})
            return {"name": data.get("title") or "Playlist", "entries": entries, "count": expected,
                    "completeness": "complete", "origin": origin}
        details = self.catalogue.call("get_song", identity)
        video = details.get("videoDetails") or {}
        if video.get("musicVideoType") not in {"MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"}:
            raise EngineError("NO_OFFICIAL_MATCH", "Tautan belum terverifikasi sebagai rilisan audio/klip resmi. Coba Artis - Judul.")
        watch = self.catalogue.call("get_watch_playlist", videoId=identity, limit=1)
        row = next((r for r in watch.get("tracks", []) if r.get("videoId") == identity), None)
        if not row:
            raise EngineError("INCOMPLETE_CREDITS", "Kredit lengkap lagu belum dapat diperiksa.")
        song = candidate_song({**row, "duration_seconds": row.get("duration_seconds") or video.get("lengthSeconds")})
        song.source_url = origin
        return {"name": song.title, "entries": [{"song": song.to_dict(), "input": origin}],
                "count": 1, "completeness": "complete", "origin": origin}

    def spotify(self, pid, origin):
        if not self.spotify_client and not self.spotify_token:
            raise EngineError("SPOTIFY_AUTH_REQUIRED", "Pembaca metadata Spotify memerlukan koneksi API yang diizinkan. Impor TXT/CSV/JSON juga tersedia.")
        client = self.spotify_client
        http = None
        if client is None:
            http = session(self.control, {"api.spotify.com"})
            token = self.spotify_token
            control = self.control
            class OfficialClient:
                def get(self, url):
                    p = urlsplit(url)
                    if p.scheme != "https" or p.netloc != "api.spotify.com" or not p.path.startswith("/v1/"):
                        raise EngineError("INVALID_PAGINATION", "Tautan halaman metadata tidak valid.")
                    for attempt in range(3):
                        response = http.get(url, headers={"Authorization": "Bearer " + token})
                        if response.status_code == 429 and attempt < 2:
                            try:
                                delay = float(response.headers.get("Retry-After", "5"))
                            except ValueError:
                                delay = 5
                            if delay > 30:
                                raise EngineError("RATE_LIMIT", "Layanan membatasi permintaan. Coba lagi nanti.", retryable=True)
                            control.emit("waiting", seconds=max(1, delay), message="Menunggu batas layanan…")
                            control.wait(max(1, delay))
                            continue
                        if response.status_code == 401:
                            raise EngineError("SPOTIFY_AUTH_REQUIRED", "Koneksi Spotify kedaluwarsa atau ditolak. Hubungkan ulang melalui Koneksi playlist Spotify.")
                        if response.status_code == 403:
                            raise EngineError("SPOTIFY_PLAYLIST_ACCESS", "Spotify menolak akses isi playlist. Development Mode umumnya hanya mengizinkan playlist milik/kolaborasi pengguna; periksa akun yang diizinkan di aplikasi Developer atau impor ekspor TXT/CSV/JSON.")
                        if response.status_code == 404:
                            raise EngineError("SPOTIFY_PLAYLIST_MISSING", "Playlist tidak ditemukan atau tidak dapat dilihat dengan akun ini.")
                        if response.status_code >= 400:
                            raise EngineError("SOURCE_HTTP", f"Sumber metadata memberi HTTP {response.status_code}.", retryable=response.status_code >= 500)
                        return response.json()
                def playlist(self, identity):
                    return self.get(f"https://api.spotify.com/v1/playlists/{identity}")
                def playlist_items(self, identity):
                    return self.get(f"https://api.spotify.com/v1/playlists/{identity}/items?limit=50&offset=0")
                def next(self, page):
                    return self.get(page["next"])
            client = OfficialClient()
        try:
            metadata = client.playlist(pid)
            if not isinstance(metadata, dict):
                raise EngineError("SOURCE_METADATA", "Metadata playlist tidak valid.")
            if "items" not in metadata and "tracks" not in metadata:
                raise EngineError("SPOTIFY_PLAYLIST_ACCESS", "Spotify memberikan nama playlist tetapi tidak mengizinkan isi lagunya. Gunakan playlist yang dapat diakses akun/aplikasi ini atau impor ekspor daftar.")
            block = metadata.get("items") or metadata.get("tracks") or {}
            meta_total = block.get("total") if isinstance(block, dict) else None
            page, rows, seen, expected = client.playlist_items(pid), [], set(), None
            while True:
                self.control.check()
                if not isinstance(page, dict) or not isinstance(page.get("items"), list):
                    raise EngineError("INCOMPLETE_PLAYLIST", "Halaman metadata tidak lengkap.")
                count = page.get("total")
                if isinstance(count, bool) or not isinstance(count, int) or not 0 <= count <= MAX_ENTRIES:
                    raise EngineError("UNVERIFIED_COUNT", "Jumlah playlist tidak valid atau melebihi batas.")
                if expected is None:
                    expected = count
                if count != expected or (meta_total is not None and meta_total != expected):
                    raise EngineError("PLAYLIST_CHANGED", "Jumlah playlist berubah selama dibaca. Ulangi pembacaan.", retryable=True)
                stamp = hashlib.sha256(json.dumps(page["items"], sort_keys=True).encode()).hexdigest()
                if stamp in seen and page["items"]:
                    raise EngineError("REPEATED_PAGE", "Halaman playlist berulang; hasil sebagian tidak diterima.")
                seen.add(stamp)
                rows.extend(page["items"])
                self.control.emit("reading_playlist", completed=len(rows), total=expected, message="Membaca daftar playlist…")
                if len(rows) > expected or (page.get("next") and not page["items"]):
                    raise EngineError("INCOMPLETE_PLAYLIST", "Pagination metadata tidak konsisten.")
                if not page.get("next"):
                    break
                page = client.next(page)
            if len(rows) != expected:
                raise EngineError("INCOMPLETE_PLAYLIST", f"Playlist belum lengkap: {len(rows)}/{expected}.", retryable=True)
            entries = []
            for row in rows:
                try:
                    song = spotify_song(row.get("item") or row.get("track"))
                    song.source_url = origin
                    entries.append({"song": song.to_dict(), "input": song.label})
                except (EngineError, TypeError, ValueError, AttributeError) as error:
                    entries.append({"error": str(error), "code": "UNAVAILABLE", "input": "Lagu tidak tersedia"})
            return {"name": metadata.get("name") or "Playlist", "entries": entries, "count": expected,
                    "origin": origin, "completeness": "complete"}
        finally:
            if http:
                http.close()

    def external(self, url):
        public_https(url, self.control)
        if urlsplit(url).path.lower().rsplit(".", 1)[-1] in {"mp3", "flac", "m4a", "wav", "ogg", "opus", "aac", "webm", "weba"}:
            import yono_engine
            qjs = ((self.runtime or {}).get("quickjs") or {}).get("path", "")
            record = json.loads(yono_engine.inspect(url, False, qjs, json.dumps(getattr(self.control, "network_options", {}))))
            song = Song(record["title"], [record["artist"]], record["duration"] / 1000, album=record.get("album", ""), source_url=url, artwork_url=record.get("artUrl", ""), direct_audio=True)
            return {"name": song.title, "entries": [{"song": song.to_dict(), "input": url}], "count": 1, "completeness": "complete", "origin": url}
        self.control.emit("reading_playlist", message="Membaca metadata tautan…")
        with youtube_dl(self.control, self.runtime, flat=True) as downloader:
            data = downloader.extract_info(url, download=False)
            if not isinstance(data, dict):
                raise EngineError("SOURCE_METADATA", "Sumber tidak memberi metadata.")
            entries = data.get("entries") if data.get("_type") in {"playlist", "multi_video"} else [data]
            rows = []
            for row in entries or []:
                self.control.check()
                if len(rows) >= MAX_ENTRIES:
                    raise EngineError("TOO_MANY_ENTRIES", "Daftar terlalu panjang; hasil tidak dipotong.")
                try:
                    if not isinstance(row, dict):
                        raise EngineError("UNAVAILABLE", "Entri tidak tersedia.")
                    artists = row.get("artists") or ([row["artist"]] if row.get("artist") else [])
                    if isinstance(artists, str):
                        artists = [artists]
                    title = row.get("track") or row.get("title", "")
                    standalone = (row.get("vcodec") == "none" or any(f.get("vcodec") == "none" for f in row.get("formats", []) if isinstance(f, dict)))
                    source = row.get("webpage_url") or row.get("original_url") or row.get("url") or url
                    # A generic audio response can omit codec details; known audio
                    # container metadata is accepted only provisionally. The native
                    # parser still rejects video/corrupt bytes before publication.
                    standalone = standalone or (row.get("ext") in {"mp3", "flac", "m4a", "wav", "ogg", "opus", "aac", "webm", "weba"} and row.get("vcodec") in {None, "none"} and data.get("_type") not in {"playlist", "multi_video"})
                    if standalone:
                        song = Song(title or "Audio", artists, row.get("duration") or 0,
                                    album=row.get("album") or "", source_url=source, artwork_url=row.get("thumbnail") or "", direct_audio=True,
                                    metadata_origin="source" if row.get("track") or artists or row.get("album") else "filename")
                        rows.append({"song": song.to_dict(), "input": source})
                    else:
                        raise EngineError("SOURCE_AUDIO_METADATA", "Aliran audio asli pada tautan ini belum dapat diverifikasi. Gunakan tautan audio langsung; rekaman lain tidak dipilih otomatis.")
                except EngineError as error:
                    rows.append({"error": str(error), "code": error.code, "input": "Entri sumber"})
                self.control.emit("reading_playlist", completed=len(rows), total=data.get("playlist_count"))
            expected = data.get("playlist_count") or data.get("n_entries")
            if isinstance(expected, int) and len(rows) != expected:
                raise EngineError("INCOMPLETE_PLAYLIST", f"Metadata sebagian: {len(rows)}/{expected}.")
            if not rows:
                raise EngineError("EMPTY_INPUT", "Tidak ada entri metadata pada tautan.")
            return {"name": data.get("title") or "Playlist", "entries": rows, "count": len(rows), "origin": url,
                    "completeness": "complete" if isinstance(expected, int) else "unverified"}
