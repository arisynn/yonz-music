"""Explicit network activity only. Adapted from the app's guarded yt-dlp bridge."""
from __future__ import annotations
import ipaddress
import json
import socket
import urllib.request
import urllib.error
from urllib.parse import urlsplit, parse_qsl
from .control import Stopped
from .model import EngineError

MAX_AUDIO = 256 * 1024 * 1024
MAX_DOCUMENT = 4 * 1024 * 1024


def public_https(url, control=None):
    if control:
        control.check()
    if not isinstance(url, str) or len(url) > 4096:
        raise EngineError("INVALID_LINK", "Tautan tidak valid atau terlalu panjang.")
    try:
        parsed = urlsplit(url)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                or parsed.port not in (None, 443)):
            raise ValueError()
        if any(k.casefold() in {"password", "access_token", "authorization", "cookie"} for k, _ in parse_qsl(parsed.query)):
            raise ValueError()
    except ValueError as error:
        raise EngineError("INVALID_LINK", "Gunakan tautan HTTPS publik tanpa kredensial.") from error
    try:
        addresses = socket.getaddrinfo(parsed.hostname, 443, type=socket.SOCK_STREAM)
    except OSError as error:
        if control:
            control.check()
        raise EngineError("NETWORK", "Nama sumber tidak dapat ditemukan. Periksa koneksi.", retryable=True) from error
    if not addresses or any(not ipaddress.ip_address(a[4][0].split("%")[0]).is_global for a in addresses):
        raise EngineError("PRIVATE_ADDRESS", "Alamat jaringan lokal tidak didukung.")
    if control:
        control.check()
    return url


def session(control, allowed_hosts):
    import requests

    class BoundedAdapter(requests.adapters.HTTPAdapter):
        def send(self, request, **kwargs):
            control.check()
            if urlsplit(request.url).hostname not in allowed_hosts:
                raise EngineError("INVALID_REDIRECT", "Pengalihan metadata menuju host yang tidak diizinkan.")
            public_https(request.url, control)
            client.request_count += 1
            if client.request_count > 512:
                raise EngineError("PAGINATION_LIMIT", "Terlalu banyak permintaan; proses dihentikan.")
            control.emit("reading_source", request_number=client.request_count, message="Membaca metadata sumber…")
            kwargs["timeout"] = (min(8, control.remaining()), min(12, control.remaining()))
            kwargs["stream"] = True
            # Bound the body before Session.resolve_redirects can consume it.
            response = super().send(request, **kwargs)
            if not response._content_consumed:
                try:
                    chunks, size = [], 0
                    for chunk in response.iter_content(64 * 1024):
                        control.check()
                        size += len(chunk)
                        if size > MAX_DOCUMENT:
                            raise EngineError("RESPONSE_TOO_LARGE", "Respons metadata terlalu besar.")
                        chunks.append(chunk)
                    response._content = b"".join(chunks)
                    response._content_consumed = True
                finally:
                    response.close()
            control.check()
            return response

    client = requests.Session()
    options = getattr(control, "network_options", {})
    if options.get("ca"):
        client.verify = options["ca"]
    if options.get("proxy"):
        client.proxies = {"https": options["proxy"]}
    client.trust_env = False
    client.max_redirects = 5
    client.request_count = 0
    client.mount("https://", BoundedAdapter(max_retries=0))
    return client


class Reader:
    def __init__(self, wrapped, limit, control):
        self.wrapped, self.limit, self.control, self.received = wrapped, limit, control, 0

    def read(self, size=-1):
        self.control.check()
        if size is None:
            size = -1
        count = min(64 * 1024, self.limit - self.received + 1) if size < 0 else min(size, self.limit - self.received + 1)
        data = self.wrapped.read(count)
        self.received += len(data)
        if self.received > self.limit:
            raise EngineError("RESPONSE_TOO_LARGE", "Sumber melampaui batas ukuran.")
        self.control.check()
        # read(-1) must preserve the file-like contract, including complete HTML/JSON.
        if size < 0:
            chunks = [data]
            while data:
                data = self.read(64 * 1024)
                chunks.append(data)
            return b"".join(chunks)
        return data

    def __getattr__(self, name):
        return getattr(self.wrapped, name)


