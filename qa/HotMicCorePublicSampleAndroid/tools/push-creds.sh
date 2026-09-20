#!/usr/bin/env bash
# Push QA credentials into the sample's private files dir (debuggable app → `run-as`), so they are
# never baked into the APK, never on the `am start` command line, and never in logcat.
# Usage: tools/push-creds.sh <env-file> [extra KEY=VALUE ...]
set -euo pipefail
ENV_FILE="${1:?env file}"; shift || true
PKG=io.hotmic.core.publicsample
TMP=$(mktemp)
grep -E '^(HOTMIC_CORE_API_KEY|HOTMIC_CORE_ACCESS_TOKEN|HOTMIC_CORE_LIVE_STREAM_ID|HOTMIC_CORE_TEST_USER_ID)=' "$ENV_FILE" > "$TMP"
for kv in "$@"; do echo "$kv" >> "$TMP"; done
adb push "$TMP" /data/local/tmp/hem96.env >/dev/null
adb shell "run-as $PKG sh -c 'mkdir -p files && cat /data/local/tmp/hem96.env > files/hem96.env && chmod 600 files/hem96.env'"
adb shell rm /data/local/tmp/hem96.env
rm -f "$TMP"
echo "pushed $(adb shell run-as $PKG wc -l files/hem96.env | awk '{print $1}') keys to $PKG/files/hem96.env"
