"""Resolve a single selected public audio stream; no transfer, shell, login or cache files."""
import json
import math
import re
from urllib.parse import urlsplit
from yono_core.control import Control, Stopped
from yono_core.network import youtube_dl
from yono_core.model import EngineError, Song, thumbnail


def inspect_video(ident, control, runtime=None, factory=youtube_dl):
    if not isinstance(ident,str) or not re.fullmatch(r'[A-Za-z0-9_-]{11}',ident):
        raise EngineError('INVALID_SOURCE','Identitas sumber tidak valid.')
    with factory(control,runtime) as dl:
        dl.params.update(format='bestaudio[vcodec=none][protocol=https]',skip_download=True)
        data=dl.extract_info('https://www.youtube.com/watch?v='+ident,download=False)
    control.check()
    if not isinstance(data,dict) or data.get('id')!=ident or data.get('is_live') or data.get('has_drm') or data.get('availability') in {'private','premium_only','subscriber_only','needs_auth'}:
        raise EngineError('SOURCE_UNAVAILABLE','Rekaman tidak tersedia tanpa akses tambahan.')
    if len(data.get('requested_formats') or [])>1 or data.get('vcodec') not in (None,'none') or data.get('acodec') in (None,'none'):
        raise EngineError('FORMAT_UNAVAILABLE','Sumber belum menyediakan satu aliran audio yang didukung.')
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
    return {'url':url,'headers':headers,'song':song.to_dict(),'ext':data.get('ext',''),'format_id':str(data.get('format_id',''))[:100]}


def execute(payload_json,config_json,callback):
    try:
        payload,config=json.loads(str(payload_json)),json.loads(str(config_json))
        token=Control(config['job_id'],callback,min(25,max(5,int(config.get('timeout_seconds',22)))))
        token.network_options=config.get('network') or {}
        result=inspect_video(payload.get('video_id'),token,config.get('runtime'))
        return json.dumps({'ok':True,**result},ensure_ascii=False,allow_nan=False)
    except Stopped as e:
        return json.dumps({'ok':False,'error':'Dibatalkan.' if e.code=='CANCELLED' else 'Sumber terlalu lama merespons.'})
    except EngineError as e:
        return json.dumps({'ok':False,'error':str(e)})
    except Exception:
        return json.dumps({'ok':False,'error':'Audio belum dapat dibaca dari sumber. Coba lagi; sumber mungkin membatasi akses.'})
