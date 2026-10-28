"""Resolve a single selected public audio stream; no transfer, shell, login or cache files."""
import json
import math
import re
import socket
import urllib.error
from urllib.parse import urlsplit
from yono_core.control import Control, Stopped
from yono_core.network import youtube_dl
from yono_core.model import EngineError, Song, thumbnail


def inspect_video(ident, control, runtime=None, factory=youtube_dl, format_id=None):
    if not isinstance(ident,str) or not re.fullmatch(r'[A-Za-z0-9_-]{11}',ident):
        raise EngineError('INVALID_SOURCE','Identitas sumber tidak valid.')
    if format_id is not None and (not isinstance(format_id,str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}',format_id)):
        raise EngineError('INVALID_FORMAT','Identitas format tidak valid.')
    with factory(control,runtime) as dl:
        dl.params.update(format=(format_id or 'bestaudio')+'[vcodec=none][protocol=https]',skip_download=True)
        data=dl.extract_info('https://www.youtube.com/watch?v='+ident,download=False)
    control.check()
    if not isinstance(data,dict) or data.get('id')!=ident or data.get('is_live') or data.get('has_drm') or data.get('availability') in {'private','premium_only','subscriber_only','needs_auth'}:
        raise EngineError('SOURCE_UNAVAILABLE','Rekaman tidak tersedia tanpa akses tambahan.')
    if len(data.get('requested_formats') or [])>1 or data.get('vcodec') not in (None,'none') or data.get('acodec') in (None,'none'):
        raise EngineError('FORMAT_UNAVAILABLE','Sumber belum menyediakan satu aliran audio yang didukung.')
    if format_id is not None and str(data.get('format_id',''))!=format_id:
        raise EngineError('FORMAT_CHANGED','Format rekaman berubah; mulai ulang pemutaran.')
    url=data.get('url','');u=urlsplit(url)
    if u.scheme!='https' or not u.hostname or not (u.hostname=='googlevideo.com' or u.hostname.endswith('.googlevideo.com')) or u.username or u.password or u.port not in (None,443) or u.fragment:
        raise EngineError('STREAM_HOST','Alamat audio sumber belum didukung.')
    artists=data.get('artists') or ([data['artist']] if data.get('artist') else [])
    artists=[str(a)[:250] for a in artists if isinstance(a,str)] if isinstance(artists,list) else []
    duration=data.get('duration')
    duration=duration if isinstance(duration,(int,float)) and math.isfinite(duration) and duration>0 else 0
    song=Song(data.get('track') or data.get('title') or '',artists,duration,album=data.get('album') or '',youtube_id=ident,
              artwork_url=thumbnail(data.get('thumbnails')),source_url='https://www.youtube.com/watch?v='+ident)
    headers={k:v for k,v in (data.get('http_headers') or {}).items() if k.lower() in {'user-agent','accept','accept-language'} and isinstance(v,str) and len(v)<1500 and '\r' not in v and '\n' not in v}
    return {'url':url,'headers':headers,'song':song.to_dict(),'ext':data.get('ext',''),'format_id':str(data.get('format_id',''))[:100],'acodec':str(data.get('acodec',''))[:100]}


def failure_code(error):
    """Inspect exceptions locally; never return extractor traces, URLs or credentials."""
    if isinstance(error,EngineError):
        return {'SOURCE_UNAVAILABLE':'UNAVAILABLE','FORMAT_UNAVAILABLE':'FORMAT','FORMAT_CHANGED':'FORMAT_CHANGED',
                'INVALID_FORMAT':'FORMAT','STREAM_HOST':'UNSAFE'}.get(error.code,'SOURCE')
    current=error
    for _ in range(8):
        if isinstance(current,(TimeoutError,socket.timeout)):return 'TIMEOUT'
        if isinstance(current,urllib.error.HTTPError):
            return {401:'ACCESS',403:'ACCESS',404:'UNAVAILABLE',410:'UNAVAILABLE',429:'RATE_LIMIT',
                    408:'SERVER',500:'SERVER',502:'SERVER',503:'SERVER',504:'SERVER'}.get(current.code,'SOURCE')
        current=getattr(current,'__cause__',None) or getattr(current,'reason',None)
        if current is None:break
    text=str(error)[:6000].lower()
    if 'tunnel connection failed' in text or 'proxyerror' in text or 'name resolution' in text or 'connection reset' in text:return 'NETWORK'
    if 'timed out' in text or 'timeout' in text:return 'TIMEOUT'
    if 'certificate verify' in text or 'ssl:' in text:return 'TLS'
    if 'http error 429' in text:return 'RATE_LIMIT'
    if 'http error 403' in text or 'http error 401' in text or 'sign in to confirm' in text:return 'ACCESS'
    if 'private video' in text or 'video unavailable' in text or 'http error 404' in text:return 'UNAVAILABLE'
    if 'requested format is not available' in text:return 'FORMAT'
    return 'SOURCE'


def execute(payload_json,config_json,callback):
    try:
        payload,config=json.loads(str(payload_json)),json.loads(str(config_json))
        token=Control(config['job_id'],callback,min(25,max(5,int(config.get('timeout_seconds',22)))))
        token.network_options=config.get('network') or {}
        options={'format_id':payload['format_id']} if payload.get('format_id') is not None else {}
        result=inspect_video(payload.get('video_id'),token,config.get('runtime'),**options)
        return json.dumps({'ok':True,**result},ensure_ascii=False,allow_nan=False)
    except Stopped as error:
        return json.dumps({'ok':False,'code':'CANCELLED' if error.code=='CANCELLED' else 'TIMEOUT',
                           'error':'Dibatalkan.' if error.code=='CANCELLED' else 'Sumber terlalu lama merespons.'})
    except Exception as error:
        return json.dumps({'ok':False,'code':failure_code(error),'error':'Sumber belum dapat menyediakan audio ini.'})
