"""Anonymous music discovery; never resolves audio, downloads files, or stores signed URLs."""
from __future__ import annotations
import json
import re
from urllib.parse import urlsplit
from yono_core.catalogue import Catalogue
from yono_core.control import Control, Stopped
from yono_core.model import norm, seconds
from yono_core.network import session
from yono_search import clean, video_id

PAGE = 12
VIDEO = re.compile(r"[A-Za-z0-9_-]{11}\Z")
ARTIST = re.compile(r"UC[A-Za-z0-9_-]{22}\Z")
ART_HOSTS = {"i.ytimg.com", "img.youtube.com", "lh3.googleusercontent.com"}
# These describe alternate recordings, not proof of who recorded a song first.
ALTERNATES = {"cover", "karaoke", "remix", "remixed", "nightcore", "slowed", "sped", "reverb", "live"}
COMPILATION = re.compile(r"\b(?:full album|nonstop|non stop|kumpulan lagu|best of|dj mix|megamix|mashup)\b", re.I)


class MusicCatalogue(Catalogue):
    @property
    def client(self):
        if self._client is None:
            from ytmusicapi import YTMusic
            self._session = session(self.control, {"music.youtube.com", "www.youtube.com", "youtube.com"})
            self._client = YTMusic(requests_session=self._session, language="en", location="ID")
        return self._client


def artwork(images):
    valid = []
    for image in images if isinstance(images, list) else []:
        try:
            url = image.get("url", "")
            p = urlsplit(url)
            if (len(url) <= 4096 and p.scheme == "https" and p.hostname in ART_HOSTS
                    and not p.username and not p.password and p.port in (None, 443) and not p.fragment):
                valid.append((int(image.get("width") or 0), url))
        except (ValueError, TypeError, AttributeError):
            pass
    if not valid:
        return ""
    # Prefer a supplied medium thumbnail over a large original; never invent an image URL.
    return min(valid, key=lambda image: (0 if 256 <= image[0] <= 768 else 1, abs(image[0] - 512)))[1]


def song(row, query="", *, videos=False):
    if not isinstance(row, dict):
        return None
    ident, title = row.get("videoId", ""), clean(row.get("title"))
    artists = [clean(a.get("name")) for a in (row.get("artists") or []) if isinstance(a, dict) and clean(a.get("name"))]
    kind = row.get("videoType") or row.get("musicVideoType")
    if (not isinstance(ident, str) or not VIDEO.fullmatch(ident) or not title or not artists
            or row.get("isAvailable") is False or row.get("isLive") or row.get("isLiveContent")
            or row.get("resultType") in {"video", "episode", "podcast"} and not videos):
        return None
    if kind and kind not in ({"MUSIC_VIDEO_TYPE_ATV", "MUSIC_VIDEO_TYPE_OMV"} if videos else {"MUSIC_VIDEO_TYPE_ATV"}):
        return None
    tokens, requested = set(norm(title).split()), set(norm(query).split())
    # A word in an ordinary song title ("Live Forever", "Cover Me In Sunshine")
    # is not sufficient evidence of an alternate recording.
    if not re.search(r"[\[(][^\])]*\bcover\b|\bcover\s+by\b|\bcover$", title, re.I):
        tokens.discard("cover")
    if not re.search(r"[\[(][^\])]*\blive\b|\blive (?:at|from|in|version)\b|[-–—]\s*live\b", title, re.I):
        tokens.discard("live")
    if any(set(norm(name).split()).issubset(requested) for name in artists):
        requested.add("cover")
    if (tokens & ALTERNATES) - requested or COMPILATION.search(title):
        return None
    length = row.get("duration_seconds") or row.get("duration") or row.get("length")
    duration = seconds(length)
    if duration > 30 * 60:  # Single-track discovery, not compilation playback.
        return None
    album = row.get("album") or {}
    return {"video_id": ident, "title": title, "artists": artists,
            "album": clean(album.get("name")) if isinstance(album, dict) else "",
            "duration_ms": int(duration * 1000),
            "artwork": artwork(row.get("thumbnails") or row.get("thumbnail")),
            "page": "https://www.youtube.com/watch?v=" + ident}


def artist(row):
    if not isinstance(row, dict):
        return None
    ident = row.get("browseId") or row.get("id") or ""
    title = clean(row.get("artist") or row.get("title") or row.get("name"))
    if not isinstance(ident, str) or not ARTIST.fullmatch(ident) or not title:
        return None
    return {"id": ident, "name": title, "artwork": artwork(row.get("thumbnails"))}


def unique(rows, key):
    seen = set()
    return [r for r in rows if r and not (r[key] in seen or seen.add(r[key]))]


