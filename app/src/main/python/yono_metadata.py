"""Shared conservative normalization. A verified badge/title alone is not release authorization."""
import re
from urllib.parse import urlsplit


def content(row):
    title = str(row.get('title') or '').lower()
    kind = next((kind for pattern, kind in (
        (r'\b(karaoke)\b', 'KARAOKE'), (r'\b(nightcore|sped up|speed up|slowed|slow reverb)\b|slow\s*\+\s*reverb', 'ALTERED'),
        (r'\b(remix|dj mix|dj remix)\b', 'REMIX'), (r'[\[(|\-]\s*cover\b|\bcover\s+by\b|\bcover\s*$', 'COVER'),
        (r'[\[(|\-]\s*live\b|\blive\s+(?:at|from|version|performance|session)\b|\blive\s*$', 'LIVE'), (r'\binstrumental\b', 'INSTRUMENTAL')) if re.search(pattern, title)), '')
    video_type = row.get('musicVideoType') or row.get('videoType')
    official = 'PLATFORM_OFFICIAL' if video_type in {'MUSIC_VIDEO_TYPE_OMV', 'MUSIC_VIDEO_TYPE_ATV'} else 'UNVERIFIED'
    if row.get('was_live') or row.get('live_status') == 'was_live':
        kind = 'LIVE'
    if not kind:
        kind = 'LYRIC' if official != 'UNVERIFIED' and re.search(r'\blyrics?\b', title) else (
            'VIDEO' if video_type == 'MUSIC_VIDEO_TYPE_OMV' else 'AUDIO' if video_type == 'MUSIC_VIDEO_TYPE_ATV' else 'UNKNOWN')
    return {'official_status': official, 'content_kind': kind}


def artwork(images, fallback=''):
    valid = []
    for image in images or []:
        if not isinstance(image, dict):
            continue
        url = image.get('url', '')
        try:
            u = urlsplit(url)
            if (u.scheme == 'https' and u.hostname in {'i.ytimg.com', 'img.youtube.com', 'lh3.googleusercontent.com'}
                    and not u.username and not u.password and u.port in (None, 443) and not u.fragment):
                width = image.get('width') or 0
                valid.append((abs(width - 512) if isinstance(width, (int, float)) and width > 0 else 10000, url[:4096]))
        except (TypeError, ValueError):
            pass
    if not valid and fallback:
        return artwork([{'url': fallback}])
    return min(valid, default=(0, ''))[1]


def extra(row):
    year = row.get('release_year') or str(row.get('release_date') or '')[:4]
    year = int(year) if len(str(year)) == 4 and str(year).isdigit() and 1900 <= int(year) <= 2200 else 0
    return {**content(row), 'uploader': str(row.get('uploader') or row.get('channel') or '')[:250],
            'channel_id': str(row.get('channel_id') or '')[:100], 'album_artist': str(row.get('album_artist') or '')[:250],
            'release_year': year, 'genre': str(row.get('genre') or '')[:250]}
