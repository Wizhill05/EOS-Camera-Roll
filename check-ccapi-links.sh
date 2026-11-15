#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
json_test_jar="${JSON_TEST_JAR:-}"
if [[ -z "$json_test_jar" ]]; then
    for candidate in "${GRADLE_USER_HOME:-$HOME/.gradle}"/caches/modules-2/files-2.1/org.json/json/*/*/*.jar; do
        if [[ -f "$candidate" ]]; then json_test_jar="$candidate"; break; fi
    done
fi
if [[ ! -f "$json_test_jar" ]]; then
    echo 'Set JSON_TEST_JAR to an org.json:json JAR for the host checks. Android uses its built-in JSON parser.' >&2
    exit 1
fi
mkdir -p build/ccapi-links-checks
javac -cp "$json_test_jar" -d build/ccapi-links-checks app/src/main/java/dev/aryan/cameralinklab/CcapiLinks.java tests/CcapiLinksChecks.java
java -cp "build/ccapi-links-checks:$json_test_jar" dev.aryan.cameralinklab.CcapiLinksChecks
