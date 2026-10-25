"""Run meaningful host tests and write actual machine-readable results, no Android claims."""
from datetime import datetime
import importlib.metadata
import io
import json
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import time
import unittest
from zoneinfo import ZoneInfo


def main():
    root=Path(__file__).resolve().parents[2]
    sys.path.insert(0,str(root/'app/src/main/python'))
    sys.path.insert(0,str(root/'tools/yono-engine/tests'))
    output=io.StringIO()
    suite=unittest.defaultTestLoader.discover(str(root/'tools/yono-engine/tests'))
    started=time.monotonic()
    result=unittest.TextTestRunner(stream=output,verbosity=2).run(suite)
    packages={}
    for name in ('yt-dlp','yt-dlp-ejs','ytmusicapi','mutagen','requests','certifi','charset-normalizer','idna','urllib3'):
        try: packages[name]=importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError: packages[name]=None
    tools={}
    for name in ('ffmpeg','ffprobe','node','adb','gradle','kotlinc','javac'):
        executable=shutil.which(name)
        tools[name]=bool(executable)
        if executable and name in {'ffmpeg','ffprobe','node'}:
            args=[executable,'--version'] if name=='node' else [executable,'-version']
            tools[name+'_version']=subprocess.run(args,check=True,capture_output=True,text=True).stdout.splitlines()[0]
    report={
        'generated_at':datetime.now(ZoneInfo('Asia/Jakarta')).isoformat(),
        'python':platform.python_version(),'platform':platform.platform(),
        'tests_run':result.testsRun,'failures':len(result.failures),'errors':len(result.errors),
        'skipped':[{'test':str(test),'reason':reason} for test,reason in result.skipped],
        'successful':result.wasSuccessful(),'seconds':round(time.monotonic()-started,3),
        'packages':packages,'tools':tools,
        'fixtures':'Synthetic own sine audio + fabricated metadata; no commercial recording downloaded',
        'android_compiled':False,'android_runtime_tested':False,'live_youtube_download_tested':False,
        'live_spotify_api_tested':False,'hyperos_tested':False,'play_protect_tested':False,
    }
    target=root/'docs/verification/engine-1.2'; target.mkdir(exist_ok=True)
    (target/'host-tests.log').write_text(output.getvalue(),encoding='utf-8')
    (target/'host-results.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:report[k] for k in ('tests_run','failures','errors','skipped','successful','seconds')},ensure_ascii=False))
    return 0 if result.wasSuccessful() else 1


if __name__=='__main__': raise SystemExit(main())
