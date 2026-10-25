"""Android bridge to a pinned, bundled yt-dlp. No shell, pip, updater, plugins or cookie import.
Kotlin owns the queue, permissions, SAF publication and native playback.
Only explicit source requests enter this module; startup/local playback never import it.
"""
import base64
import hashlib
import ipaddress
import json
import os
import pathlib
import socket
import time
import threading
import urllib.parse

MAX_AUDIO = 256 * 1024 * 1024
MAX_PAGE = 2 * 1024 * 1024
_NETWORK_POLICY = threading.local()
BLOCKED = {"spotify.com", "open.spotify.com", "accounts.spotify.com"}
STREAMING = {"youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be"}


class EngineError(Exception):
    pass


class TransferCancelled(EngineError):
    pass


def validate_url(value, service_permission=False, test_config=None):
    if not isinstance(value, str) or len(value) > 4096:
        raise EngineError("Invalid source link")
    parsed = urllib.parse.urlsplit(value)
    host = (parsed.hostname or "").lower()
    local_test = bool(test_config and test_config.get('localFixture') is True and host in {"127.0.0.1", "10.0.2.2", "localhost"})
    if parsed.username or parsed.password or parsed.fragment or not host or (parsed.scheme != "https" and not local_test):
        raise EngineError("Use a public HTTPS source without embedded credentials")
    if host in BLOCKED or host.endswith(".spotify.com"):
        raise EngineError("Spotify links supply metadata, not downloadable MP3 audio. Use an authorized export/API integration")
    if (host in STREAMING or host.endswith(".youtube.com") or host.endswith(".googlevideo.com")) and not service_permission:
        raise EngineError("This service requires separate download authorization under its terms, in addition to content rights")
    if parsed.port not in (None, 443) and not local_test:
        raise EngineError("Only the standard HTTPS port is accepted")
    for key, _ in urllib.parse.parse_qsl(parsed.query):
        if key.lower() in {"password", "access_token", "cookie", "authorization"}:
            raise EngineError("Credential-bearing links are not accepted")
    if not local_test:
        try:
            addresses = socket.getaddrinfo(host, 443, type=socket.SOCK_STREAM)
        except OSError as exc:
            raise EngineError("Cannot resolve the source. Check your connection") from exc
        if any(not ipaddress.ip_address(address[4][0].split('%')[0]).is_global for address in addresses):
            raise EngineError("Private-network source addresses are not accepted")
    return value


class QuietLog:
    # Extractors can log user URLs or tokens. Return bounded errors through the structured bridge instead.
    def debug(self, message):
        pass

    def info(self, message):
        pass

    def warning(self, message):
        pass

    def error(self, message):
        pass


class BoundedReader:
    def __init__(self, reader, limit):
        self.reader, self.limit, self.received = reader, limit, 0

    def read(self, count=None):
        available = self.limit - self.received + 1
        data = self.reader.read(min(count, available) if count is not None and count >= 0 else available)
        self.received += len(data)
        if self.received > self.limit:
            raise EngineError("Source response exceeds its size limit")
        return data

    def __getattr__(self, name):
        return getattr(self.reader, name)


