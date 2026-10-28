"""Explicit read-only public playlist snapshot. Exact video IDs, no audio acquisition."""
import json
import re
from urllib.parse import urlsplit, parse_qs
from yono_core.control import Control, Stopped
from yono_core.network import youtube_dl
from yono_search import normalize, clean

MAX_SONGS = 500

def canonical(value):
    if not isinstance(value, str) or len(value)>4096:
        raise ValueError('invalid URL')
    u=urlsplit(value.strip())
    if u.scheme!='https' or u.hostname not in {'music.youtube.com','youtube.com','www.youtube.com'} or u.username or u.password or u.port not in (None,443) or u.fragment or u.path not in {'/playlist','/watch'}:
        raise ValueError('invalid source')
    ids=parse_qs(u.query).get('list',[])
    if len(ids)!=1 or not re.fullmatch(r'[A-Za-z0-9_-]{10,200}',ids[0]) or ids[0].startswith(('RD','UL','WL','LL')):
        raise ValueError('use a fixed public playlist')
    return 'https://music.youtube.com/playlist?list='+ids[0]

def read(value,control,factory=youtube_dl):
    url=canonical(value)
    with factory(control,flat=True) as dl:
        dl.params.update(playlistend=MAX_SONGS+1,ignoreerrors=False)
        data=dl.extract_info(url,download=False)
        if not isinstance(data,dict) or data.get('_type')!='playlist':
            raise ValueError('playlist unavailable')
        rows=[];seen=set();unavailable=duplicates=total=0
        for item in data.get('entries') or []:
            control.check();total+=1
            if total>MAX_SONGS:raise ValueError('playlist exceeds500 songs; not truncated')
            song=normalize(item) if isinstance(item,dict) and item.get('availability') not in {'private','premium_only','subscriber_only','needs_auth'} and item.get('title') not in {'[Deleted video]','[Private video]'} else None
            if not song:unavailable+=1;continue
            if song['id'] in seen:duplicates+=1;continue
            seen.add(song['id']);rows.append(song)
        expected=data.get('playlist_count') or data.get('n_entries')
        if type(expected) is int and expected!=total:raise ValueError('incomplete playlist; retry')
        if not rows:raise ValueError('no readable songs')
    control.check()
    return {'ok':True,'name':clean(data.get('title') or 'Playlist online',100),'origin':url,'songs':rows,'unavailable':unavailable,'duplicates':duplicates,'total':total}

def execute(payload_json,config_json,callback):
    try:
        p,c=json.loads(str(payload_json)),json.loads(str(config_json))
        control=Control(str(c['job_id']),callback,40);control.network_options=c.get('network') or {}
        result=read(p.get('url'),control)
    except Stopped as e:
        result={'ok':False,'error':{'code':e.code,'message':'Dibatalkan.' if e.code=='CANCELLED' else 'Playlist terlalu lama merespons. Coba lagi.'}}
    except ValueError:
        result={'ok':False,'error':{'code':'PLAYLIST_INVALID','message':'Playlist tidak lengkap, kosong, privat, dinamis, atau melebihi500 entri. Gunakan tautan playlist publik tetap.'}}
    except Exception:
        result={'ok':False,'error':{'code':'PLAYLIST_NETWORK','message':'Playlist belum dapat dibaca. Periksa koneksi; sumber dapat membatasi akses otomatis.'}}
    return json.dumps(result,ensure_ascii=False,allow_nan=False)
