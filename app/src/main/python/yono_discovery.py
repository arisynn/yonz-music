"""Public catalogue identities and bounded discovery; no signed media URLs are persisted."""
import json
import re
import threading
import time
from urllib.parse import quote
from yono_core.control import Control, Stopped
from yono_core.network import session
from yono_core.model import Song, EngineError, title_key, artist_keys, variants, duration_matches
from yono_core.catalogue import Catalogue
from yono_catalog import music_song

_lock = threading.Lock()
_last_request = 0.0
UUID = re.compile(r'^[a-f0-9-]{36}$')


def credits(row):
    return [a['artist']['name'] for a in row.get('artist-credit', []) if isinstance(a, dict) and isinstance(a.get('artist'), dict)]


def track(row, album='', cover=''):
    recording = row.get('recording', row)
    ident = recording.get('id', '')
    if not UUID.fullmatch(ident):
        return None
    artists = credits(row) or credits(recording)
    # Disambiguation is essential for distinguishing live recordings and remixes.
    title = row.get('title') or recording.get('title', '')
    disambiguation = recording.get('disambiguation', '')
    if disambiguation and any(v in disambiguation.lower() for v in ('live', 'remix', 'acoustic', 'instrumental')) and disambiguation.lower() not in title.lower():
        title += ' (' + disambiguation + ')'
    release = next(iter(recording.get('releases', [])), {})
    release_id = release.get('id', '')
    return {'id': 'mb:recording:' + ident, 'kind': 'song', 'title': title, 'credit': ', '.join(artists),
            'artists': artists, 'duration_ms': row.get('length') or recording.get('length') or 0,
            'album': album or release.get('title', ''), 'artwork': cover or cover_url('release', release_id),
            'isrc': next((i for i in recording.get('isrcs', []) if re.fullmatch(r'[A-Z]{2}[A-Z0-9]{3}\d{7}', i)), ''), 'provider': 'MusicBrainz'}


def cover_url(kind, ident):
    return f'https://coverartarchive.org/{kind}/{ident}/front-250' if UUID.fullmatch(ident) else ''


def entity(row, kind):
    return {'id': 'mb:' + kind + ':' + row['id'], 'kind': kind, 'title': row.get('name') or row.get('title', ''),
            'credit': ', '.join(credits(row)) or row.get('disambiguation', ''),
            'artwork': cover_url('release-group', row['id']) if kind == 'album' else '', 'provider': 'MusicBrainz'}


