"""Build-time NDK recipe. Does not download or execute code on the user's phone.

Supply reviewed FFmpeg and LAME source trees. This recipe is NOT claimed to have
been executed here; see TEST_REPORT.md. Build both APK ABIs before enabling MP3.
SPDX-License-Identifier: GPL-3.0-or-later
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess


def run(argv, cwd, env):
    subprocess.run([str(x) for x in argv], cwd=cwd, env=env, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path, required=True)
    parser.add_argument('--ffmpeg-src', type=Path, required=True)
    parser.add_argument('--lame-src', type=Path, required=True)
    parser.add_argument('--work', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--abi', choices=['arm64-v8a', 'x86_64'], action='append')
    parser.add_argument('--jobs', type=int, default=2)
    args = parser.parse_args()
    ndk, ffmpeg, lame = (x.resolve() for x in (args.ndk, args.ffmpeg_src, args.lame_src))
    toolchain = ndk/'toolchains/llvm/prebuilt/linux-x86_64'
    if not (toolchain/'bin/llvm-ar').is_file(): parser.error('Linux x86_64 NDK r28c toolchain required')
    if not (ffmpeg/'configure').is_file() or not (lame/'configure').is_file():
        parser.error('Use reviewed source releases with their generated configure scripts')
    work, output = args.work.resolve(), args.output.resolve()
    for abi in args.abi or ['arm64-v8a', 'x86_64']:
        target, arch = ('aarch64-linux-android', 'aarch64') if abi == 'arm64-v8a' else ('x86_64-linux-android', 'x86_64')
        base = work/abi
        prefix = base/'prefix'
        prefix.mkdir(parents=True, exist_ok=True)
        compiler = toolchain/'bin'/f'{target}26-clang'
        env = os.environ.copy()
        env.update({
            'PATH': str(toolchain/'bin') + os.pathsep + env.get('PATH',''),
            'CC': str(compiler), 'CXX': str(compiler) + '++',
            'AR': str(toolchain/'bin/llvm-ar'), 'RANLIB': str(toolchain/'bin/llvm-ranlib'),
            'STRIP': str(toolchain/'bin/llvm-strip'), 'CFLAGS': '-O2 -fPIC',
        })
        lame_build = base/'lame'
        lame_build.mkdir(exist_ok=True)
        run([lame/'configure', f'--host={target}', f'--prefix={prefix}', '--disable-shared',
             '--enable-static', '--disable-frontend', '--with-pic'], lame_build, env)
        run(['make', f'-j{max(1,args.jobs)}'], lame_build, env)
        run(['make', 'install'], lame_build, env)
        ff_build = base/'ffmpeg'
        ff_build.mkdir(exist_ok=True)
        run([ffmpeg/'configure', '--target-os=android', f'--arch={arch}', '--enable-cross-compile',
             f'--cc={compiler}', f'--ar={env["AR"]}', f'--ranlib={env["RANLIB"]}', f'--strip={env["STRIP"]}',
             f'--sysroot={toolchain/"sysroot"}', '--disable-shared', '--enable-static', '--enable-pic',
             '--disable-autodetect', '--disable-network', '--disable-doc', '--disable-debug',
             '--disable-x86asm', '--enable-small', '--disable-programs', '--enable-ffmpeg', '--enable-ffprobe',
             '--enable-libmp3lame', f'--extra-cflags=-I{prefix/"include"} -fPIC',
             f'--extra-ldflags=-L{prefix/"lib"}', '--extra-ldexeflags=-pie -Wl,-z,max-page-size=16384'], ff_build, env)
        run(['make', f'-j{max(1,args.jobs)}', 'ffmpeg', 'ffprobe'], ff_build, env)
        dest = output/abi
        dest.mkdir(parents=True, exist_ok=True)
        for program in ('ffmpeg','ffprobe'):
            file = dest/f'libyono_{program}.so'
            shutil.copy2(ff_build/program, file)
            run([env['STRIP'], file], ff_build, env)
            # Keep an explicit ELF header/dependency report alongside the build, outside jniLibs.
            audit = subprocess.run([str(toolchain/'bin/llvm-readelf'), '-h', '-l', '-d', str(file)],
                                   check=True,capture_output=True,text=True).stdout
            (base/f'{program}-elf.txt').write_text(audit)
        (base/'source-locations.json').write_text(json.dumps({'ffmpeg_source':str(ffmpeg),'lame_source':str(lame),
            'ndk':str(ndk),'abi':abi,'api':26,'recipe_tested_on_android':False},indent=2))


if __name__ == '__main__': main()
