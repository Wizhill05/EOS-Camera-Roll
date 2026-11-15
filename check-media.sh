#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
json_test_jar="${JSON_TEST_JAR:-}"
if [[ -z "$json_test_jar" ]]; then
    for candidate in "${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/files-2.1/org.json/json/*/*/*.jar; do
        if [[ -f "$candidate" ]]; then json_test_jar="$candidate"; break; fi
    done
fi
if [[ ! -f "$json_test_jar" ]]; then echo 'Set JSON_TEST_JAR to an org.json:json JAR.' >&2; exit 1; fi
mkdir -p build/media-checks
android_test_jar="${ANDROID_HOME:-$HOME/android-sdk}/platforms/android-36/android.jar"
if [[ ! -f "$android_test_jar" ]]; then echo 'Set ANDROID_HOME to the SDK used to build this app.' >&2; exit 1; fi
sources=app/src/main/java/dev/aryan/cameralinklab
javac -cp "$json_test_jar:$android_test_jar" -d build/media-checks "$sources/CameraHttp.java" "$sources/CcapiLinks.java" "$sources/MediaItem.java" "$sources/MediaCatalog.java" "$sources/MediaKey.java" "$sources/ExifOrientation.java" "$sources/LibraryIssue.java" "$sources/CameraSession.java" tests/GalleryMediaChecks.java
java -cp "build/media-checks:$json_test_jar:$android_test_jar" dev.aryan.cameralinklab.GalleryMediaChecks
