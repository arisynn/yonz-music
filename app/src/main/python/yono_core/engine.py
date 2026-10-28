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
from .control import Stopped, ItemCancelled
from .inputs import MAX_ENTRIES
from .model import EngineError, Song, safe_name
from .review import suggested_name, update_review


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
                "name": str(request.get("name") or suggested_name(data, flattened))[:100], "state": "preparing",
                "origin": data.get("origin", ""), "completeness": data.get("completeness", "complete"),
                "total": len(flattened), "entries": []}
        plan["collection_id"] = identity(plan["origin"], flattened)
        revision = hashlib.sha256(json.dumps(flattened, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        plan["source_revision"] = revision
        if request.get("resume_existing"):
            # Compare actual enumerated content, not the display name or only a playlist URL.
            candidates = sorted((self.root / "plans").glob("*.json"), key=lambda p: p.stat().st_mtime, reverse=True)[:100]
            for path in candidates:
                try:
                    previous = json.loads(path.read_text(encoding="utf-8"))
                except (OSError, ValueError):
                    continue
                if previous.get("collection_id") == plan["collection_id"] and previous.get("source_revision") == revision:
                    previous["reuse_reason"] = "Daftar yang sama sudah diperiksa. Pilihan sebelumnya dipulihkan; gunakan Coba gagal lagi untuk entri yang belum cocok."
                    return previous
        plan["folder"] = safe_name(plan["name"])
        plan["entries"] = [{"id": uuid.uuid4().hex, "position": i, "status": "pending", "request": row}
                           for i, row in enumerate(flattened, 1)]
        update_review(plan, {"create_playlist": request.get("create_playlist", False)})
        self.save(plan)
        self.control.emit("playlist_ready", completed=0, total=plan["total"], name=plan["name"], plan_id=plan["id"], message="Daftar lengkap siap diperiksa.")
        seen, network_failures = {}, 0
        try:
            for row in plan["entries"]:
                self.control.check()
                original = row["request"]
                self.control.emit("matching", completed=row["position"] - 1, total=plan["total"], input=original.get("input", ""), entry_id=row["id"], plan_id=plan["id"])
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
                        existing, reason = self.index.find(song, exact_source=True) if original.get("selected_exact") else self.index.find(song)
                        if existing:
                            row.update(status="exists", song=song.to_dict() if original.get("selected_exact") else Song.from_dict(existing).to_dict(), uri=existing["uri"], reason=reason)
                            if original.get("selected_exact"):row["audio"]={"sha256":existing.get("sha256","")}
                            self.index.bind(song, existing)
                        else:
                            song, candidate = (song, {"selected_exact": True}) if original.get("selected_exact") else self.catalogue.resolve(song)
                            existing, reason = self.index.find(song, exact_source=True) if original.get("selected_exact") else self.index.find(song)
                            if existing:
                                self.index.bind(song, existing)
                                row.update(status="exists", song=song.to_dict(), uri=existing["uri"], reason=reason)
                                if original.get("selected_exact"):row["audio"]={"sha256":existing.get("sha256","")}
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

    def persist_manifest(self, plan, row, workspace):
        """Portable, hash-bound metadata; contains no Android URI or private signing/config secrets."""
        from urllib.parse import urlsplit, urlunsplit
        song = Song.from_dict(row["song"])
        audio = row.get("audio") or {}
        digest = audio.get("sha256") or row.get("pending_sha256")
        if not digest and hasattr(self.storage, "hash"):
            digest = str(self.storage.hash(row["uri"]))
            row.setdefault("audio", {})["sha256"] = digest
        if not digest:
            return
        def name(uri, fallback):
            return str(self.storage.displayName(uri)) if hasattr(self.storage, "displayName") else fallback
        p = urlsplit(song.source_url)
        source_url = f"https://music.youtube.com/watch?v={song.youtube_id}" if song.youtube_id else urlunsplit((p.scheme, p.netloc, p.path, "", "")) if p.scheme == "https" else ""
        artist = [a for a in song.artists if a.casefold() not in {"unknown artist", "unspecified credits"}]
        meta = {"type": "yono.music.metadata", "schema": 1, "revision": row.setdefault("metadata_revision", int(time.time()*1000)),
            "audio": {"sha256": digest, "size": audio.get("size", 0), "duration_ms": int(song.duration * 1000),
                      "file_name": name(row["uri"], row.get("file_name", ""))},
            "metadata": {"title": song.title, "artists": artist, "album": song.album,
                "source_identity": "youtube:" + song.youtube_id if song.youtube_id else "sha256:" + digest,
                "source_url": source_url, "isrc": song.isrc, "origin": song.metadata_origin if artist else "filename"},
            "artwork": {"file_name": (row.get("cover_directory", "") + name(row["artwork_uri"], row.get("cover_name", ""))) if row.get("artwork_uri") else "",
                        "sha256": audio.get("artwork_sha256", "")}}
        path = workspace / "metadata.yono.json"
        path.write_text(json.dumps(meta, ensure_ascii=False, allow_nan=False, indent=2) + "\n", encoding="utf-8")
        from .media import file_hash
        filename = safe_name(meta["audio"]["file_name"] or song.title) + ".yono.json"
        uri = str(self.storage.commit(str(path), plan["folder"], filename, "application/json",
                  f"{plan['id']}-{row['id']}-metadata", file_hash(path, self.control)))
        if not uri or not self.storage.exists(uri):
            raise EngineError("METADATA_COMMIT", "Audio tersimpan tetapi manifest metadata belum berhasil. Coba ulang tanpa mengunduh audio lagi.", retryable=True)
        row["metadata_uri"] = uri

    def acquire(self, plan_id, selected, *, output_format="original", bitrate=192):
        plan = self.load_plan(plan_id)
        selected = set(selected)
        known = {r["id"] for r in plan["entries"]}
        if not selected.issubset(known):
            raise EngineError("INVALID_SELECTION", "Pilihan lagu tidak termasuk rencana ini.")
        if output_format != "original":
            raise EngineError("ORIGINAL_ONLY", "Unduhan selalu memakai format audio asli; konversi dinonaktifkan.")
        # Old plans retain their layout and playlist behavior. New reviewed plans freeze here.
        if "create_playlist" in plan:
            update_review(plan, {"entry_ids": list(selected)})
        plan["layout_locked"] = True
        accepted = set(plan.get("accepted_ids", [])) | selected
        plan["accepted_ids"] = [r["id"] for r in plan["entries"] if r["id"] in accepted]
        plan["state"] = "downloading"
        self.save(plan)
        done = 0
        consecutive_failures = 0
        try:
            for row in plan["entries"]:
                self.control.current_entry = ""
                self.control.check()
                if row["id"] not in selected or row["status"] not in {"ready", "failed", "saving", "cancelled"} or "song" not in row:
                    continue
                song = Song.from_dict(row["song"])
                self.control.emit("track_started", completed=done, total=len(selected), title=song.title, position=row["position"], entry_id=row["id"], plan_id=plan["id"])
                workspace = self.root / "work" / (plan["id"] + "-" + row["id"])
                workspace.mkdir(parents=True, exist_ok=True)
                self.control.current_entry = row["id"]
                try:
                    try:
                        self.control.check()
                        key = f"{plan['id']}-{row['id']}"
                        if row.get("pending_sha256") and hasattr(self.storage, "lookup"):
                            previous = self.storage.lookup(key, row["pending_sha256"])
                            if previous and self.storage.exists(str(previous)):
                                uri = str(previous)
                                self.index.add("download", song, uri, row["pending_sha256"])
                                row.update(uri=uri, reason="tanda terima penyimpanan dipulihkan")
                                self.persist_manifest(plan, row, workspace)
                                row["status"] = "downloaded"
                                self.save(plan)
                                done += 1
                                self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                                continue
                        existing, reason = self.index.find(song, exact_source=True) if row.get("request", {}).get("selected_exact") else self.index.find(song)
                        if existing:
                            row.update(status="exists", uri=existing["uri"], reason=reason)
                            if row.get("request",{}).get("selected_exact"):row["audio"]={"sha256":existing.get("sha256","")}
                        else:
                            # Explicit selections keep their recording ID; Media.transfer re-checks
                            # that exact source's availability/DRM/identity before fetching bytes.
                            verified, candidate = (song, {"selected_exact": True}) if row.get("request", {}).get("selected_exact") else self.catalogue.resolve(song)
                            source = self.media.transfer(verified, workspace)
                            result = self.media.finish(source, verified, workspace, output_format=output_format, bitrate=bitrate)
                            row.update(status="saving", pending_sha256=result["sha256"], song=verified.to_dict(),
                                       audio={k: v for k, v in result.items() if k not in {"path", "artwork_path"}})
                            self.save(plan)
                            if result.get("artwork_path"):
                                cover_name = safe_name(f"{verified.artists[0] if verified.artists else 'Unknown artist'} - {verified.title}") + ".cover." + result["artwork_extension"]
                                save_cover = getattr(self.storage, "commitCover", self.storage.commit)
                                artwork_uri = str(save_cover(result["artwork_path"], plan["folder"], cover_name,
                                    result["artwork_mime"], key + "-cover", result["artwork_sha256"]))
                                if not artwork_uri or not self.storage.exists(artwork_uri):
                                    raise EngineError("ARTWORK_COMMIT", "Sampul belum tersimpan. Coba ulang; audio asli tidak diubah.", retryable=True)
                                row["artwork_uri"] = artwork_uri
                                row["cover_directory"] = ".cover/" if hasattr(self.storage, "commitCover") else ""
                                row["cover_name"] = cover_name
                                self.save(plan)
                            self.control.check()
                            self.control.emit("saving", title=song.title, message="Menyimpan ke folder pilihan…")
                            filename = safe_name(f"{verified.artists[0] if verified.artists else 'Unknown artist'} - {verified.title}") + "." + result["extension"]
                            receipt = self.storage.commit(result["path"], plan["folder"], filename, result["mime"],
                                key, result["sha256"])
                            # Once publication succeeds, persist its receipt even when cancellation arrives now.
                            if not receipt or not self.storage.exists(str(receipt)):
                                raise EngineError("STORAGE_COMMIT", "Penyimpanan tidak mengembalikan berkas yang dapat dibaca.")
                            uri = str(receipt)
                            self.index.add("download", verified, uri, result["sha256"])
                            row.update(song=verified.to_dict(), uri=uri, file_name=filename,
                                       audio={k: v for k, v in result.items() if k not in {"path", "artwork_path"}})
                            self.save(plan)
                            self.persist_manifest(plan, row, workspace)
                            row["status"] = "downloaded"
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
                except ItemCancelled:
                    row.update(status="cancelled", error={"code": "ITEM_CANCELLED", "message": "Dibatalkan untuk lagu ini.", "retryable": True})
                    self.save(plan)
                    done += 1
                    self.control.emit("item_result", completed=done, total=len(selected), entry=row)
                finally:
                    self.control.current_entry = ""
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
                plan["state"] = "complete_with_errors" if any(r["status"] in {"failed", "saving", "cancelled"} for r in plan["entries"] if r["id"] in selected) else "complete"
        except Stopped as stop:
            self.control.current_entry = ""
            plan["state"] = stop.code.lower()
            for row in plan["entries"]:
                if row["id"] in selected and row["status"] in {"ready", "saving"}:
                    row["status"] = "cancelled" if stop.code == "CANCELLED" else "failed"
                    row["error"] = {"code": stop.code, "message": "Dibatalkan." if stop.code == "CANCELLED" else "Batas waktu tercapai. Coba ulang.", "retryable": True}
            raise
        finally:
            self.save(plan)
        self.control.current_entry = ""
        self.control.emit("finished", counts=plan["counts"], plan_id=plan["id"], total=plan["total"])
        return plan

    def retry_request(self, plan_id):
        plan = self.load_plan(plan_id)
        entries = [r["request"] for r in plan["entries"] if r["status"] in {"failed", "pending", "unmatched", "ambiguous", "saving"}]
        return {"name": plan["name"], "entries": entries, "count": len(entries), "completeness": "complete", "origin": plan["origin"]}

    def retry(self, plan_id):
        """Refresh failed metadata in place: IDs, choices, destination and collection stay stable."""
        plan = self.load_plan(plan_id)
        for row in plan["entries"]:
            self.control.check()
            if row["status"] not in {"failed", "pending", "unmatched", "ambiguous", "saving", "cancelled"}:
                continue
            original = row["request"]
            try:
                if original.get("error"):
                    raise EngineError(original.get("code", "INVALID_ENTRY"), original["error"])
                if original.get("local_path"):
                    record = self.index.relative(original["local_path"])
                    if not record:
                        raise EngineError("LOCAL_NOT_FOUND", "Jalur M3U belum cocok di folder pilihan.")
                    song = Song.from_dict(record)
                    existing, reason = record, "jalur relatif folder pilihan"
                else:
                    song = Song.from_dict(original["song"])
                    exact=original.get("selected_exact")
                    existing, reason = self.index.find(song, exact_source=True) if exact else self.index.find(song)
                    if not existing:
                        if exact:
                            from yono_stream import inspect_video
                            song=Song.from_dict(inspect_video(song.youtube_id,self.control,self.sources.runtime)["song"])
                            candidate={"selected_exact":True}
                        else:
                            song, candidate = self.catalogue.resolve(song)
                        existing, reason = self.index.find(song, exact_source=True) if exact else self.index.find(song)
                        row["candidate"] = candidate
                row.update(song=song.to_dict(), status="exists" if existing else "ready")
                if existing:
                    row.update(uri=existing["uri"], reason=reason)
                    if original.get("selected_exact"):row["audio"]={"sha256":existing.get("sha256","")}
                    self.index.bind(song, existing)
                row.pop("error", None)
            except EngineError as error:
                row.update(status="ambiguous" if error.code == "AMBIGUOUS" else "unmatched" if error.code == "NO_OFFICIAL_MATCH" else "failed", error=error.as_dict())
            finally:
                self.save(plan)
            self.control.emit("item_result", entry=row, completed=row["position"], total=plan["total"])
        plan["state"] = "review"
        self.save(plan)
        return plan

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
            # Existing audio is never fetched, rewritten or copied for cover repair.
            picture = self.media.artwork(song)
            if not picture:
                raise EngineError("ARTWORK_UNAVAILABLE", "Sumber belum menyediakan sampul JPEG/PNG yang dapat diunduh.", retryable=True)
            from .media import file_hash
            extension = "png" if picture.startswith(b"\x89PNG") else "jpg"
            path = workspace / ("cover." + extension)
            path.write_bytes(picture)
            key = f"{plan['id']}-{row['id']}-cover"
            filename = safe_name(f"{song.artists[0] if song.artists else 'Unknown artist'} - {song.title}") + ".cover." + extension
            save_cover = getattr(self.storage, "commitCover", self.storage.commit)
            uri = str(save_cover(str(path), plan["folder"], filename,
                "image/png" if extension == "png" else "image/jpeg", key, file_hash(path, self.control)))
            if not uri or not self.storage.exists(uri):
                raise EngineError("ARTWORK_COMMIT", "Sampul belum tersimpan. Coba lagi.", retryable=True)
            row["artwork_uri"] = uri
            row["cover_directory"] = ".cover/" if hasattr(self.storage, "commitCover") else ""
            row.setdefault("audio", {}).update(artwork_downloaded=True, artwork_sha256=file_hash(path, self.control))
            row["cover_name"] = filename
            row["metadata_revision"] = int(time.time()*1000)
            self.persist_manifest(plan, row, workspace)
            self.save(plan)
            return plan
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
