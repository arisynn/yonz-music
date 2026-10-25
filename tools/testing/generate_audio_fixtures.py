"""Regenerate original sine-wave fixtures. FFmpeg is a HOST TEST tool, never bundled/executed by the app."""
import pathlib
import subprocess

root = pathlib.Path(__file__).resolve().parents[2] / 'app/src/androidTest/assets/formats'
root.mkdir(parents=True, exist_ok=True)
for extension, codec in [('flac', 'flac'), ('m4a', 'aac'), ('ogg', 'libvorbis'), ('wav', 'pcm_s16le')]:
    subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i',
                    'sine=frequency=528:duration=3:sample_rate=44100', '-metadata', 'title=Yono Original Format Fixture',
                    '-metadata', 'artist=Karisyono Test Signal', '-metadata', 'album=Original Fixtures',
                    '-c:a', codec, str(root / f'owned-tone.{extension}')], check=True)
subprocess.run(['ffmpeg', '-nostdin', '-loglevel', 'error', '-y', '-f', 'lavfi', '-i', 'color=c=black:s=64x64:d=3',
                '-f', 'lavfi', '-i', 'sine=frequency=528:duration=3', '-c:v', 'mpeg4', '-c:a', 'aac', '-shortest',
                str(root / 'owned-video.mp4')], check=True)