def downloader(service_permission, qjs, callback=None, output=None, test_config=None):
    import yt_dlp
    from yt_dlp.networking import _urllib
    # Enforce redirect policy before the redirected request is sent (including private-network URLs).
    if not getattr(_urllib.RedirectHandler, '_yono_guarded', False):
        original_redirect = _urllib.RedirectHandler.redirect_request
        def guarded_redirect(self, req, fp, code, msg, headers, newurl):
            validate_url(newurl, getattr(_NETWORK_POLICY, 'service', False), getattr(_NETWORK_POLICY, 'test', None))
            return original_redirect(self, req, fp, code, msg, headers, newurl)
        _urllib.RedirectHandler.redirect_request = guarded_redirect
        _urllib.RedirectHandler._yono_guarded = True

    class GuardedYoutubeDL(yt_dlp.YoutubeDL):
        def build_request_director(self, handlers, preferences=None):
            # One reviewed transport keeps redirect/TLS policy identical on Android and host tests.
            director = super().build_request_director([_urllib.UrllibRH], preferences)
            if test_config and test_config.get('ca'):
                # Android system roots (or an explicit debug-test CA), with normal hostname/signature checks.
                for handler in director.handlers.values():
                    def context(*args, **kwargs):
                        import ssl
                        return ssl.create_default_context(cafile=test_config['ca'])
                    handler._make_sslcontext = context
            return director

        def urlopen(self, req):
            url = req if isinstance(req, str) else getattr(req, 'url', getattr(req, 'full_url', ''))
            validate_url(url, service_permission, test_config)
            previous = (getattr(_NETWORK_POLICY, 'service', False), getattr(_NETWORK_POLICY, 'test', None))
            _NETWORK_POLICY.service, _NETWORK_POLICY.test = service_permission, test_config
            try:
                response = super().urlopen(req)
            finally:
                _NETWORK_POLICY.service, _NETWORK_POLICY.test = previous
            # Validate redirects, too. Header/manifest documents have a much smaller bound than media.
            validate_url(response.url, service_permission, test_config)
            content_type = response.headers.get('Content-Type', '').lower()
            limit = MAX_PAGE if output is None or any(kind in content_type for kind in ('text/', 'json', 'xml', 'javascript')) else MAX_AUDIO
            response.fp = BoundedReader(response.fp, limit)
            return response

    def hook(data):
        if callback is not None:
            if int(data.get('downloaded_bytes') or 0) > MAX_AUDIO:
                raise EngineError('Audio exceeds the transfer size limit')
            if callback.cancelled():
                raise TransferCancelled("Cancelled by you")
            callback.progress(int(data.get('downloaded_bytes') or 0), int(data.get('total_bytes') or data.get('total_bytes_estimate') or 0))

    options = {
        'quiet': True, 'no_warnings': True, 'logger': QuietLog(),
        'noplaylist': True, 'playlistend': 50, 'socket_timeout': 15,
        'retries': 2, 'fragment_retries': 2, 'extractor_retries': 1,
        'max_filesize': MAX_AUDIO, 'max_downloads': 1,
        # Generic application/ogg links omit codec fields. Accept unknown codec metadata,
        # then require an actual audio-only container through Android's native parser.
        'format': 'bestaudio[vcodec=none][protocol=https]/bestaudio[vcodec=none]/best[vcodec=?none]',
        'fixup': 'never', 'postprocessors': [], 'cachedir': False,
        'continuedl': False, 'overwrites': False, 'nopart': False,
        'writethumbnail': False, 'writeinfojson': False, 'writesubtitles': False,
        'remote_components': set(), 'plugin_dirs': [], 'js_runtimes': {},
        'progress_hooks': [hook], 'concurrent_fragment_downloads': 1,
        'color': {'stdout': 'never', 'stderr': 'never'},
        # Do not invoke an absent platform FFmpeg, shell, impersonator or external downloader.
        'external_downloader': None, 'ffmpeg_location': '/__yono_no_external_ffmpeg__',
        'check_formats': False, 'skip_unavailable_fragments': False,
    }
    if qjs and os.path.isfile(qjs):
        options['js_runtimes'] = {'quickjs': {'path': qjs}}
    if output:
        options['outtmpl'] = os.path.join(output, 'payload.%(ext)s')
    if test_config:
        # This argument exists only in the debug Kotlin bridge and instrumented tests.
        if test_config.get('proxy'):
            options['proxy'] = test_config['proxy']
        if test_config.get('rate'):
            options['ratelimit'] = int(test_config['rate'])
    return GuardedYoutubeDL(options)


def source_record(info, requested_url):
    if info.get('_type') in {'playlist', 'multi_video'}:
        raise EngineError("Choose individual source links; implicit playlist expansion is disabled")
    if info.get('is_live') or info.get('live_status') in {'is_live', 'is_upcoming'}:
        raise EngineError("Live/upcoming sources cannot be validated as complete recordings")
    if info.get('has_drm') or info.get('availability') in {'private', 'premium_only', 'subscriber_only', 'needs_auth'}:
        raise EngineError("This source is restricted. No access or DRM bypass is provided")
    artists = info.get('artists') or info.get('creators') or [info.get('artist') or info.get('creator') or 'Unspecified credits']
    if isinstance(artists, str):
        artists = [artists]
    extractor = str(info.get('extractor_key') or info.get('extractor') or 'Generic')
    source_id = extractor + ':' + str(info.get('id') or '')
    if extractor.lower() == 'generic':
        source_id += ':' + hashlib.sha256(requested_url.encode()).hexdigest()[:24]
    return {
        'id': 'yt-dlp:' + source_id, 'title': str(info.get('track') or info.get('title') or 'Audio')[:512],
        'artist': '; '.join(str(a) for a in artists)[:512], 'album': str(info.get('album') or '')[:512],
        'duration': int(float(info.get('duration') or 0) * 1000),
        'size': int(info.get('filesize') or 0), 'url': requested_url,
        'license': str(info.get('license') or 'User-authorized source')[:512],
        'licenseUrl': '', 'page': str(info.get('webpage_url') or requested_url),
        'description': str(info.get('description') or '')[:1500],
        'artUrl': str(info.get('thumbnail') or '')[:4096],
    }


