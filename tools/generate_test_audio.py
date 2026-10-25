"""Generate original synthetic fixtures for manual emulator checks. Requires FFmpeg.
No audio is downloaded. Run in an empty output directory of your choice.
"""
import argparse
import pathlib
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('output', type=pathlib.Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
rows = [('01_Dawn_Signal.mp3', 'Dawn Signal', 180, 'libmp3lame'),
        ('02_Violet_Hours.flac', 'Violet Hours', 90, 'flac'),
        ('03_Glass_Patterns.m4a', 'Glass Patterns', 90, 'aac'),
        ('04_Paper_Waves.wav', 'Paper Waves', 30, 'pcm_s16le'),
        ('05_Slow_Orbit.ogg', 'Slow Orbit', 90, 'libvorbis')]
for number, (name, title, duration, codec) in enumerate(rows):
    path = args.output / name
    if path.exists():
        raise SystemExit(f'Refusing to overwrite {path}')
    subprocess.run(['ffmpeg', '-nostdin', '-hide_banner', '-loglevel', 'error',
                    '-f', 'lavfi', '-i', f'sine=frequency={220 + 110 * number}:duration={duration}',
                    '-c:a', codec, '-metadata', f'title={title}', '-metadata', 'artist=Yonz Test Ensemble',
                    '-metadata', 'album=Quiet Signals' if number < 2 else 'album=Afterglow', str(path)], check=True)
print('Created five original tones. These files are not bundled in the app.')
