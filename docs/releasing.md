# Releasing

Every deliverable app build is published to this repository's GitHub Releases. Keep the Android package and signing identity stable so installed versions can update in place.

1. Update `versionCode` and `versionName` in app/build.gradle. Add docs/releases/VERSION.md with user-facing changes and validation limitations.
2. Commit the code, tests and release notes.
3. Push `main`. The Build and publish APK workflow runs Android build, lint, Robolectric workflows and host protocol/media checks, then publishes vVERSION with the APK and SHA256SUMS.
4. Verify the published APK checksum and signer before sharing the release link.

A duplicate published version fails before another build is released. Bump the version for a new app change. Documentation-only pushes do not build another APK. `workflow_dispatch` also invokes the pipeline. CI uses the checked-in, pinned native library; native code changes must regenerate and commit it using native/build-native.py first.

For a local release from a clean committed source tree:

```bash
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
./scripts/release.sh
```

The script refuses duplicate versions and builds/checks before publishing. It uses the existing local development key unless EOS_KEYSTORE_PATH and the signing variables are set. A fresh machine's default debug key will not update existing installations.

GitHub Actions uses the repository secret EOS_RELEASE_KEYSTORE_BASE64. This is the update-compatible key encoded as base64, not a public artifact. Only the workflow restores it into a runner temporary file. Never commit keystores or print secret contents. The repository owner configured this secret during initial setup. Its default Android development-key alias/password are passed separately in workflow environment; the private key itself remains a GitHub secret.

New repositories/forks must configure their own signing secret before running release builds. Their keys cannot update an installation signed by this repository's key. Build/lint/test jobs do not require a signing secret; publishing does.
