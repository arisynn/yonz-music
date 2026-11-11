"""Bounded public music metadata. No account, audio transfer, or source substitution."""
import json
from urllib.parse import urlsplit
from yono_core.control import Control, Stopped
from yono_core.catalogue import Catalogue
from yono_search import clean, normalize, run as fallback_search


def music_song(row):
    if not isinstance(row, dict):
        return None
    if row.get('isLive') or row.get('isUnavailable'):
        return None
    artists = [a.get('name', '') for a in (row.get('artists') or []) if isinstance(a, dict)]
    duration = row.get('duration_seconds') or row.get('lengthSeconds')
    if not duration and isinstance(row.get('length'), str):
        try:
            duration = sum(int(part) * 60**i for i, part in enumerate(reversed(row['length'].split(':'))))
        except (ValueError, OverflowError):
            duration = 0
    try:
        duration = float(duration or 0)
    except (TypeError, ValueError):
        duration = 0
    album = row.get('album')
    song = normalize({'id': row.get('videoId'), 'title': row.get('title'), 'artists': artists,
                      'duration': duration, 'album': album.get('name', '') if isinstance(album, dict) else '',
                      'thumbnails': row.get('thumbnails') or []})
    if song:
        for image in reversed(row.get('thumbnails') or []):
            url=image.get('url','') if isinstance(image,dict) else ''
            try:
                u=urlsplit(url)
                if u.scheme=='https' and u.hostname in {'lh3.googleusercontent.com','i.ytimg.com','img.youtube.com'} and not u.username and not u.password and u.port in (None,443):
                    song['artwork']=url[:4096];break
            except (TypeError,ValueError):
                continue
    return song


def run(payload, control, catalogue_factory=Catalogue):
    operation=payload.get('operation','search')
    query=clean(payload.get('query'),200)
    if operation=='search' and '://' in query:
        return fallback_search(payload,control)
    catalogue=catalogue_factory(control)
    try:
        if operation=='search':
            page=payload.get('page',0)
            if len(query)<2 or type(page) is not int or page not in range(3):
                raise ValueError('invalid search')
            try:
                raw=catalogue.call('search',query,filter='songs',limit=(page+1)*12+1)
                rows=[song for row in raw if (song:=music_song(row))]
                unique=list({s['video_id']:s for s in rows}.values())
                return {'ok':True,'songs':unique[page*12:(page+1)*12], 'next':str(page+1) if len(unique)>(page+1)*12 and page<2 else ''}
            except Exception:
                control.check()
                return fallback_search(payload,control)
        if operation=='related':
            ident=payload.get('video_id','')
            import re
            if not isinstance(ident,str) or not re.fullmatch(r'[A-Za-z0-9_-]{11}',ident):
                raise ValueError('invalid seed')
            raw=catalogue.call('get_watch_playlist',videoId=ident,radio=True,limit=20)
            rows=[song for row in (raw.get('tracks') or [])[:200] if (song:=music_song(row)) and song['video_id']!=ident]
            return {'ok':True,'songs':list({s['video_id']:s for s in rows}.values())[:20],'next':''}
        raise ValueError('invalid operation')
    finally:
        catalogue.close()


def execute(payload_json,config_json,callback):
    try:
        payload,config=json.loads(str(payload_json)),json.loads(str(config_json))
        control=Control(config['job_id'],callback,min(20,max(5,int(config.get('timeout_seconds',18)))))
        control.network_options=config.get('network') or {}
        control.check()
        result=run(payload,control)
    except Stopped as error:
        result={'ok':False,'error':{'code':error.code,'message':'Permintaan dihentikan.'}}
    except Exception:
        result={'ok':False,'error':{'code':'CATALOGUE_NETWORK','message':'Katalog belum tersedia. Periksa koneksi atau coba lagi.'}}
    return json.dumps(result,ensure_ascii=False,allow_nan=False)
