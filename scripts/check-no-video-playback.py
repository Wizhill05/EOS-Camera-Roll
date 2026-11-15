#!/usr/bin/env python3
"""Reject playback code and HTTP Range headers in the actual release DEX files."""
import os
import re
import subprocess
import tempfile
from pathlib import Path
import struct
import sys
import zipfile

forbidden = {
    b'Landroid/media/MediaPlayer;',
    b'Landroid/media/MediaDataSource;',
    b'Landroid/media/MediaMetadataRetriever;',
    b'Landroidx/media3/exoplayer/ExoPlayer;',
    b'Ldev/aryan/cameralinklab/RemoteMediaFile;',
}


def dex_strings(data):
    if not data.startswith(b'dex\n'):
        raise ValueError('Expected a standard DEX file')
    count, table = struct.unpack_from('<II', data, 0x38)
    for index in range(count):
        offset = struct.unpack_from('<I', data, table + index * 4)[0]
        while data[offset] & 0x80:
            offset += 1
        offset += 1
        yield data[offset:data.index(b'\0', offset)]


apk = sys.argv[1] if len(sys.argv) > 1 else 'app/build/outputs/apk/release/app-release.apk'
with zipfile.ZipFile(apk) as archive:
    dex_files = [name for name in archive.namelist() if name.startswith('classes') and name.endswith('.dex')]
    if not dex_files:
        raise SystemExit('FAIL: APK has no DEX files')
    found = set()
    sdk = Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'android-sdk')))
    dexdump = sdk / 'build-tools/36.0.0/dexdump'
    if not dexdump.is_file():
        raise SystemExit('FAIL: Android build-tools 36.0.0 dexdump is required')
    for name in dex_files:
        data = archive.read(name)
        found.update(forbidden.intersection(dex_strings(data)))
        # AndroidX also uses Range in Kotlin annotations, so check executable constants.
        with tempfile.TemporaryDirectory(prefix='eos-dex-check-') as temporary:
            dex = Path(temporary) / name
            dex.write_bytes(data)
            process = subprocess.Popen([str(dexdump), '-d', str(dex)], stdout=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
            for line in process.stdout:
                if re.search(r'\bconst-string(?:/jumbo)?\s.*"Range"\s*//', line):
                    found.add(b'HTTP Range header constant')
            if process.wait() != 0:
                raise SystemExit('FAIL: could not inspect executable DEX instructions')
    if found:
        raise SystemExit('FAIL: removed video playback code remains: ' + ', '.join(value.decode() for value in sorted(found)))
print('PASS: release APK contains no video player, video data source, video metadata retriever, or HTTP Range header')