def youtube_dl(control, runtime=None, *, flat=False, output=None):
    import yt_dlp
    from yt_dlp.networking import _urllib

    class Redirect(urllib.request.HTTPRedirectHandler):
        # Same method semantics as pinned yt-dlp, with a policy check before forwarding.
        http_error_301 = http_error_303 = http_error_307 = http_error_308 = urllib.request.HTTPRedirectHandler.http_error_302

        def redirect_request(self, request, fp, code, message, headers, newurl):
            public_https(newurl, control)
            if code not in {301, 302, 303, 307, 308}:
                raise urllib.error.HTTPError(request.full_url, code, message, headers, fp)
            method = _urllib.get_redirect_method(request.get_method(), code)
            remove = {"cookie", "authorization", "proxy-authorization"}
            data = request.data
            if method != request.get_method():
                data = None
                remove.update({"content-length", "content-type"})
            return urllib.request.Request(newurl, data=data, method=method,
                headers={k: v for k, v in request.headers.items() if k.casefold() not in remove},
                origin_req_host=request.origin_req_host, unverifiable=True)

    class YonoUrllibRH(_urllib.UrllibRH):
        def _make_sslcontext(self, *args, **kwargs):
            options = getattr(control, "network_options", {})
            if options.get("ca"):
                import ssl
                return ssl.create_default_context(cafile=options["ca"])
            return super()._make_sslcontext(*args, **kwargs)

        def _create_instance(self, *args, **kwargs):
            original = super()._create_instance(*args, **kwargs)
            opener = urllib.request.OpenerDirector()
            for handler in original.handlers:
                if not isinstance(handler, urllib.request.HTTPRedirectHandler):
                    opener.add_handler(handler)
            opener.add_handler(Redirect())
            opener.addheaders = []
            return opener

    class Log:
        def debug(self, _message):
            control.check()
        info = debug
        def warning(self, _message):
            control.check()
            # Extractor logs can include signed URLs. Do not send raw messages to UI/history.
            control.emit("source_warning", code="EXTRACTOR_WARNING", message="Sumber memberi peringatan; rincian hasil diperiksa kembali.")
        error = warning

    class GuardedYoutubeDL(yt_dlp.YoutubeDL):
        def build_request_director(self, handlers, preferences=None):
            return super().build_request_director([YonoUrllibRH], preferences)

        def urlopen(self, request):
            control.check()
            url = request if isinstance(request, str) else getattr(request, "url", getattr(request, "full_url", ""))
            public_https(url, control)
            result = super().urlopen(request)
            public_https(result.url, control)
            kind = result.headers.get("Content-Type", "").lower()
            limit = MAX_DOCUMENT if flat or output is None or any(x in kind for x in ("text/", "json", "xml", "javascript")) else MAX_AUDIO
            result.fp = Reader(result.fp, limit, control)
            return result

    options = {
        "quiet": True, "no_warnings": True, "logger": Log(),
        "socket_timeout": min(12, control.remaining()), "retries": 1,
        "fragment_retries": 1, "extractor_retries": 1, "ignoreerrors": False,
        "noplaylist": not flat, "extract_flat": "in_playlist" if flat else False,
        "lazy_playlist": flat, "skip_download": flat,
        "writethumbnail": False, "writeinfojson": False, "writesubtitles": False,
        "writeautomaticsub": False, "postprocessors": [], "fixup": "never",
        "remote_components": set(), "plugin_dirs": [], "js_runtimes": runtime or {},
        "cachedir": False, "continuedl": False, "overwrites": False,
        "concurrent_fragment_downloads": 1, "progress_hooks": [control.hook],
        "max_filesize": MAX_AUDIO, "external_downloader": None,
        "format": "bestaudio[vcodec=none]/bestaudio",
        "ffmpeg_location": "/__yono_no_implicit_ffmpeg__",
    }
    transport = getattr(control, "network_options", {})
    if transport.get("proxy"):
        options["proxy"] = transport["proxy"]
    if output:
        options["outtmpl"] = str(output / "source.%(ext)s")
    return GuardedYoutubeDL(options)


def fetch_picture(url, control):
    # Artwork is optional, but an arbitrary URL from an import must not contact arbitrary services.
    host = (urlsplit(url).hostname or "").lower()
    allowed = {"i.ytimg.com", "img.youtube.com", "lh3.googleusercontent.com", "i.scdn.co",
               "image-cdn-ak.spotifycdn.com", "image-cdn-fa.spotifycdn.com"}
    if host not in allowed and not host.endswith(".spotifycdn.com"):
        raise EngineError("ARTWORK_HOST", "Host sampul tidak didukung.")
    with session(control, {host}) as client:
        response = client.get(url)
        response.raise_for_status()
        image = response.content
    if not 0 < len(image) <= 1024 * 1024:
        raise EngineError("ARTWORK_SIZE", "Sampul melampaui 1 MiB.")
    if not (image.startswith(b"\x89PNG\r\n\x1a\n") or image.startswith(b"\xff\xd8\xff")):
        raise EngineError("ARTWORK_FORMAT", "Sampul harus berupa JPEG/PNG.")
    return image
