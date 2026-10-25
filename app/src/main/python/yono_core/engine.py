"""Durable review -> selected acquisition, without an Android UI dependency."""
from __future__ import annotations
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import time
import uuid
from urllib.parse import parse_qs, urlencode, urlsplit, urlunsplit
from .control import Stopped
from .inputs import MAX_ENTRIES
from .model import EngineError, Song, safe_name


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    try:
        with temporary.open("w", encoding="utf-8") as stream:
            json.dump(value, stream, ensure_ascii=False, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def identity(origin, entries):
    if origin:
        parsed = urlsplit(origin)
        params = parse_qs(parsed.query)
        canonical = urlunsplit((parsed.scheme, parsed.netloc, parsed.path,
                                urlencode({k: params[k][0] for k in ("list", "v") if k in params}), ""))
    else:
        canonical = json.dumps(entries, sort_keys=True, ensure_ascii=False)
    return hashlib.sha256(canonical.encode()).hexdigest()[:24]


class Engine:
    def __init__(self, root, index, sources, catalogue, media, storage, control):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        self.index, self.sources, self.catalogue = index, sources, catalogue
        self.media, self.storage, self.control = media, storage, control

    def save(self, plan):
        self.control.plan_id = plan["id"]
        plan["updated_at"] = int(time.time())
        counts = {}
        for row in plan["entries"]:
            status = row["status"]
            counts[status] = counts.get(status, 0) + 1
        plan["counts"] = counts
        atomic_json(self.root / "plans" / (plan["id"] + ".json"), plan)
        atomic_json(self.root / "last-report.json", {"schema": 1, "plan_id": plan["id"], "state": plan["state"],
            "total": plan["total"], "counts": counts, "updated_at": plan["updated_at"]})

    def load_plan(self, plan_id):
        if not re.fullmatch(r"[a-f0-9]{32}", str(plan_id)):
            raise EngineError("INVALID_PLAN", "ID rencana tidak valid.")
        try:
            return json.loads((self.root / "plans" / (plan_id + ".json")).read_text(encoding="utf-8"))
        except (OSError, ValueError) as error:
            raise EngineError("PLAN_MISSING", "Rencana impor tidak ditemukan.") from error

    def _flatten(self, data, depth=0):
        if depth > 3:
            raise EngineError("NESTED_INPUT", "Tautan bersarang terlalu dalam.")
        result = []
        for row in data["entries"]:
            self.control.check()
            if "url" in row:
                try:
                    nested = self.sources.load({"kind": "url", "url": row["url"]})
                    result.extend(self._flatten(nested, depth + 1))
                except EngineError as error:
                    result.append({"input": row["url"], "error": str(error), "code": error.code})
            else:
                result.append(row)
            if len(result) > MAX_ENTRIES:
                raise EngineError("TOO_MANY_ENTRIES", "Jumlah gabungan melebihi batas; tidak dipotong diam-diam.")
        return result

    def prepare(self, request):
        self.control.emit("reading_source", message="Membaca masukan…")
        data = self.sources.load(request)
        flattened = self._flatten(data)
        if not flattened:
            raise EngineError("EMPTY_INPUT", "Playlist tidak memuat lagu.")
        plan = {"schema": 1, "engine": "Yono Engine", "id": uuid.uuid4().hex,
                "name": str(request.get("name") or data["name"])[:512], "state": "preparing",
                "origin": data.get("origin", ""), "completeness": data.get("completeness", "complete"),
                "total": len(flattened), "entries": []}
        plan["collection_id"] = identity(plan["origin"], flattened)
        plan["folder"] = safe_name(plan["name"])
        plan["entries"] = [{"id": uuid.uuid4().hex, "position": i, "status": "pending", "request": row}
                           for i, row in enumerate(flattened, 1)]
        self.save(plan)
        self.control.emit("playlist_ready", completed=0, total=plan["total"], name=plan["name"], plan_id=plan["id"], message="Daftar lengkap siap diperiksa.")
        seen, network_failures = {}, 0
        try:
            for row in plan["entries"]:
                self.control.check()
                original = row["request"]
                self.control.emit("matching", completed=row["position"] - 1, total=plan["total"], input=original.get("input", ""))
                try:
                    if original.get("error"):
                        raise EngineError(original.get("code", "INVALID_ENTRY"), original["error"])
                    if original.get("local_path"):
                        record = self.index.relative(original["local_path"])
                        if not record:
                            raise EngineError("LOCAL_NOT_FOUND", "Jalur M3U belum cocok dengan berkas di folder pilihan.")
                        row.update(status="exists", song=Song.from_dict(record).to_dict(), uri=record["uri"], reason="jalur relatif folder pilihan")
                    else:
                        song = Song.from_dict(original["song"])
                        existing, reason = self.index.find(song)
                        if existing:
                            row.update(status="exists", song=Song.from_dict(existing).to_dict(), uri=existing["uri"], reason=reason)
                            self.index.bind(song, existing)
                        else:
                            song, candidate = self.catalogue.resolve(song)
                            existing, reason = self.index.find(song)
                            if existing:
                                self.index.bind(song, existing)
                                row.update(status="exists", song=song.to_dict(), uri=existing["uri"], reason=reason)
                            elif (song.youtube_id or song.source_url) in seen:
                                row.update(status="duplicate", song=song.to_dict(), duplicate_of=seen[song.youtube_id or song.source_url], reason="rilisan yang sama sudah ada pada daftar")
                            else:
                                row.update(status="ready", song=song.to_dict(), candidate=candidate)
                                seen[song.youtube_id or song.source_url] = row["id"]
                    network_failures = 0
                except EngineError as error:
                    status = "ambiguous" if error.code == "AMBIGUOUS" else "unmatched" if error.code == "NO_OFFICIAL_MATCH" else "invalid" if error.code in {"INVALID_ENTRY", "UNAVAILABLE"} else "failed"
                    row.update(status=status, error=error.as_dict())
                    network_failures = network_failures + 1 if error.retryable else 0
                self.save(plan)
                self.control.emit("item_result", completed=row["position"], total=plan["total"], entry=row)
                if network_failures >= 3:
                    plan["state"] = "paused_network"
                    break
            else:
                plan["state"] = "review"
        except Stopped as stop:
            plan["state"] = stop.code.lower()
            raise
        finally:
            self.save(plan)
        self.control.emit("review", total=plan["total"], counts=plan["counts"], plan_id=plan["id"])
        return plan

    def acquire(self, plan_id, selected, *, output_format="mp3", bitrate=192):
        plan = self.load_plan(plan_id)
        selected = set(selected)
        known = {r["id"] for r in plan["entries"]}
        if not selected.issubset(known):
            raise EngineError("INVALID_SELECTION", "Pilihan lagu tidak termasuk rencana ini.")
        if output_format == "mp3" and not getattr(self.media, "ffmpeg", ""):
            raise EngineError("MP3_RUNTIME_REQUIRED", "Runtime MP3 belum tersedia. Tidak ada audio diunduh; pilih format asli atau lengkapi build FFmpeg.")
        plan["state"] = "downloading"
        self.save(plan)
        done = 0
        consecutive_failures = 0
        try:
            for row in plan["entries"]:
                self.control.check()
                if row["id"] not in selected or row["status"] not in {"ready", "failed", "saving"} or "song" not in row:
                    continue
                song = Song.from_dict(row["song"])
                self.control.emit("track_started", completed=done, total=len(selected), title=song.title, position=row["position"])
                workspace = self.root / "work" / (plan["id"] + "-" + row["id"])
                workspace.mkdir(parents=True, exist_ok=True)
                try:
                    key = f"{plan['id']}-{row['id']}"
                    if row.get("pending_sha256") and hasattr(self.storage, "lookup"):
                        previous = self.storage.lookup(key, row["pending_sha256"])
                        if previous and self.storage.exists(str(previous)):
                            uri = str(previous)
                            self.index.add("download", song, uri, row["pending_sha256"])
                            row.update(status="downloaded", uri=uri, reason="tanda terima penyimpanan dipulihkan")
                            self.save(plan)
                            done += 1
                            self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                            continue
                    existing, reason = self.index.find(song)
                    if existing:
                        row.update(status="exists", uri=existing["uri"], reason=reason)
                    else:
                        # Re-check at actual transfer time; a review is not permanent source verification.
                        verified, candidate = self.catalogue.resolve(song)
                        source = self.media.transfer(verified, workspace)
                        result = self.media.finish(source, verified, workspace, output_format=output_format, bitrate=bitrate)
                        row.update(status="saving", pending_sha256=result["sha256"])
                        self.save(plan)
                        self.control.check()
                        self.control.emit("saving", title=song.title, message="Menyimpan ke folder pilihan…")
                        filename = safe_name(f"{song.artists[0]} - {song.title}") + "." + result["extension"]
                        receipt = self.storage.commit(result["path"], plan["folder"], filename, result["mime"],
                            key, result["sha256"])
                        # Once publication succeeds, persist its receipt even when cancellation arrives now.
                        if not receipt or not self.storage.exists(str(receipt)):
                            raise EngineError("STORAGE_COMMIT", "Penyimpanan tidak mengembalikan berkas yang dapat dibaca.")
                        uri = str(receipt)
                        self.index.add("download", verified, uri, result["sha256"])
                        row.update(status="downloaded", song=verified.to_dict(), uri=uri,
                                   audio={k: v for k, v in result.items() if k != "path"})
                        row.pop("error", None)
                    self.save(plan)
                    done += 1
                    consecutive_failures = 0
                    self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                except EngineError as error:
                    consecutive_failures = consecutive_failures + 1 if error.retryable else 0
                    row.update(status="failed", error=error.as_dict())
                    self.save(plan)
                    done += 1
                    self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                except Exception:
                    self.control.check()
                    consecutive_failures += 1
                    row.update(status="failed", error={"code": "TRANSFER_FAILED", "message": "Unduhan/pemrosesan gagal. Berkas asli tidak diubah.", "retryable": True})
                    self.save(plan)
                    done += 1
                    self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                finally:
                    shutil.rmtree(workspace, ignore_errors=True)
                if consecutive_failures >= 3:
                    plan["state"] = "paused_network"
                    break
            # Repeated entries point to the same physical audio, preserving playlist positions.
            by_id = {r["id"]: r for r in plan["entries"]}
            for row in plan["entries"]:
                if row.get("duplicate_of"):
                    parent = by_id.get(row["duplicate_of"], {})
                    if parent.get("uri"):
                        row["uri"] = parent["uri"]
            if plan["state"] != "paused_network":
                plan["state"] = "complete_with_errors" if any(r["status"] in {"failed", "saving"} for r in plan["entries"] if r["id"] in selected) else "complete"
        except Stopped as stop:
            plan["state"] = stop.code.lower()
            for row in plan["entries"]:
                if row["status"] == "saving":
                    row["status"] = "failed"
                    row["error"] = {"code": "INTERRUPTED_SAVE", "message": "Periksa tanda terima penyimpanan sebelum mengulangi.", "retryable": True}
            raise
        finally:
            self.save(plan)
        self.control.emit("finished", counts=plan["counts"], plan_id=plan["id"], total=plan["total"])
        return plan

    def retry_request(self, plan_id):
        plan = self.load_plan(plan_id)
        entries = [r["request"] for r in plan["entries"] if r["status"] in {"failed", "pending", "unmatched", "ambiguous", "saving"}]
        return {"name": plan["name"], "entries": entries, "count": len(entries), "completeness": "complete", "origin": plan["origin"]}

    def choose(self, plan_id, entry_id, youtube_id):
        plan = self.load_plan(plan_id)
        row = next((r for r in plan["entries"] if r["id"] == entry_id), None)
        choices = (row or {}).get("error", {}).get("choices", [])
        choice = next((c for c in choices if c.get("youtube_id") == youtube_id), None)
        if not row or row["status"] != "ambiguous" or not choice:
            raise EngineError("INVALID_SELECTION", "Rekaman bukan salah satu pilihan terverifikasi untuk entri ini.")
        song, candidate = self.catalogue.resolve(Song.from_dict(choice))
        existing, reason = self.index.find(song)
        if existing:
            self.index.bind(song, existing)
            row.update(status="exists", song=song.to_dict(), uri=existing["uri"], reason=reason)
        else:
            same = next((r for r in plan["entries"] if r["id"] != entry_id
                         and r.get("song", {}).get("youtube_id") == youtube_id and r["status"] == "ready"), None)
            row.update(status="duplicate" if same else "ready", song=song.to_dict(), candidate=candidate)
            if same:
                row["duplicate_of"] = same["id"]
        row.pop("error", None)
        self.save(plan)
        return plan

    def repair_artwork(self, plan_id, entry_id):
        plan = self.load_plan(plan_id)
        row = next((r for r in plan["entries"] if r["id"] == entry_id and r.get("uri")), None)
        if not row:
            raise EngineError("LOCAL_NOT_FOUND", "Lagu lokal pada rencana tidak ditemukan.")
        song = Song.from_dict(row["song"])
        workspace = self.root / "work" / ("art-" + uuid.uuid4().hex)
        workspace.mkdir(parents=True)
        try:
            # Kotlin returns a file inside this private workspace, never a raw shared-storage path.
            source = Path(str(self.storage.copyToPrivate(row["uri"], str(workspace)))).resolve()
            if source.parent != workspace.resolve() or not source.is_file():
                raise EngineError("STORAGE_READ", "Salinan privat tidak valid.")
            result = self.media.repair(source, song, workspace)
            filename = safe_name(f"{song.artists[0]} - {song.title}") + "." + result["extension"]
            uri = str(self.storage.commit(result["path"], "Sampul diperbaiki", filename, result["mime"],
                "art-" + uuid.uuid4().hex, result["sha256"]))
            self.index.retagged("", song, uri, result["sha256"])
            return {"original_uri": row["uri"], "repaired_uri": uri, "original_unchanged": True,
                    "audio_downloaded": False, "artwork_embedded": True}
        finally:
            shutil.rmtree(workspace, ignore_errors=True)

    def export_m3u(self, plan_id):
        # content:// URIs work in Yono; provider access for external players is not guaranteed.
        plan = self.load_plan(plan_id)
        lines = ["#EXTM3U"]
        for row in plan["entries"]:
            if row.get("uri") and self.storage.exists(row["uri"]):
                song = Song.from_dict(row["song"])
                lines += [f"#EXTINF:{int(song.duration)},{song.label.replace(chr(10), ' ')}", row["uri"]]
        return "\n".join(lines) + "\n"
