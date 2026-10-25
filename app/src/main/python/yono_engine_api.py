"""JSON ABI for Chaquopy. Kotlin's native media player never needs this module at startup."""
from __future__ import annotations
import importlib.metadata
import json
from pathlib import Path
import shutil
import threading
from yono_core import __version__
from yono_core.catalogue import Catalogue
from yono_core.control import Control, Stopped
from yono_core.engine import Engine
from yono_core.index import LibraryIndex
from yono_core.media import Media, command
from yono_core.model import EngineError
from yono_core.sources import Sources

_LOCK = threading.Lock()
_JOBS = {}
_SERIAL = threading.Lock()


def cancel(job_id):
    with _LOCK:
        token = _JOBS.get(str(job_id))
        if token:
            token.cancel()
    return token is not None


def _json(value):
    return json.dumps(value, ensure_ascii=False, allow_nan=False)


def capabilities(config, token):
    packages = {}
    for name in ("yt-dlp", "yt-dlp-ejs", "ytmusicapi", "requests", "mutagen"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    result = {"schema": 1, "name": "Yono Engine", "version": __version__, "packages": packages,
              "mp3": False, "javascript": False, "offline_library_requires_engine": False}
    ffmpeg = config.get("ffmpeg", "")
    if ffmpeg and Path(ffmpeg).is_file():
        encoders = command([ffmpeg, "-hide_banner", "-encoders"], token, timeout=15).decode("utf-8", "replace")
        result["mp3"] = "libmp3lame" in encoders
    runtime = config.get("runtime") or {}
    if runtime:
        name, options = next(iter(runtime.items()))
        executable = options.get("path", "")
        if executable and Path(executable).is_file():
            code = "console.log(JSON.stringify({answer:Math.imul(6,7)}))"
            output = command([executable, "-e", code], token, timeout=25).decode().strip()
            result["javascript"] = json.loads(output).get("answer") == 42
            result["javascript_runtime"] = name
    result["live_youtube_verified"] = False  # Doctor proves runtime capability, not remote availability.
    return result


def execute(action, payload_json, config_json, callback, storage):
    """One active operation; cancellation is a separate, non-queued entry point.

    config is constructed by Kotlin, never parsed from pasted user input.
    storage implements exists/commit/lookup/probe/copyToPrivate (see ANDROID_INTEGRATION.md).
    """
    try:
        payload, config = json.loads(str(payload_json)), json.loads(str(config_json))
        if not isinstance(payload, dict) or not isinstance(config, dict):
            raise ValueError()
        job_id = str(config["job_id"])
        if not 0 < len(job_id) <= 128:
            raise ValueError()
    except (ValueError, TypeError, KeyError):
        return _json({"ok": False, "error": {"code": "INVALID_REQUEST", "message": "Permintaan mesin tidak valid."}})
    token = Control(job_id, callback, config.get("timeout_seconds", 900))
    if not _SERIAL.acquire(blocking=False):
        return _json({"ok": False, "error": {"code": "BUSY", "message": "Operasi sebelumnya belum selesai."}})
    with _LOCK:
        _JOBS[job_id] = token
    token.network_options = config.get("network") or {}
    index, catalogue = None, None
    try:
        token.check()
        root = Path(config["state_dir"]).resolve()
        root.mkdir(parents=True, exist_ok=True)
        if action == "doctor":
            return _json({"ok": True, "result": capabilities(config, token)})
        index = LibraryIndex(root / "acquisition.sqlite3", storage.exists, getattr(storage, "matches", None))
        if action == "snapshot":
            rows = payload.get("rows", [])
            if len(rows) > 100000:
                raise EngineError("LIBRARY_TOO_LARGE", "Kirim snapshot folder pilihan secara terpisah.")
            index.snapshot(payload["scope"], rows)
            return _json({"ok": True, "result": {"indexed": len(rows), "scope": payload["scope"]}})
        catalogue = Catalogue(token)
        sources = Sources(catalogue, token, spotify_token=str(config.get("spotify_access_token") or ""), runtime=config.get("runtime"))
        media = Media(token, runtime=config.get("runtime"), ffmpeg=config.get("ffmpeg", ""), ffprobe=config.get("ffprobe", ""), host=storage)
        engine = Engine(root, index, sources, catalogue, media, storage, token)
        if action == "prepare":
            result = engine.prepare(payload)
        elif action == "acquire":
            if payload.get("name"):
                from yono_core.model import safe_name
                plan = engine.load_plan(payload["plan_id"])
                plan["name"] = str(payload["name"])[:100]
                plan["folder"] = safe_name(plan["name"])
                engine.save(plan)
            result = engine.acquire(payload["plan_id"], payload["entry_ids"], output_format=payload.get("format", "mp3"), bitrate=payload.get("bitrate", 192))
        elif action == "get_plan":
            result = engine.load_plan(payload["plan_id"])
        elif action == "get_last":
            report = json.loads((root / "last-report.json").read_text(encoding="utf-8"))
            result = engine.load_plan(report["plan_id"])
        elif action == "choose":
            result = engine.choose(payload["plan_id"], payload["entry_id"], payload["youtube_id"])
        elif action == "retry":
            data = engine.retry_request(payload["plan_id"])
            if not data["entries"]:
                raise EngineError("NOTHING_TO_RETRY", "Tidak ada entri gagal/tertunda untuk dicoba ulang.")
            class RetrySources:
                def load(self, request):
                    return data if request.get("kind") == "retry" else sources.load(request)
            engine.sources = RetrySources()
            result = engine.prepare({"kind": "retry", "name": data["name"]})
        elif action == "repair_artwork":
            result = engine.repair_artwork(payload["plan_id"], payload["entry_id"])
        elif action == "export_m3u":
            result = {"content": engine.export_m3u(payload["plan_id"])}
        else:
            raise EngineError("UNKNOWN_ACTION", "Operasi mesin tidak dikenal.")
        token.check()
        return _json({"ok": True, "result": result})
    except Stopped as stop:
        return _json({"ok": False, "plan_id": getattr(token, "plan_id", None), "error": {"code": stop.code, "message": "Dibatalkan." if stop.code == "CANCELLED" else "Batas waktu operasi tercapai.", "retryable": True}})
    except EngineError as error:
        return _json({"ok": False, "error": error.as_dict()})
    except Exception:
        try:
            token.check()
        except Stopped as stop:
            return _json({"ok": False, "error": {"code": stop.code, "message": "Operasi dihentikan.", "retryable": True}})
        return _json({"ok": False, "error": {"code": "ENGINE_FAILURE", "message": "Mesin gagal memproses masukan. Periksa dependensi dan laporan lokal.", "retryable": True}})
    finally:
        if catalogue:
            catalogue.close()
        if index:
            index.close()
        with _LOCK:
            _JOBS.pop(job_id, None)
        _SERIAL.release()
