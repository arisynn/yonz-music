"""Opt-in source availability probe. Records sanitized outcomes, never signed URLs.

Run with PYTHONPATH=app/src/main/python and the pinned host dependencies.
This resolves metadata only; it is not evidence of decoded Android playback.
"""
import json
import os
import time
from yono_catalog import execute as catalog
from yono_stream import execute as stream


def main():
    network={'proxy':os.environ.get('HTTPS_PROXY',''),'ca':os.environ.get('SSL_CERT_FILE','')}
    config=json.dumps({'job_id':'streaming-v2-live-probe','timeout_seconds':18,'network':network})
    started=time.monotonic()
    search=json.loads(catalog(json.dumps({'operation':'search','query':'Kevin MacLeod Carefree','page':0}),config,None))
    report={'kind':'host-metadata-only','search':{'ok':search.get('ok',False),'elapsedMs':round((time.monotonic()-started)*1000)},'resolutions':[]}
    if not search.get('ok'):
        report['search']['code']=search.get('error',{}).get('code','UNKNOWN')
    songs=search.get('songs',[])[:3]
    if not songs:
        # Known public Blender recording: probe resolution independently of catalogue search.
        songs=[{'video_id':'aqz-KE-bpKQ'}]
        report['knownPublicVideoProbe']=True
    for song in songs:
        started=time.monotonic()
        value=json.loads(stream(json.dumps({'video_id':song['video_id'],'quality':'balanced'}),config,None))
        report['resolutions'].append({'ok':value.get('ok',False),'elapsedMs':round((time.monotonic()-started)*1000),
                                      'code':value.get('code','OK'),'bitrate':value.get('bitrate',0)})
    report['decodedPlaybackTested']=False
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
