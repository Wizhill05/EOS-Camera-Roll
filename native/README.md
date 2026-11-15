# Native RAW decoder

LibRaw is vendored at commit a9a14b1970d188c25dade81f8326ff00a6e33649 from https://github.com/LibRaw/LibRaw. This project selects its CDDL license option. Complete corresponding source, COPYRIGHT and LICENSE.CDDL are under LibRaw; the same notices ship in the app's assets. Local modifications are limited to the separate JNI adapter, not the vendored source.

The checked-in arm64 library was built with Android NDK r27d for API 29, static C++ runtime, hidden non-JNI symbols and 16 KiB ELF alignment. To rebuild:

```bash
export CAMERA_NDK_TOOLCHAIN=/path/to/android-ndk-r27d/toolchains/llvm/prebuilt/linux-x86_64
python3 native/build-native.py
```

The script uses LibRaw's canonical Makefile.am source list. Output: app/src/main/jniLibs/arm64-v8a/libcameraraw.so. Regenerate this library when changing native code and commit it with the source change before release.

`python3 native/check-raw.py` downloads a public-domain EOS R50 V CR3, verifies its published SHA-256 and exercises the same full-resolution processing settings in a Linux host executable. It verifies model support in the pinned source, not JNI execution on an Android handset.
