"""Durable review choices and predictable destinations; no filesystem/network work."""
from datetime import datetime
from .model import EngineError, safe_name


def suggested_name(data, entries):
    supplied = str(data.get("name") or "").strip()
    if supplied and supplied.casefold() not in {"playlist", "playlist saya", "musik"}:
        return supplied[:100]
    songs = [r.get("song", {}) for r in entries if r.get("song")]
    if len(entries) == 1 and songs:
        song = songs[0]
        return ((song.get("artists") or [""])[0] + " — " + song.get("title", "Musik")).strip(" —")[:100]
    artists = {(s.get("artists") or [""])[0].strip() for s in songs}
    if len(songs) == len(entries) and len(artists) == 1 and next(iter(artists), ""):
        return ("Koleksi " + next(iter(artists)))[:100]
    return "Pilihan Musik · " + datetime.now().strftime("%d %b %Y")


def update_review(plan, payload):
    rows = plan["entries"]
    ids = payload.get("entry_ids", plan.get("selected_ids", [r["id"] for r in rows]))
    if not isinstance(ids, list) or not all(isinstance(i, str) for i in ids) or not set(ids).issubset({r["id"] for r in rows}):
        raise EngineError("INVALID_SELECTION", "Pilihan lagu tidak termasuk daftar ini.")
    name = str(payload.get("name") or plan["name"]).strip()[:100]
    create = payload.get("create_playlist", plan.get("create_playlist", True))
    if not isinstance(create, bool):
        raise EngineError("INVALID_SELECTION", "Pilihan playlist harus boolean.")
    # Publication fixes the physical layout; retry/renaming never moves existing files.
    locked = plan.get("layout_locked", False)
    if locked and create != plan.get("create_playlist", True):
        raise EngineError("REVIEW_LOCKED", "Pengaturan playlist sudah dipakai. Buat pemeriksaan baru untuk mengubahnya.")
    plan.update(name=name, create_playlist=create, selected_ids=list(dict.fromkeys(ids)))
    if not locked:
        plan["folder"] = safe_name(name) if create and len(set(ids)) > 1 else ""
    return plan
