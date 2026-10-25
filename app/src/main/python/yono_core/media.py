"""Original audio transfer, native validation, embedded and companion artwork."""
from __future__ import annotations
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
from .model import EngineError, Song, duration_matches
from .network import MAX_AUDIO, fetch_picture, youtube_dl

MIMES = {"mp3": "audio/mpeg", "m4a": "audio/mp4", "flac": "audio/flac", "ogg": "audio/ogg",
         "opus": "audio/ogg", "wav": "audio/wav", "aac": "audio/aac", "webm": "audio/webm", "weba": "audio/webm"}


def file_hash(path, control=None):
    result = hashlib.sha256()
    with open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(256 * 1024), b""):
            if control:
                control.check()
            result.update(chunk)
    return result.hexdigest()


def command(argv, control, *, timeout=120):
    """No shell. Stop the child process when the operation is cancelled/times out."""
    import time
    end = time.monotonic() + min(timeout, control.remaining())
    with subprocess.Popen([str(a) for a in argv], stdout=subprocess.PIPE, stderr=subprocess.PIPE) as process:
        try:
            while True:
                control.check()
                if time.monotonic() >= end:
                    raise EngineError("PROCESS_TIMEOUT", "Pemrosesan audio melewati batas waktu.", retryable=True)
                try:
                    stdout, stderr = process.communicate(timeout=.15)
                    if process.returncode:
                        # Do not expose filesystem paths / source URLs from external tool stderr.
                        raise EngineError("AUDIO_PROCESSING", "Alat pemrosesan audio gagal; file tidak dipublikasikan.")
                    return stdout
                except subprocess.TimeoutExpired:
                    continue
        except BaseException:
            if process.poll() is None:
                process.terminate()
                try:
                    process.communicate(timeout=1)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.communicate(timeout=2)
            raise


def probe(path, control, ffprobe="", host=None):
    if ffprobe:
        raw = command([ffprobe, "-v", "error", "-show_format", "-show_streams", "-of", "json", path], control, timeout=30)
        try:
            data = json.loads(raw)
            audio = [s for s in data.get("streams", []) if s.get("codec_type") == "audio"]
            video = [s for s in data.get("streams", []) if s.get("codec_type") == "video" and not (s.get("disposition") or {}).get("attached_pic")]
            return {"duration": float(data.get("format", {}).get("duration") or (audio[0].get("duration") if audio else 0) or 0),
                    "audio": bool(audio), "video": bool(video)}
        except (ValueError, TypeError, IndexError) as error:
            raise EngineError("INVALID_AUDIO", "Durasi/struktur audio tidak dapat diperiksa.") from error
    if host is not None and hasattr(host, "probe"):
        result = host.probe(str(path))
        return json.loads(str(result)) if not isinstance(result, dict) else result
    import mutagen
    audio = mutagen.File(path)
    if audio is None:
        raise EngineError("PROBE_REQUIRED", "Kontainer ini memerlukan pemeriksa audio Android atau FFprobe.")
    return {"duration": audio.info.length, "audio": True, "video": False}


