"""Portable parts adapted from Spoti Termux 1.2.1; no Android or shell imports."""
from __future__ import annotations

import dataclasses
import math
import re
import unicodedata


class EngineError(Exception):
    def __init__(self, code, message, *, retryable=False, choices=None):
        super().__init__(message)
        self.code, self.retryable, self.choices = code, retryable, choices

    def as_dict(self):
        return {"code": self.code, "message": str(self), "retryable": self.retryable,
                **({"choices": self.choices} if self.choices else {})}


def norm(value):
    value = unicodedata.normalize("NFKD", str(value or "")).casefold()
    return " ".join("".join(c if c.isalnum() else " " for c in value
                            if not unicodedata.combining(c)).split())


def title_key(value):
    value = re.sub(r"[\[(]\s*(?:official\s+)?(?:audio|music video|video|lyrics?)\s*[\])]", "", str(value), flags=re.I)
    value = re.sub(r"[\[(]\s*(?:featuring|feat\.?|ft\.?)\s+[^\])]+[\])]", "", value, flags=re.I)
    return norm(value)


def artist_keys(names):
    return {norm(p) for name in names for p in re.split(
        r"\s+(?:featuring|feat\.?|ft\.?)\s+", str(name), flags=re.I) if norm(p)}


def primary_artist(name):
    return norm(re.split(r"\s+(?:featuring|feat\.?|ft\.?)\s+", name, flags=re.I)[0])


VARIANTS = {"live", "acoustic", "remix", "remixed", "instrumental", "karaoke", "cover",
            "sped", "slowed", "nightcore", "remaster", "remastered", "demo", "edit",
            "rerecorded", "version", "mono", "stereo", "clean", "explicit"}


def variants(value):
    return set(norm(value).split()) & VARIANTS


def seconds(value):
    try:
        result = 0.0
        for part in str(value).split(":"):
            result = result * 60 + float(part)
        return result if math.isfinite(result) and result > 0 else 0.0
    except (ValueError, TypeError):
        return 0.0


def duration_matches(a, b, tolerance=None):
    a, b = seconds(a), seconds(b)
    return a > 0 and b > 0 and abs(a - b) <= (min(6, max(2.5, a * .015)) if tolerance is None else tolerance)


def safe_name(value, max_bytes=150):
    value = re.sub(r'[\x00-\x1f\x7f/\\:*?"<>|]', "_", unicodedata.normalize("NFC", str(value)))
    value = value.encode("utf-8")[:max_bytes].decode("utf-8", "ignore").strip(" .")
    return value if value not in {"", ".", ".."} else "Musik"


def thumbnail(images):
    images = [i for i in (images or []) if isinstance(i, dict) and isinstance(i.get("url"), str)]
    return max(images, key=lambda i: (i.get("width") or 0) * (i.get("height") or 0)).get("url", "") if images else ""


@dataclasses.dataclass
class Song:
    title: str
    artists: list[str] = dataclasses.field(default_factory=list)
    duration: float = 0
    sid: str = ""
    isrc: str = ""
    album: str = ""
    date: str = ""
    explicit: bool | None = None
    artwork_url: str = ""
    youtube_id: str = ""
    source_url: str = ""
    direct_audio: bool = False
    metadata_origin: str = "source"

    def __post_init__(self):
        if not isinstance(self.direct_audio, bool):
            raise EngineError("INVALID_ENTRY", "direct_audio harus boolean.")
        self.title = str(self.title or "").strip()
        if not self.title or len(self.title) > 2000:
            raise EngineError("INVALID_ENTRY", "Judul kosong atau terlalu panjang.")
        if not isinstance(self.artists, list):
            raise EngineError("INVALID_ENTRY", "Artis harus berupa daftar.")
        self.artists = [str(a).strip() for a in self.artists if str(a).strip()]
        self.duration = seconds(self.duration)
        self.sid = str(self.sid or "")
        if self.sid and not re.fullmatch(r"[A-Za-z0-9]{22}", self.sid):
            raise EngineError("INVALID_ENTRY", "ID metadata Spotify tidak valid.")
        self.isrc = re.sub(r"[\s-]", "", str(self.isrc or "")).upper()
        if self.isrc and not re.fullmatch(r"[A-Z]{2}[A-Z0-9]{3}\d{7}", self.isrc):
            raise EngineError("INVALID_ENTRY", "ISRC tidak valid.")
        if self.youtube_id and not re.fullmatch(r"[A-Za-z0-9_-]{11}", str(self.youtube_id)):
            raise EngineError("INVALID_ENTRY", "ID sumber audio tidak valid.")
        if self.explicit is not None and not isinstance(self.explicit, bool):
            raise EngineError("INVALID_ENTRY", "explicit harus boolean atau null.")

    @classmethod
    def from_dict(cls, value):
        if not isinstance(value, dict):
            raise EngineError("INVALID_ENTRY", "Entri harus berupa objek.")
        return cls(**{f.name: value[f.name] for f in dataclasses.fields(cls) if f.name in value})

    def to_dict(self):
        return dataclasses.asdict(self)

    @property
    def label(self):
        return f"{'; '.join(self.artists)} — {self.title}" if self.artists else self.title


def candidate_song(row):
    album = row.get("album") or {}
    return Song(row.get("title", ""), [a["name"] for a in row.get("artists", []) if a.get("name")],
                row.get("duration_seconds") or row.get("duration") or row.get("length"),
                album=album.get("name", "") if isinstance(album, dict) else str(album),
                explicit=row.get("isExplicit"), artwork_url=thumbnail(row.get("thumbnails")),
                youtube_id=row.get("videoId", ""))


def candidate_rejection(song, row):
    if row.get("resultType", "song") != "song" or row.get("isAvailable") is False:
        return "bukan lagu katalog yang tersedia"
    if not re.fullmatch(r"[A-Za-z0-9_-]{11}", str(row.get("videoId", ""))):
        return "ID sumber tidak valid"
    if title_key(song.title) != title_key(row.get("title", "")):
        return "judul berbeda"
    if variants(song.title) != variants(row.get("title", "")):
        return "versi rekaman berbeda"
    if artist_keys(song.artists) != artist_keys(a.get("name", "") for a in row.get("artists", [])):
        return "kredit artis berbeda atau tidak lengkap"
    if not duration_matches(song.duration, row.get("duration_seconds") or row.get("duration")):
        return "durasi berbeda"
    if song.explicit is not None and row.get("isExplicit") is not None and song.explicit != row["isExplicit"]:
        return "explicit/clean berbeda"
    return ""


def verification_rejection(song, row, details):
    reason = candidate_rejection(song, row)
    if reason:
        return reason
    video = details.get("videoDetails") or {}
    if video.get("musicVideoType") != "MUSIC_VIDEO_TYPE_ATV":
        return "sumber belum terverifikasi sebagai rilisan audio katalog ATV"
    if video.get("videoId") != row.get("videoId") or video.get("isLiveContent") or video.get("isPrivate"):
        return "identitas berubah, live, atau privat"
    if (details.get("playabilityStatus") or {}).get("status") != "OK":
        return "sumber tidak dapat diputar"
    if title_key(video.get("title", "")) != title_key(song.title):
        return "judul sumber berubah"
    if not duration_matches(song.duration, video.get("lengthSeconds")):
        return "durasi sumber berbeda"
    return ""
