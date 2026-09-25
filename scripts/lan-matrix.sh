#!/usr/bin/env bash
# Gate A0.2 emulator row for Android 16: runs LanMatrixTest with RESTRICT_LOCAL_NETWORK disabled,
# then enabled, rebooting after each toggle. Managed devices cannot reboot between boot and test.
set -euo pipefail
source "$(dirname "$0")/avd-lib.sh"
PKG=io.github.tuthan.steamoscompanion
RUNNER="$PKG.test/androidx.test.runner.AndroidJUnitRunner"
IMAGE="system-images;android-36;google_apis;x86_64"
PORT=47989
LOG="$LOG_DIR/lan-matrix-$(date -u +%Y%m%dT%H%M%SZ).log"

"$ROOT/gradlew" -p "$ROOT" --no-daemon -q :app:assembleBaselineDebug :app:assembleBaselineDebugAndroidTest

# The emulator reaches the development machine's loopback as 10.0.2.2.
python3 -c "
import socket
s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
s.bind(('127.0.0.1', $PORT)); s.listen(8)
while True: s.accept()[0].close()
" &
LISTENER=$!

avd_ensure sc-api36 "$IMAGE"
avd_boot sc-api36 5582
trap 'avd_stop; kill $LISTENER 2>/dev/null || true' EXIT

APKS="$ROOT/app/build/outputs/apk"
"$ADB" -s "$SERIAL" install -r -t "$APKS/baseline/debug/app-baseline-debug.apk" >/dev/null
"$ADB" -s "$SERIAL" install -r -t "$APKS/androidTest/baseline/debug/app-baseline-debug-androidTest.apk" >/dev/null
echo "device: $(avd_fingerprint)" | tee "$LOG"

status=0
for state in disable enable; do
  "$ADB" -s "$SERIAL" shell am compat "$state" RESTRICT_LOCAL_NETWORK "$PKG" | tee -a "$LOG"
  "$ADB" -s "$SERIAL" reboot
  avd_wait_boot
  expect=$([ "$state" = enable ] && echo true || echo false)
  echo "== RESTRICT_LOCAL_NETWORK ${state}d, expectRestricted=$expect" | tee -a "$LOG"
  out=$("$ADB" -s "$SERIAL" shell am instrument -w \
    -e class "$PKG.android.LanMatrixTest" \
    -e lanHost 10.0.2.2 -e lanPort "$PORT" -e expectRestricted "$expect" "$RUNNER" | tr -d '\r')
  echo "$out" >>"$LOG"
  "$ADB" -s "$SERIAL" logcat -d -s LanMatrix:I | tail -n 1 | tee -a "$LOG"
  if grep -q '^OK (1 test)' <<<"$out"; then echo "PASS" | tee -a "$LOG"; else echo "FAIL" | tee -a "$LOG"; status=1; fi
done
echo "log: $LOG"
exit $status