class MetadataProvider:
    def __init__(self, control):
        self.control = control
        self.client = session(control, {'musicbrainz.org'})
        self.client.headers.update({'User-Agent': 'YonoMusic/2.0 (https://github.com/arisynn/yonz-music)', 'Accept': 'application/json'})

    def get(self, path, **params):
        global _last_request
        with _lock:
            while time.monotonic() - _last_request < 1.05:
                self.control.check()
                time.sleep(.05)
            self.control.check()
            _last_request = time.monotonic()
            response = self.client.get('https://musicbrainz.org/ws/2/' + path, params={'fmt': 'json', **params})
        response.raise_for_status()
        return response.json()

    def search(self, query, kind):
        field, path, key = {'song': ('recording', 'recording/', 'recordings'), 'album': ('releasegroup', 'release-group/', 'release-groups'), 'artist': ('artist', 'artist/', 'artists')}[kind]
        # Quoting prevents user input becoming Lucene operators.
        text = query.replace('\\', ' ').replace('"', ' ')
        data = self.get(path, query=f'{field}:"{text}"', limit=20)
        rows = data.get(key, [])
        if kind == 'song':
            rows.sort(key=lambda r: (title_key(r.get('title', '')) != title_key(query), not bool(r.get('length')), -int(r.get('score', 0))))
        return [item for row in rows if (item := track(row) if kind == 'song' else entity(row, kind))]

    def detail(self, ident):
        _, kind, value = ident.split(':', 2)
        if not UUID.fullmatch(value) or kind not in ('album', 'artist'):
            raise ValueError('invalid catalogue identity')
        if kind == 'artist':
            artist = self.get('artist/' + value)
            albums = self.get('release-group', artist=value, type='album', inc='artist-credits', limit=40).get('release-groups', [])
            recordings = self.get('recording', artist=value, inc='artist-credits+releases+isrcs', limit=20).get('recordings', [])
            return {'item': entity(artist, kind), 'items': [entity(a, 'album') for a in albums], 'songs': [t for r in recordings if (t := track(r))]}
        group = self.get('release-group/' + value, inc='releases+artist-credits')
        releases = [r for r in group.get('releases', []) if r.get('status') in (None, 'Official')]
        releases.sort(key=lambda r: (r.get('date', '9999'), r.get('id', '')))
        if not releases:
            raise EngineError('NO_RELEASE', 'Album ini belum memiliki daftar lagu di katalog.')
        release = self.get('release/' + releases[0]['id'], inc='recordings+artist-credits+isrcs')
        art = cover_url('release', release['id'])
        songs = []
        for medium in sorted(release.get('media', []), key=lambda m: m.get('position', 0)):
            for row in sorted(medium.get('tracks', []), key=lambda r: r.get('position', 0)):
                item = track(row, release['title'], art)
                if item:
                    if not item['credit']:
                        item['artists'] = credits(release)
                        item['credit'] = ', '.join(item['artists'])
                    songs.append(item)
        if len(songs) > 500:
            raise EngineError('TOO_MANY_ENTRIES', 'Album melebihi batas 500 lagu; daftar tidak dipotong diam-diam.')
        return {'item': {**entity(group, kind), 'artwork': art}, 'items': [], 'songs': songs}

    def enrich(self, source):
        artists = source.get('artists') or []
        if not artists or not source.get('duration_ms'):
            return source
        title = source.get('title', '').replace('"', ' ')
        artist = artists[0].replace('"', ' ')
        data = self.get('recording/', query=f'recording:"{title}" AND artist:"{artist}"', limit=10)
        candidates = []
        for row in data.get('recordings', []):
            item = track(row)
            if (item and title_key(item['title']) == title_key(source['title'])
                    and variants(item['title']) == variants(source['title'])
                    and artist_keys(item['artists']) == artist_keys(artists)
                    and duration_matches(source['duration_ms'] / 1000, item['duration_ms'] / 1000)):
                candidates.append(item)
        if len(candidates) == 1:
            item = candidates[0]
            return {**source, 'title': item['title'], 'artists': item['artists'], 'album': item['album'],
                    'artwork': item['artwork'] or source.get('artwork', ''), 'metadata_id': item['id']}
        return source


class ChartsProvider:
    def __init__(self, catalogue):
        self.catalogue = catalogue

    def charts(self, country):
        data = self.catalogue.call('get_charts', country=country if country in ('ID', 'ZZ') else 'ID')
        videos = data.get('videos') or data.get('daily') or data.get('weekly') or {}
        playlist = (videos.get('playlist') or videos.get('playlistId')) if isinstance(videos, dict) else next((v.get('playlistId') for v in videos if isinstance(v, dict) and v.get('playlistId')), None)
        if isinstance(playlist, dict):
            playlist = playlist.get('id') or playlist.get('playlistId')
        songs = []
        if playlist:
            # Read the actual chart playlist, never a search query labelled as trending.
            from yono_core.network import youtube_dl
            with youtube_dl(self.catalogue.control, flat=True) as downloader:
                downloader.params.update({'extract_flat': 'in_playlist', 'skip_download': True, 'playlistend': 20})
                result = downloader.extract_info('https://music.youtube.com/playlist?list=' + playlist, download=False)
                from yono_search import normalize
                songs = [s for r in (result.get('entries') or []) if (s := normalize(r))][:20]
        if not songs:
            rows = videos.get('items', []) if isinstance(videos, dict) else videos if isinstance(videos, list) else []
            songs = [s for r in rows if (s := music_song(r))][:20]
        artists = data.get('artists') or {}
        artists = artists.get('items', []) if isinstance(artists, dict) else artists
        return {'songs': songs, 'items': [{'id': 'yt:artist:' + r['browseId'], 'kind': 'artist', 'title': r.get('title', ''),
                'credit': '', 'artwork': next(iter(reversed(r.get('thumbnails') or [])), {}).get('url', ''), 'provider': 'YouTube Music'}
                for r in artists[:20] if r.get('browseId')], 'source': 'YouTube Music Charts', 'country': country}


