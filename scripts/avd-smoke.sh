#!/usr/bin/env bash
# Runs the instrumented suite on a plain AVD, for API levels below the managed-device floor.
# Usage: scripts/avd-smoke.sh 26 "system-images;android-26;google_apis;x86_64"
set -euo pipefail
source "$(dirname "$0")/avd-lib.sh"
api="$1" image="$2" name="sc-api$1"

avd_ensure "$name" "$image"
avd_boot "$name" 5580
trap avd_stop EXIT
echo "device: $(avd_fingerprint)"
ANDROID_SERIAL="$SERIAL" "$ROOT/gradlew" -p "$ROOT" --no-daemon :app:connectedBaselineDebugAndroidTest \
  | tee "$LOG_DIR/avd-smoke-api$api.log"
