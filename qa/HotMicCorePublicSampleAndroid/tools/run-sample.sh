#!/usr/bin/env bash
# Launch the sample with a scripted row list, wait for `AUTORUN COMPLETE`, save the redacted HEM96 log.
# Usage: tools/run-sample.sh <rows> <out.log> [timeout-s] [extra --es KEY VALUE ...]
set -euo pipefail
ROWS="${1:?rows}"; OUT="${2:?out}"; TIMEOUT="${3:-600}"; shift 3 || shift $#
PKG=io.hotmic.core.publicsample
adb logcat -c
adb shell am force-stop $PKG
adb shell am start -W -n $PKG/.StreamsActivity --es HEM96_AUTORUN "$ROWS" "$@" >/dev/null
START=$(date +%s)
while true; do
  adb logcat -d -s HEM96 -v time > "$OUT.raw"
  if grep -q "AUTORUN COMPLETE" "$OUT.raw"; then break; fi
  if [ $(( $(date +%s) - START )) -ge "$TIMEOUT" ]; then echo "TIMEOUT after ${TIMEOUT}s" >> "$OUT.raw"; break; fi
  sleep 5
done
# Belt and braces: redact anything JWT-shaped before the log leaves the emulator capture.
sed -E 's/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}/<redacted-jwt>/g' "$OUT.raw" > "$OUT"
rm -f "$OUT.raw"
echo "saved $OUT ($(wc -l < "$OUT") lines): $(grep -c 'ROW ' "$OUT") ROW lines"
