#!/usr/bin/env bash
# Developer helper: runs the real client under Xvfb with frame dumps. Usage: ./runclient.sh <seconds> <dumpEvery> [extra -D args]
set -u
secs="${1:-120}"; every="${2:-60}"; shift 2 || true
out="${DX_OUT:-/tmp/dxrender-run}"
rm -rf "$out/frames"; mkdir -p "$out"
export JAVA_HOME="${DX_JAVA_HOME:-/opt/jdk25/jdk-25.0.5+8}"
cd "$(dirname "$0")"
timeout "$secs" xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -PdxVmArgs="-Ddxrender.dumpDir=$out/frames -Ddxrender.dumpEvery=$every -Djava.awt.headless=true $*" > "$out/run.log" 2>&1
echo "exit $?" >> "$out/run.log"
