"""Build reviewed QuickJS-NG sources with the Android NDK. Build-time only; never run on a phone."""
import argparse
import pathlib
import shutil
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--sdk', type=pathlib.Path, required=True)
parser.add_argument('--output', type=pathlib.Path, required=True)
args = parser.parse_args()
root = pathlib.Path(__file__).resolve().parents[2]
ndk = args.sdk / 'ndk/28.2.13676358'
cmake = args.sdk / 'cmake/3.22.1/bin/cmake'
assert cmake.is_file() and (ndk / 'build/cmake/android.toolchain.cmake').is_file(), 'Install NDK 28.2.13676358 and CMake 3.22.1 with sdkmanager'
for abi in ['arm64-v8a', 'x86_64']:
    build = root / 'app/build/yono-quickjs' / abi
    subprocess.run([str(cmake), '-S', str(root / 'native'), '-B', str(build),
                    '-DCMAKE_TOOLCHAIN_FILE=' + str(ndk / 'build/cmake/android.toolchain.cmake'),
                    '-DANDROID_ABI=' + abi, '-DANDROID_PLATFORM=android-26',
                    '-DCMAKE_BUILD_TYPE=Release', '-DCMAKE_EXE_LINKER_FLAGS=-Wl,-z,max-page-size=16384',
                    '-DQJS_ENABLE_INSTALL=OFF'], check=True)
    subprocess.run([str(cmake), '--build', str(build), '--target', 'yono_qjs', '-j1'], check=True)
    target = args.output / abi / 'libqjs.so'
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(build / 'qjs', target)
    subprocess.run([str(ndk / 'toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-strip'), str(target)], check=True)
