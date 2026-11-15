#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
repo="${EOS_GITHUB_REPOSITORY:-Wizhill05/EOS-Camera-Roll}"
version="$(sed -n "s/.*versionName '\([^']*\)'.*/\1/p" app/build.gradle)"
tag="v${version}"
if gh release view "$tag" --repo "$repo" >/dev/null 2>&1; then
  echo "Release $tag already exists. Bump versionCode and versionName before building another release." >&2
  exit 1
fi
if ! git diff --quiet || ! git diff --cached --quiet; then
  echo "Commit the source before publishing a release." >&2
  exit 1
fi
./gradlew --no-daemon :app:assembleRelease :app:lintRelease :app:testDebugUnitTest
bash check-protocols.sh
bash check-ccapi-links.sh
bash check-media.sh
mkdir -p dist
apk="dist/EOS-Camera-Roll-${version}.apk"
cp app/build/outputs/apk/release/app-release.apk "$apk"
(cd dist && sha256sum "EOS-Camera-Roll-${version}.apk" > SHA256SUMS)
gh release create "$tag" "$apk" dist/SHA256SUMS --repo "$repo" --target "${GITHUB_SHA:-$(git rev-parse HEAD)}" --title "EOS Camera Roll $version" --notes-file "docs/releases/${version}.md" --draft
gh release edit "$tag" --repo "$repo" --draft=false --latest
