#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p build/protocol-checks
javac -d build/protocol-checks app/src/main/java/dev/aryan/cameralinklab/PtpIp.java app/src/main/java/dev/aryan/cameralinklab/CameraHttp.java tests/ProtocolChecks.java
java -cp build/protocol-checks dev.aryan.cameralinklab.ProtocolChecks