def tag(path, song, picture=None):
    import mutagen
    from mutagen.id3 import APIC, TALB, TDRC, TIT2, TPE1, TSRC, TXXX
    from mutagen.mp3 import MP3
    from mutagen.flac import FLAC, Picture
    from mutagen.mp4 import MP4, MP4Cover
    from mutagen.oggopus import OggOpus
    from mutagen.oggvorbis import OggVorbis
    audio = mutagen.File(path)
    if audio is None:
        return {"tags_embedded": False, "artwork_embedded": False}
    values = {"spotify_track_id": song.sid, "isrc": song.isrc, "youtube_id": song.youtube_id,
              "source_url": f"https://music.youtube.com/watch?v={song.youtube_id}" if song.youtube_id else song.source_url,
              "playlist_source_url": song.source_url,
              "source_verification": "Yono: catalogue ATV + title/full artist credits/version/duration" if song.youtube_id else "Direct HTTPS audio; native audio validation"}
    mime = "image/png" if picture and picture.startswith(b"\x89PNG") else "image/jpeg"
    if isinstance(audio, MP3):
        if audio.tags is None:
            audio.add_tags()
        audio.tags.add(TIT2(encoding=3, text=song.title))
        audio.tags.add(TPE1(encoding=3, text=song.artists))
        audio.tags.add(TALB(encoding=3, text=song.album))
        if song.date:
            audio.tags.add(TDRC(encoding=3, text=song.date))
        if song.isrc:
            audio.tags.add(TSRC(encoding=3, text=song.isrc))
        for key, value in values.items():
            if value:
                audio.tags.add(TXXX(encoding=3, desc=key, text=value))
        if picture:
            audio.tags.delall("APIC")
            audio.tags.add(APIC(encoding=3, mime=mime, type=3, desc="Front cover", data=picture))
    elif isinstance(audio, (FLAC, OggOpus, OggVorbis)):
        audio["title"], audio["artist"], audio["album"] = [song.title], song.artists, [song.album]
        if song.date:
            audio["date"] = [song.date]
        for key, value in values.items():
            if value:
                audio[key] = [value]
        if picture:
            item = Picture()
            item.data, item.mime, item.type = picture, mime, 3
            if isinstance(audio, FLAC):
                audio.clear_pictures()
                audio.add_picture(item)
            else:
                audio["metadata_block_picture"] = [base64.b64encode(item.write()).decode("ascii")]
    elif isinstance(audio, MP4):
        if audio.tags is None:
            audio.add_tags()
        audio["\xa9nam"], audio["\xa9ART"], audio["\xa9alb"] = [song.title], song.artists, [song.album]
        for key, value in values.items():
            if value:
                audio[f"----:com.yonomusic:{key}"] = [value.encode("utf-8")]
        if picture:
            audio["covr"] = [MP4Cover(picture, imageformat=MP4Cover.FORMAT_PNG if mime == "image/png" else MP4Cover.FORMAT_JPEG)]
    else:
        return {"tags_embedded": False, "artwork_embedded": False}
    audio.save()
    return {"tags_embedded": True, "artwork_embedded": bool(picture)}