def run(payload, control, catalogue):
    op = payload.get("operation", "search")
    query = clean(payload.get("query"), 200)
    page = payload.get("page", 0)
    if type(page) is not int or not 0 <= page <= 2:
        raise ValueError("invalid page")
    if op == "search":
        if len(query) < 2:
            raise ValueError("invalid query")
        ident = video_id(query)
        if "://" in query:
            if not ident:
                raise ValueError("invalid link")
            # Explicit links retain the stable video metadata path, never broad text fallback.
            from yono_search import run as search_link
            return search_link({"query": query, "page": 0}, control)
        raw = catalogue.call("search", query, filter="songs", limit=(page + 1) * PAGE + 1)
        songs = unique([song(r, query) for r in raw], "video_id")
        # An explicitly requested performer takes precedence. Otherwise preserve source relevance;
        # uploader names or a word in the title are not proof of the original recording.
        requested = set(norm(query).split())
        songs.sort(key=lambda r: not any(len(norm(a)) >= 3 and set(norm(a).split()).issubset(requested) for a in r["artists"]))
        artists = []
        if page == 0:
            try:
                artists = unique([artist(r) for r in catalogue.call("search", query, filter="artists", limit=5)], "id")
            except Stopped:
                raise
            except Exception:
                pass  # An unavailable artist shelf must not hide matching songs.
        return {"ok": True, "songs": songs[page * PAGE:(page + 1) * PAGE], "artists": artists[:5],
                "next": str(page + 1) if len(raw) > (page + 1) * PAGE and page < 2 else ""}
    if op == "artist":
        ident = payload.get("artist_id", "")
        if not isinstance(ident, str) or not ARTIST.fullmatch(ident):
            raise ValueError("invalid artist")
        info = catalogue.call("get_artist", ident)
        section = info.get("songs") or {}
        rows = section.get("results") or []
        if section.get("browseId"):
            rows = catalogue.call("get_playlist", section["browseId"], limit=36).get("tracks") or rows
        # Artist explicitly selected: keep their covers, but not generic mixes or modifications.
        return {"ok": True, "songs": unique([song(r, "cover") for r in rows], "video_id")[:36]}
    if op == "radio":
        ident = payload.get("video_id", "")
        if not isinstance(ident, str) or not VIDEO.fullmatch(ident):
            raise ValueError("invalid seed")
        rows = catalogue.call("get_watch_playlist", videoId=ident, radio=True, limit=20).get("tracks") or []
        return {"ok": True, "songs": unique([song(r) for r in rows if r.get("videoId") != ident], "video_id")[:20]}
    if op in {"popular_songs", "popular_artists"}:
        region = payload.get("region", "ID")
        if region not in {"ID", "ZZ"}:
            raise ValueError("invalid region")
        charts = catalogue.call("get_charts", region)
        if op == "popular_artists":
            return {"ok": True, "artists": unique([artist(r) for r in charts.get("artists", [])], "id")[:12],
                    "songs": [], "source": "YouTube Music · artis populer"}
        playlists = charts.get("videos") or []
        if not playlists:
            return {"ok": True, "songs": [], "source": "YouTube Music · video musik populer"}
        playlist = playlists[0]
        ident = playlist.get("playlistId", "")
        if not isinstance(ident, str) or not re.fullmatch(r"[A-Za-z0-9_-]{10,100}", ident):
            raise ValueError("invalid chart")
        rows = catalogue.call("get_playlist", ident, limit=20).get("tracks") or []
        return {"ok": True, "songs": unique([song(r, videos=True) for r in rows], "video_id")[:12],
                "source": "YouTube Music · " + (clean(playlist.get("title")) or "video musik populer")}
    raise ValueError("invalid operation")


def execute(payload_json, config_json, callback):
    catalogue = None
    try:
        payload, config = json.loads(str(payload_json)), json.loads(str(config_json))
        if not isinstance(payload, dict) or not isinstance(config, dict):
            raise ValueError("invalid request")
        token = Control(str(config["job_id"]), callback, min(20, max(5, int(config.get("timeout_seconds", 18)))))
        token.network_options = config.get("network") or {}
        token.check()
        catalogue = MusicCatalogue(token)
        result = run(payload, token, catalogue)
        token.check()
    except Stopped as error:
        result = {"ok": False, "error": {"message": "Dibatalkan." if error.code == "CANCELLED" else "Katalog terlalu lama merespons. Coba lagi."}}
    except (ValueError, TypeError, KeyError):
        result = {"ok": False, "error": {"message": "Permintaan katalog tidak valid."}}
    except Exception:
        result = {"ok": False, "error": {"message": "Katalog musik belum tersedia. Periksa koneksi dan coba lagi."}}
    finally:
        if catalogue:
            catalogue.close()
    return json.dumps(result, ensure_ascii=False, allow_nan=False)