def inspect(url, service_permission, qjs='', test_json=''):
    config = json.loads(test_json) if test_json else None
    validate_url(url, service_permission, config)
    with downloader(service_permission, qjs, test_config=config) as engine:
        info = engine.extract_info(url, download=False)
        return json.dumps(source_record(info, url), ensure_ascii=False)


def transfer(url, output, service_permission, callback, qjs='', test_json=''):
    config = json.loads(test_json) if test_json else None
    validate_url(url, service_permission, config)
    root = pathlib.Path(output).resolve()
    root.mkdir(parents=True, exist_ok=True)
    if list(root.iterdir()):
        raise EngineError("Transfer workspace must be empty")
    with downloader(service_permission, qjs, callback, str(root), config) as engine:
        info = engine.extract_info(url, download=False)
        record = source_record(info, url)
        if str(info.get('ext', '')).lower() not in {'mp3', 'flac', 'm4a', 'm4b', 'aac', 'wav', 'ogg', 'oga', 'opus', 'webm', 'weba', 'mp4'}:
            raise EngineError("The source did not offer a supported standalone audio container")
        if len(info.get('requested_formats') or []) > 1:
            raise EngineError("This source requires merging streams; no external FFmpeg pipeline is bundled")
        if callback.cancelled():
            raise TransferCancelled("Cancelled by you")
        # Native validation rejects video/malformed/partial audio before any shared publication.
        from yt_dlp.utils import MaxDownloadsReached
        try:
            engine.process_info(info)
        except MaxDownloadsReached:
            # yt-dlp signals the one-file limit after finishing that file. Validate it below.
            pass
        file = pathlib.Path(engine.prepare_filename(info)).resolve()
        if file.parent != root or not file.is_file() or not 0 < file.stat().st_size <= MAX_AUDIO:
            raise EngineError("No complete bounded audio file was produced")
        callback.progress(file.stat().st_size, file.stat().st_size)
        return json.dumps({'path': str(file), 'metadata': record}, ensure_ascii=False)


def tags(path, metadata_json, artwork_path=''):
    """Mutagen replaces FFmpeg tag/cover rewriting without re-encoding the audio stream."""
    import mutagen
    from mutagen.id3 import ID3, TIT2, TPE1, TALB, TXXX, TCOP, APIC
    from mutagen.flac import FLAC, Picture
    from mutagen.mp3 import MP3
    from mutagen.mp4 import MP4, MP4Cover
    from mutagen.oggvorbis import OggVorbis
    from mutagen.oggopus import OggOpus
    metadata = json.loads(metadata_json)
    audio = mutagen.File(path)
    if audio is None:
        with open(path, 'rb') as source:
            if source.read(4) == b'\x1aE\xdf\xa3':
                return json.dumps({'embedded': False})  # Native-validated WebM: metadata stays in Room/sidecar.
        raise EngineError("Audio metadata is unreadable")
    picture = pathlib.Path(artwork_path).read_bytes() if artwork_path else None
    if picture and len(picture) > 1024 * 1024:
        raise EngineError("Artwork exceeds 1 MB")
    image_mime = 'image/png' if picture and picture.startswith(b'\x89PNG') else 'image/jpeg'
    title, artists, album = metadata['title'], metadata['artist'].split('; '), metadata.get('album', '')
    if isinstance(audio, MP3):
        if audio.tags is None:
            audio.add_tags()
        audio.tags.add(TIT2(encoding=3, text=title)); audio.tags.add(TPE1(encoding=3, text=artists)); audio.tags.add(TALB(encoding=3, text=album))
        audio.tags.add(TXXX(encoding=3, desc='SOURCE_ID', text=metadata['sourceId']))
        audio.tags.add(TXXX(encoding=3, desc='SOURCE_URL', text=metadata['url']))
        audio.tags.add(TCOP(encoding=3, text=metadata.get('license', '')))
        if picture:
            audio.tags.delall('APIC'); audio.tags.add(APIC(encoding=3, mime=image_mime, type=3, desc='Front cover', data=picture))
    elif isinstance(audio, (FLAC, OggVorbis, OggOpus)):
        audio['title'] = [title]; audio['artist'] = artists; audio['album'] = [album]
        audio['source_id'] = [metadata['sourceId']]; audio['source_url'] = [metadata['url']]; audio['license'] = [metadata.get('license', '')]
        if picture:
            pic = Picture(); pic.data = picture; pic.mime = image_mime; pic.type = 3
            if isinstance(audio, FLAC):
                audio.clear_pictures(); audio.add_picture(pic)
            else:
                audio['metadata_block_picture'] = [base64.b64encode(pic.write()).decode('ascii')]
    elif isinstance(audio, MP4):
        if audio.tags is None:
            audio.add_tags()
        audio['\xa9nam'] = [title]; audio['\xa9ART'] = artists; audio['\xa9alb'] = [album]
        audio['----:com.yonomusic:source_id'] = [metadata['sourceId'].encode('utf8')]
        if picture:
            audio['covr'] = [MP4Cover(picture, imageformat=MP4Cover.FORMAT_PNG if image_mime=='image/png' else MP4Cover.FORMAT_JPEG)]
    else:
        # Native Room metadata remains authoritative for WAV/raw AAC. No risky container rewrite.
        return json.dumps({'embedded': False})
    audio.save()
    return json.dumps({'embedded': True})