class AudioResolver:
    def __init__(self, catalogue):
        self.catalogue = catalogue

    def resolve(self, item):
        request = Song(title=item['title'], artists=item.get('artists') or [a.strip() for a in item.get('credit', '').split(',') if a.strip()],
                       album=item.get('album', ''), duration=item.get('duration_ms', 0) / 1000, isrc=item.get('isrc', ''))
        song, row = self.catalogue.resolve(request)
        result = music_song(row)
        if not result:
            raise EngineError('NO_OFFICIAL_MATCH', 'Rekaman audio yang cocok belum ditemukan.')
        return result


def execute(payload_json, config_json, callback):
    metadata = catalogue = None
    try:
        payload, config = json.loads(str(payload_json)), json.loads(str(config_json))
        control = Control(config['job_id'], callback, min(40, max(5, int(config.get('timeout_seconds', 30)))))
        control.network_options = config.get('network') or {}
        control.network_timeout = 2 if config.get("background") else 12
        operation = payload['operation']
        if operation in ('search', 'detail', 'enrich') and not payload.get('id', '').startswith('yt:') and payload.get('kind') != 'playlist':
            metadata = MetadataProvider(control)
            if operation == 'search':
                result = {'items': metadata.search(payload['query'], payload.get('kind', 'song'))}
            elif operation == 'detail':
                result = metadata.detail(payload['id'])
            else:
                result = {'songs': [metadata.enrich(payload['song'])]}
        else:
            catalogue = Catalogue(control)
            if operation == 'charts':
                result = ChartsProvider(catalogue).charts(payload.get('country', 'ID'))
            elif operation == 'resolve':
                result = {'songs': [AudioResolver(catalogue).resolve(payload['item'])]}
            elif operation == 'search':
                rows = catalogue.call('search', payload['query'], filter='playlists', limit=20)
                result = {'items': [{'id': 'yt:playlist:' + r['browseId'].removeprefix('VL'), 'kind': 'playlist', 'title': r.get('title', ''), 'credit': r.get('author', ''), 'provider': 'YouTube Music'} for r in rows if r.get('browseId')]}
            elif operation == 'detail' and payload['id'].startswith('yt:playlist:'):
                data = catalogue.call('get_playlist', payload['id'].split(':', 2)[2], limit=501)
                count = data.get('trackCount')
                if type(count) is not int or count > 500 or len(data.get('tracks') or []) != count:
                    raise EngineError('INCOMPLETE_PLAYLIST', 'Playlist belum lengkap atau melebihi 500 lagu. Gunakan impor tautan; daftar tidak dipotong diam-diam.')
                result = {'songs': [s for r in data.get('tracks', []) if (s := music_song(r))], 'items': []}
            elif operation == 'detail' and payload['id'].startswith('yt:artist:'):
                data = catalogue.call('get_artist', payload['id'].split(':', 2)[2])
                result = {'songs': [s for r in (data.get('songs') or {}).get('results', []) if (s := music_song(r))],
                          'items': [{'id': 'yt:album:' + r['browseId'], 'kind': 'album', 'title': r.get('title', ''), 'credit': '', 'provider': 'YouTube Music'} for r in (data.get('albums') or {}).get('results', []) if r.get('browseId')]}
            elif operation == 'detail' and payload['id'].startswith('yt:album:'):
                data = catalogue.call('get_album', payload['id'].split(':', 2)[2])
                result = {'songs': [s for r in data.get('tracks', []) if (s := music_song(r))], 'items': []}
            else:
                raise ValueError('unsupported discovery operation')
        return json.dumps({'ok': True, **result}, ensure_ascii=False, allow_nan=False)
    except Stopped as error:
        return json.dumps({'ok': False, 'error': {'code': error.code, 'message': 'Permintaan dihentikan.'}})
    except EngineError as error:
        return json.dumps({'ok': False, 'error': {'code': error.code, 'message': str(error)}})
    except Exception:
        return json.dumps({'ok': False, 'error': {'code': 'CATALOGUE_NETWORK', 'message': 'Katalog belum tersedia. Periksa koneksi lalu coba lagi.'}})
    finally:
        if metadata:
            metadata.client.close()
        if catalogue:
            catalogue.close()