class Media:
    def __init__(self, control, *, runtime=None, ffmpeg="", ffprobe="", host=None):
        self.control, self.runtime = control, runtime
        self.ffmpeg, self.ffprobe, self.host = ffmpeg, ffprobe, host

    def transfer(self, song, workspace):
        self.control.check()
        if not song.youtube_id and song.source_url:
            import yono_engine
            token = self.control
            class Progress:
                def cancelled(self):
                    token.check()
                    return False
                def progress(self, received, total):
                    token.hook({"status": "downloading", "downloaded_bytes": received, "total_bytes": total})
            qjs = ((self.runtime or {}).get("quickjs") or {}).get("path", "")
            directory = workspace / "direct"
            directory.mkdir(exist_ok=True)
            record = json.loads(yono_engine.transfer(song.source_url, str(directory), False, Progress(), qjs, json.dumps(getattr(token, "network_options", {}))))
            path = Path(record["path"])
            info = probe(path, token, self.ffprobe, self.host)
            import mutagen
            song.artwork_url = song.artwork_url or record.get("metadata", {}).get("artUrl", "")
            embedded = mutagen.File(path, easy=True)
            if embedded is not None:
                if embedded.get("title"):
                    song.title = str(embedded["title"][0])[:512]
                if embedded.get("artist"):
                    song.artists = [str(a)[:512] for a in embedded["artist"]]
                if embedded.get("album"):
                    song.album = str(embedded["album"][0])[:512]
            if not song.duration:
                song.duration = info["duration"]
            return path
        url = f"https://music.youtube.com/watch?v={song.youtube_id}"
        with youtube_dl(self.control, self.runtime, output=workspace) as downloader:
            data = downloader.extract_info(url, download=False)
            if (not isinstance(data, dict) or data.get("id") != song.youtube_id or data.get("is_live")
                    or data.get("has_drm") or data.get("availability") in {"private", "premium_only", "subscriber_only", "needs_auth"}):
                raise EngineError("SOURCE_CHANGED", "Sumber berubah atau memerlukan akses yang tidak tersedia.")
            if len(data.get("requested_formats") or []) > 1:
                raise EngineError("MERGE_REQUIRED", "Sumber tidak menyediakan satu aliran audio mandiri.")
            downloader.process_info(data)
            path = Path(downloader.prepare_filename(data)).resolve()
        if path.parent != workspace.resolve() or not path.is_file() or not 0 < path.stat().st_size <= MAX_AUDIO:
            raise EngineError("INVALID_AUDIO", "Audio lengkap tidak ditemukan dalam ruang kerja.")
        return path

    def artwork(self, song):
        urls = [song.artwork_url]
        if song.youtube_id:
            urls += [f"https://i.ytimg.com/vi/{song.youtube_id}/maxresdefault.jpg", f"https://i.ytimg.com/vi/{song.youtube_id}/hqdefault.jpg"]
        for url in dict.fromkeys(u for u in urls if u):
            self.control.check()
            try:
                return fetch_picture(url, self.control, song.source_url if song.direct_audio else "")
            except Exception:
                self.control.check()
        return None

    def finish(self, source, song, workspace, *, output_format="original", bitrate=192):
        self.control.emit("validating_audio", title=song.title, message="Memeriksa audio lengkap…")
        original = probe(source, self.control, self.ffprobe, self.host)
        if not original.get("audio") or original.get("video") or not duration_matches(song.duration, original.get("duration")):
            raise EngineError("DURATION_MISMATCH", "Kontainer/durasi audio tidak sesuai. Audio tidak disimpan.")
        if output_format != "original":
            raise EngineError("ORIGINAL_ONLY", "Unduhan selalu memakai format audio asli; konversi dinonaktifkan.")
        path = source
        self.control.emit("artwork", title=song.title, message="Menulis informasi dan sampul…")
        picture = self.artwork(song)
        embedded = tag(path, song, picture)
        artwork = {}
        if picture:
            extension = "png" if picture.startswith(b"\x89PNG") else "jpg"
            cover = workspace / ("cover." + extension)
            cover.write_bytes(picture)
            artwork = {"artwork_path": str(cover), "artwork_sha256": file_hash(cover, self.control),
                       "artwork_extension": extension, "artwork_mime": "image/png" if extension == "png" else "image/jpeg"}
        final = probe(path, self.control, self.ffprobe, self.host)
        if not final.get("audio") or final.get("video") or not duration_matches(song.duration, final.get("duration")):
            raise EngineError("DURATION_MISMATCH", "Validasi audio setelah penulisan tag gagal.")
        extension = path.suffix.lower().lstrip(".")
        if extension not in MIMES:
            raise EngineError("UNSUPPORTED_CONTAINER", "Kontainer keluaran belum didukung.")
        return {"path": str(path), "extension": extension, "mime": MIMES[extension],
                "sha256": file_hash(path, self.control), "size": path.stat().st_size,
                "duration": final["duration"], "artwork_downloaded": bool(picture), **artwork, **embedded}

    def repair(self, source, song, workspace):
        # Stage a safe copy; the user's original is not edited or deleted.
        path = workspace / ("artwork" + source.suffix.lower())
        shutil.copyfile(source, path)
        picture = self.artwork(song)
        if not picture:
            raise EngineError("ARTWORK_UNAVAILABLE", "Sampul yang valid belum ditemukan.", retryable=True)
        result = tag(path, song, picture)
        if not result["artwork_embedded"]:
            raise EngineError("ARTWORK_CONTAINER", "Kontainer belum mendukung sampul tertanam. Gunakan sampul pendamping tanpa mengubah audio.")
        checked = probe(path, self.control, self.ffprobe, self.host)
        if not duration_matches(song.duration, checked["duration"]):
            raise EngineError("DURATION_MISMATCH", "Durasi berubah saat memperbaiki sampul.")
        extension = path.suffix.lstrip(".")
        return {"path": str(path), "extension": extension, "mime": MIMES[extension],
                "sha256": file_hash(path, self.control), "size": path.stat().st_size, **result}
