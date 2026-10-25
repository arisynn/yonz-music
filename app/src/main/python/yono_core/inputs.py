"""Bounded TXT/CSV/JSON/M3U import. Every input position gets a result."""
from __future__ import annotations
import csv
import io
import json
import re
from urllib.parse import parse_qs, urlsplit
from .model import Song, EngineError, norm, seconds

MAX_ENTRIES = 5000
MAX_INPUT_BYTES = 2 * 1024 * 1024
YT_HOSTS = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}


def youtube_target(value):
    parsed = urlsplit(value)
    if parsed.hostname not in YT_HOSTS:
        return None
    query = parse_qs(parsed.query)
    video = query.get("v", [""])[0]
    if parsed.hostname == "youtu.be":
        video = parsed.path.strip("/").split("/")[0]
    if parsed.path.startswith(("/shorts/", "/embed/")):
        video = parsed.path.split("/")[2]
    if video:
        if re.fullmatch(r"[A-Za-z0-9_-]{11}", video):
            return "track", video
        raise EngineError("INVALID_LINK", "ID video tidak valid.")
    identity = query.get("list", [""])[0]
    if not re.fullmatch(r"[A-Za-z0-9_-]{10,200}", identity):
        raise EngineError("INVALID_LINK", "Tautan tidak memuat ID lagu atau playlist yang valid.")
    if identity.startswith(("RD", "UL", "WL", "LL")):
        raise EngineError("DYNAMIC_PLAYLIST", "Gunakan playlist tetap; radio/mix dan daftar privat khusus tidak didukung.")
    return "playlist", identity


def spotify_playlist(value):
    if value.startswith("spotify:playlist:"):
        identity = value.split(":")[-1]
    else:
        parsed = urlsplit(value)
        if parsed.hostname != "open.spotify.com":
            return None
        match = re.fullmatch(r"/(?:intl-[^/]+/)?playlist/([A-Za-z0-9]{22})/?", parsed.path)
        if not match:
            raise EngineError("INVALID_LINK", "Gunakan tautan playlist Spotify yang valid.")
        identity = match[1]
    if not re.fullmatch(r"[A-Za-z0-9]{22}", identity):
        raise EngineError("INVALID_LINK", "ID playlist Spotify harus 22 karakter.")
    return identity


def bounded(content):
    content = str(content).lstrip("\ufeff")
    if len(content.encode("utf-8")) > MAX_INPUT_BYTES:
        raise EngineError("INPUT_TOO_LARGE", "Batas berkas/teks adalah 2 MiB.")
    return content


def entry(raw):
    line = re.sub(r"^(?:\d+[.)]|[-*+•])\s+", "", raw.strip()).replace("**", "")
    link = re.fullmatch(r"\[[^\]]+\]\((https://[^\s)]+)\)", line)
    if link:
        line = link[1]
    if line.startswith(("https://", "http://", "spotify:")):
        return {"url": line}
    parts = re.split(r"\s+(?:-|–|—|\|)\s+", line, maxsplit=1)
    song = Song(parts[1], [a.strip() for a in parts[0].split(";") if a.strip()]) if len(parts) == 2 else Song(line)
    return {"song": song.to_dict()}


def _json_song(row):
    if not isinstance(row, dict):
        raise EngineError("INVALID_ENTRY", "Baris JSON harus berupa objek.")
    if "url" in row and not (row.get("title") or row.get("name")):
        return {"url": str(row["url"])}
    artists = row.get("artists") or row.get("artist") or []
    if isinstance(artists, str):
        artists = [a.strip() for a in artists.split(";")]
    elif isinstance(artists, list):
        artists = [a.get("name", "") if isinstance(a, dict) else a for a in artists]
    album = row.get("album") or row.get("album_name") or ""
    if isinstance(album, dict):
        artwork = (album.get("images") or [{}])[0].get("url", "")
        album_name, date = album.get("name", ""), album.get("release_date", "")
    else:
        artwork, album_name, date = "", str(album), row.get("date", "")
    duration = seconds(row.get("duration") or row.get("duration_seconds"))
    if not duration and row.get("duration_ms"):
        duration = seconds(row["duration_ms"]) / 1000
    sid = row.get("sid") or row.get("song_id") or row.get("id", "")
    uri = row.get("Track URI") or row.get("spotify_url") or row.get("url", "")
    if not sid and uri:
        match = re.search(r"(?:track/|track:)([A-Za-z0-9]{22})", uri)
        sid = match[1] if match else ""
    song = Song(row.get("title") or row.get("name", ""), artists, duration,
                sid=sid, isrc=row.get("isrc") or (row.get("external_ids") or {}).get("isrc", ""),
                album=album_name, date=date, explicit=row.get("explicit"),
                artwork_url=row.get("artwork_url") or row.get("cover_url") or artwork,
                youtube_id=row.get("youtube_id", ""), source_url=row.get("source_url", ""))
    return {"song": song.to_dict()}


def parse(content, name="Playlist", kind="text"):
    content = bounded(content)
    rows = []
    def add(raw, build):
        if len(rows) >= MAX_ENTRIES:
            raise EngineError("TOO_MANY_ENTRIES", f"Batas {MAX_ENTRIES} entri; daftar tidak dipotong diam-diam.")
        try:
            rows.append({"input": str(raw)[:2000], **build()})
        except (EngineError, TypeError, ValueError, AttributeError) as error:
            rows.append({"input": str(raw)[:2000], "error": str(error)[:400], "code": "INVALID_ENTRY"})
    if kind in {"json", "spotdl"}:
        try:
            data = json.loads(content)
        except ValueError as error:
            raise EngineError("INVALID_JSON", "JSON tidak valid.") from error
        if isinstance(data, dict):
            data = data.get("tracks", data.get("songs"))
        if not isinstance(data, list):
            raise EngineError("INVALID_JSON", "JSON harus berupa array atau objek tracks/songs.")
        for row in data:
            add(row, lambda row=row: _json_song(row))
    elif kind == "csv":
        try:
            dialect = csv.Sniffer().sniff(content[:4096], delimiters=",;\t")
        except csv.Error:
            dialect = csv.excel
        for row in csv.DictReader(io.StringIO(content), dialect=dialect):
            fields = {norm(k): v for k, v in row.items() if k}
            def get(*names):
                return next((fields[norm(n)] for n in names if fields.get(norm(n))), "")
            data = {"title": get("title", "Track Name", "name"), "artists": get("artists", "artist", "Artist Name(s)", "Artist Name"),
                    "duration": get("duration", "duration_seconds", "Duration (s)"),
                    "duration_ms": get("duration_ms", "Duration (ms)", "Track Duration (ms)"),
                    "Track URI": get("Track URI", "Track URL"), "sid": get("sid", "spotify_id"),
                    "isrc": get("ISRC"), "album": get("Album Name", "album"),
                    "artwork_url": get("Artwork URL", "Cover URL", "artwork_url")}
            add(row, lambda data=data: _json_song(data))
    else:
        for raw in content.splitlines():
            line = raw.strip()
            if not line or line.startswith(("#", "```")) or (line.endswith(":") and not line.startswith("https:")):
                continue
            if kind in {"m3u", "m3u8"} and not line.startswith(("https://", "spotify:")):
                # SAF relative paths cannot be interpreted as arbitrary filesystem paths.
                add(line, lambda line=line: {"local_path": line})
            else:
                add(line, lambda line=line: entry(line))
    if not rows:
        raise EngineError("EMPTY_INPUT", "Daftar kosong. Gunakan Artis - Judul, satu lagu per baris.")
    return {"name": name or "Playlist", "entries": rows, "count": len(rows), "completeness": "complete"}