def runtime_report(qjs=''):
    import sys
    import ssl
    import sqlite3
    import zlib
    import pyexpat
    import yt_dlp.version
    import importlib.metadata
    import shutil
    report = {'python': sys.version.split()[0], 'openssl': ssl.OPENSSL_VERSION, 'sqlite': sqlite3.sqlite_version,
              'zlib': zlib.ZLIB_RUNTIME_VERSION, 'expat': pyexpat.EXPAT_VERSION, 'yt_dlp': yt_dlp.version.__version__,
              'ejs': importlib.metadata.version('yt-dlp-ejs'), 'mutagen': importlib.metadata.version('mutagen'),
              'node': bool(shutil.which('node')), 'deno': bool(shutil.which('deno')),
              'ffmpeg': bool(shutil.which('ffmpeg')), 'ffprobe': bool(shutil.which('ffprobe'))}
    if qjs and os.path.isfile(qjs):
        import subprocess
        # Only the immutable, build-time bundled Android executable chosen by Kotlin. No shell.
        result = subprocess.run([qjs, '-e', 'console.log(JSON.stringify({value:6*7,runtime:"quickjs-ng"}))'], capture_output=True, text=True, timeout=10)
        report['quickjs'] = result.stdout.strip(); report['quickjsExit'] = result.returncode
        import importlib.resources
        scripts = importlib.resources.files('yt_dlp_ejs.yt.solver')
        script = scripts.joinpath('lib.min.js').read_text() + '\nObject.assign(globalThis, lib);\n' + scripts.joinpath('core.min.js').read_text()
        script += '\nconsole.log(JSON.stringify({ejsLoaded:typeof jsc === "function",answer:Math.imul(6,7),filesystem:typeof std,network:typeof fetch}));'
        import tempfile
        with tempfile.NamedTemporaryFile(mode='w', suffix='.js', delete=False) as fixture:
            fixture.write(script)
            name = fixture.name
        try:
            ejs = subprocess.run([qjs, '--script', name], capture_output=True, text=True, timeout=25)
            report['ejsBoot'] = ejs.stdout.strip(); report['ejsExit'] = ejs.returncode
        finally:
            pathlib.Path(name).unlink(missing_ok=True)
        with downloader(False, qjs) as engine:
            runtime = engine._js_runtimes['quickjs'].info
            report['ytDlpJsRuntime'] = {'name': runtime.name, 'version': runtime.version, 'supported': runtime.supported} if runtime else None
    return json.dumps(report)


def artwork(url, destination, service_permission, qjs='', test_json=''):
    config = json.loads(test_json) if test_json else None
    validate_url(url, service_permission, config)
    with downloader(service_permission, qjs, test_config=config) as engine:
        with engine.urlopen(url) as response:
            image = response.read(1024 * 1024 + 1)
            if len(image) > 1024 * 1024:
                raise EngineError("Artwork exceeds 1 MB")
            pathlib.Path(destination).write_bytes(image)
    return destination
