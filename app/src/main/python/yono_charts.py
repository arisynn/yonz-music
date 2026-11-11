"""Existing anonymous public-web adapter, never an invented official data API or chart."""
import json
import re
from yono_core.control import Control, Stopped
from yono_core.catalogue import Catalogue
from yono_catalog import music_song
from yono_metadata import artwork

YOUTUBE = 'https://artists.youtube.com/charts/tracks'
CATEGORIES = {'daily_videos', 'daily_shorts', 'trending', 'weekly_videos', 'weekly_songs', 'weekly_artists', 'weekly_shorts', 'new_releases', 'genres', 'genre_playlists'}


def position(value):
    return int(value) if isinstance(value, (int, str)) and str(value).isdigit() and 1 <= int(value) <= 1000 else None


def entry(row, video_chart=False):
    song = music_song(row)
    if not song:
        return None
    song['metadata_source'] = 'CHART'
    # Only a source-identified music-video chart supplies this authorization evidence.
    if video_chart:
        song['official_status'] = 'PLATFORM_OFFICIAL'
        if song['content_kind'] == 'UNKNOWN':
            song['content_kind'] = 'VIDEO'
    return {'song': song, 'rank': position(row.get('rank')), 'movement': row.get('trend') if row.get('trend') in {'up', 'down', 'neutral', 'new'} else '',
            'lastWeek': position(row.get('lastWeek')), 'peak': position(row.get('peak')), 'label': ''}


def run(payload, catalogue):
    category, country = payload.get('category'), payload.get('country', 'ID')
    if category not in CATEGORIES or not re.fullmatch(r'[A-Z]{2}', str(country)):
        raise ValueError('invalid chart selection')
    result = {'entries': [], 'items': [], 'countries': [], 'source': 'YouTube Music · public web (ytmusicapi)',
              'sourceUrl': YOUTUBE, 'sourceUpdated': '', 'status': 'unavailable', 'country': country, 'category': category,
              'message': 'Kategori ini belum tersedia tanpa akun. Buka chart sumber untuk melihat daftar resminya.'}
    if category == 'genre_playlists':
        params = payload.get('params', '')
        if not isinstance(params, str) or not 1 <= len(params) <= 500:
            raise ValueError('invalid genre')
        rows = catalogue.call('get_mood_playlists', params)
        result['items'] = [{'id': 'yt:playlist:' + r['playlistId'], 'kind': 'playlist', 'title': r.get('title', ''),
                            'credit': '', 'artwork': artwork(r.get('thumbnails')), 'provider': 'YouTube Music Genre'}
                           for r in rows[:100] if re.fullmatch(r'[A-Za-z0-9_-]{10,200}', str(r.get('playlistId', '')))]
        result['status'] = 'available' if result['items'] else 'unavailable'
        return result
    if category in {'trending', 'new_releases', 'genres'}:
        data = catalogue.call('get_explore')
        # Explore currently has no region argument. Never label its results as Indonesia-specific charts.
        result['source'] = 'YouTube Music Explore · wilayah sumber'
        result['country'] = ''
        if category == 'trending':
            rows = (data.get('trending') or {}).get('items', [])
            result['entries'] = [e for r in rows[:100] if (e := entry(r))]
        elif category == 'new_releases':
            result['items'] = [{'id': 'yt:album:' + r['browseId'], 'kind': 'album', 'title': r.get('title', ''),
                                'credit': ', '.join(a.get('name', '') for a in r.get('artists', [])), 'artwork': artwork(r.get('thumbnails')), 'provider': 'YouTube Music · rilisan katalog'}
                               for r in (data.get('new_releases') or [])[:100] if re.fullmatch(r'[A-Za-z0-9_-]{2,200}', str(r.get('browseId', '')))]
        else:
            # Return source-provided genre navigation, not genre chart ranks.
            categories = catalogue.call('get_mood_categories')
            result['genres'] = [{'title': str(r.get('title', ''))[:250], 'params': str(r.get('params', ''))[:500]}
                                for r in categories.get('Genres', [])[:100] if r.get('title') and r.get('params')]
        result['status'] = 'available' if result['entries'] or result['items'] or result.get('genres') else 'unavailable'
        return result
    data = catalogue.call('get_charts', country=country)
    result['countries'] = [c for c in (data.get('countries') or {}).get('options', []) if isinstance(c, str) and re.fullmatch(r'[A-Z]{2}', c)]
    if category == 'weekly_artists':
        rows = data.get('artists') or []
        if isinstance(rows, dict):
            rows = rows.get('items', [])
        result['items'] = [{'id': 'yt:artist:' + r['browseId'], 'kind': 'artist', 'title': r.get('title', ''), 'credit': '',
                            'artwork': artwork(r.get('thumbnails')), 'provider': 'YouTube Music Charts',
                            'rank': position(r.get('rank')), 'movement': r.get('trend') if r.get('trend') in {'up', 'down', 'neutral', 'new'} else ''}
                           for r in rows[:100] if re.fullmatch(r'[A-Za-z0-9_-]{2,200}', str(r.get('browseId', '')))]
    else:
        expected = {'daily_videos': 'Daily Top Music Videos', 'daily_shorts': 'Daily Top Songs on Shorts',
                    'weekly_videos': 'Weekly Top Music Videos', 'weekly_songs': 'Weekly Top Songs', 'weekly_shorts': 'Weekly Top Songs on Shorts'}[category]
        playlists = [p for name in ('videos', 'daily', 'weekly') for p in (data.get(name) or []) if isinstance(p, dict)]
        # No guessing category/period from carousel position or picking the first playlist.
        matching = [p for p in playlists if str(p.get('title', '')).split(' - ')[0].casefold() == expected.casefold()
                    and re.fullmatch(r'[A-Za-z0-9_-]{10,200}', str(p.get('playlistId', '')))]
        if len(matching) == 1:
            playlist = catalogue.call('get_playlist', matching[0]['playlistId'], limit=100)
            result['entries'] = [e for r in (playlist.get('tracks') or [])[:100] if (e := entry(r, category in {'daily_videos', 'weekly_videos'}))]
            # Source order is preserved, but absent numeric ranks/update dates stay absent.
    if result['entries'] or result['items']:
        result['status'] = 'available'
        result['message'] = 'Peringkat/tanggal hanya ditampilkan jika tersedia dari sumber. Artis publik dapat tidak berperingkat.'
    return result


def execute(payload_json, config_json, callback):
    catalogue = None
    try:
        payload, config = json.loads(str(payload_json)), json.loads(str(config_json))
        control = Control(config['job_id'], callback, 30)
        control.network_options = config.get('network') or {}
        control.network_timeout = 4
        catalogue = Catalogue(control)
        return json.dumps({'ok': True, **run(payload, catalogue)}, ensure_ascii=False, allow_nan=False)
    except Stopped:
        return json.dumps({'ok': False, 'code': 'CANCELLED'})
    except Exception:
        return json.dumps({'ok': False, 'code': 'CHART_UNAVAILABLE'})
    finally:
        if catalogue:
            catalogue.close()
