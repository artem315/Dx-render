#!/usr/bin/env bash
# Developer helper: runs the real client under Xvfb. Usage: ./runclient.sh <seconds> [extra -D args]
# Screenshots taken by the self-test are copied to $DX_OUT/shots, the log to $DX_OUT/run.log.
set -u
secs="${1:-120}"; shift || true
out="${DX_OUT:-/tmp/dxrender-run}"
rm -rf "$out/shots" "$out/frames"; mkdir -p "$out"
export JAVA_HOME="${DX_JAVA_HOME:-/opt/jdk25/jdk-25.0.5+8}"
cd "$(dirname "$0")"
rm -rf run/screenshots
timeout "$secs" xvfb-run -a -s "-screen 0 1280x720x24" ./gradlew runClient -PdxVmArgs="-Ddxrender.dumpDir=$out/frames -Ddxrender.dumpEvery=${DX_DUMP_EVERY:-100000} -Djava.awt.headless=true $*" > "$out/run.log" 2>&1
echo "exit $?" >> "$out/run.log"
mkdir -p "$out/shots"; cp run/screenshots/*.png "$out/shots/" 2>/dev/null || true
