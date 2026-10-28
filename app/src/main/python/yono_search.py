"""Read-only yt-dlp search. No API key, file download, global patch, or acquisition lock."""
from __future__ import annotations
import json
import math
import re
from urllib.parse import urlsplit, parse_qs
from yono_core.control import Control, Stopped
from yono_core.network import youtube_dl

PAGE = 12
MAX_PAGE = 2
ID = re.compile(r"[A-Za-z0-9_-]{11}\Z")
HOSTS = {"youtube.com", "www.youtube.com", "music.youtube.com", "youtu.be"}


def clean(value, limit=250):
    return " ".join(str(value or "").split())[:limit]


def video_id(value):
    try:
        u = urlsplit(value)
        if u.scheme != "https" or u.hostname not in HOSTS or u.username or u.password or u.port not in (None, 443) or u.fragment:
            return None
        ident = u.path.lstrip("/") if u.hostname == "youtu.be" else (
            u.path[len("/shorts/"):] if u.path.startswith("/shorts/") else parse_qs(u.query).get("v", [""])[0] if u.path == "/watch" else "")
        return ident if ID.fullmatch(ident) else None
    except ValueError:
        return None


def normalize(row):
    ident = row.get("id", "")
    title = clean(row.get("title"))
    if not isinstance(ident, str) or not ID.fullmatch(ident) or not title or row.get("is_live") or row.get("live_status") in {"is_live", "is_upcoming"}:
        return None
    duration = row.get("duration")
    duration_ms = int(duration * 1000) if isinstance(duration, (int, float)) and math.isfinite(duration) and 0 < duration <= 7*86400 else 0
    artists = row.get("artists") or ([row["artist"]] if row.get("artist") else [])
    artists = [clean(a) for a in artists if isinstance(a, str) and clean(a)] if isinstance(artists, list) else []
    channel = clean(row.get("channel") or row.get("uploader"))
    thumbs = row.get("thumbnails") or []
    art = ""
    # Choose an actual supplied thumbnail, never invent an HD image URL.
    for t in reversed(thumbs if isinstance(thumbs, list) else []):
        u = t.get("url", "") if isinstance(t, dict) else ""
        try:
            p = urlsplit(u)
            if p.scheme == "https" and p.hostname in {"i.ytimg.com", "img.youtube.com"} and not p.username and not p.password and p.port in (None, 443):
                art = u[:4096]; break
        except ValueError:
            pass
    return {"id": "engine:" + ident, "title": title, "artists": artists, "channel": channel,
            "album": clean(row.get("album")), "duration_ms": duration_ms, "artwork": art,
            "page": "https://www.youtube.com/watch?v=" + ident, "video_id": ident}


def run(payload, control, factory=youtube_dl):
    query = clean(payload.get("query"), 200)
    page = payload.get("page", 0)
    if not query or len(query) < 3 or type(page) is not int or not 0 <= page <= MAX_PAGE:
        raise ValueError("invalid search")
    ident = video_id(query)
    if "://" in query and not ident:
        return {"ok": False, "error": {"code": "INVALID_LINK", "message": "Tempel tautan video publik yang valid, atau ketik judul dan artis."}}
    target = f"ytsearch5:{ident}" if ident else f"ytsearch{(page+1)*PAGE+1}:{query}"
    with factory(control, flat=True) as dl:
        result = dl.extract_info(target, download=False)
        raw = result.get("entries", [])
        rows, seen, has_more = [], set(), False
        for i, row in enumerate(raw):
            control.check()
            if i > (page+1)*PAGE:
                break
            if i < page*PAGE:
                continue
            if i == (page+1)*PAGE:
                has_more = True; break
            if not isinstance(row, dict):
                continue
            found = normalize(row)
            if found and (not ident or found["video_id"] == ident) and found["id"] not in seen:
                seen.add(found["id"]); rows.append(found)
    control.check()
    return {"ok": True, "songs": rows, "next": str(page+1) if has_more and page < MAX_PAGE and not ident else ""}


def execute(payload_json, config_json, callback):
    try:
        payload, config = json.loads(str(payload_json)), json.loads(str(config_json))
        if not isinstance(payload, dict) or not isinstance(config, dict):
            raise ValueError("invalid request")
        token = Control(str(config["job_id"]), callback, min(20, max(5, int(config.get("timeout_seconds", 18)))))
        token.network_options = config.get("network") or {}
        token.check()
        result = run(payload, token)
    except Stopped as e:
        result = {"ok": False, "error": {"code": e.code, "message": "Dibatalkan." if e.code == "CANCELLED" else "Sumber terlalu lama merespons. Coba lagi."}}
    except (ValueError, TypeError, KeyError):
        result = {"ok": False, "error": {"code": "INVALID_REQUEST", "message": "Pencarian tidak valid."}}
    except Exception:
        # No signed URLs, cookies, raw extractor logs or secrets in UI/history.
        result = {"ok": False, "error": {"code": "SOURCE_NETWORK", "message": "Mesin belum dapat membaca sumber. Periksa koneksi; sumber mungkin membatasi akses otomatis."}}
    return json.dumps(result, ensure_ascii=False, allow_nan=False)
