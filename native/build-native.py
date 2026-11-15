"""Build the arm64 RAW decoder with the official Android NDK, without changing host tooling."""
from pathlib import Path
import os, subprocess, concurrent.futures, re

root = Path(__file__).resolve().parent
toolchain = Path(os.environ.get('CAMERA_NDK_TOOLCHAIN', '/tmp/camera-roll-native/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64'))
compiler = toolchain / 'bin/clang++'
source = root / 'LibRaw'
objects = root.parent / 'build/native-objects'
objects.mkdir(parents=True, exist_ok=True)
flags = ['--target=aarch64-linux-android29', '--sysroot=' + str(toolchain / 'sysroot'), '-O2', '-fPIC', '-std=c++11', '-DUSE_ZLIB', '-DLIBRAW_LIBRARY_BUILD', '-fvisibility=hidden', '-I' + str(source)]
manifest = (source / 'Makefile.am').read_text().split('lib_libraw_a_SOURCES =',1)[1].split('\n\n',1)[0]
inputs = [source / name for name in re.findall(r'src/[\w/]+\.cpp', manifest)] + [root / 'raw_renderer.cpp']

def compile_one(path):
    output = objects / (str(path.relative_to(root)).replace('/', '_') + '.o')
    if not output.exists() or output.stat().st_mtime < path.stat().st_mtime:
        subprocess.run([str(compiler), *flags, '-c', str(path), '-o', str(output)], check=True, stdout=subprocess.DEVNULL)
    return output

with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    built = list(pool.map(compile_one, inputs))
destination = root.parent / 'app/src/main/jniLibs/arm64-v8a/libcameraraw.so'
destination.parent.mkdir(parents=True, exist_ok=True)
subprocess.run([str(compiler), *flags, '-shared', '-static-libstdc++', '-Wl,-z,max-page-size=16384', '-Wl,--no-undefined', *map(str,built), '-ljnigraphics', '-llog', '-lz', '-o', str(destination)], check=True)
subprocess.run([str(toolchain / 'bin/llvm-strip'), '--strip-unneeded', str(destination)], check=True)
print(destination, destination.stat().st_size)
