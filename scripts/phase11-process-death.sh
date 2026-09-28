#!/usr/bin/env bash
# Run the current encrypted-state/process-death journey on a fresh API 37 emulator.
set -euo pipefail
source "$(dirname "$0")/avd-lib.sh"

AVD_ROOT=$(mktemp -d /tmp/sc-phase11-death.XXXXXX)
export ANDROID_AVD_HOME="$AVD_ROOT"
LOG="$LOG_DIR/phase11-process-death-$(date -u +%Y%m%dT%H%M%SZ).log"

cleanup() {
  if [ -n "${EMULATOR_PID:-}" ]; then
    avd_stop
  fi
  rm -rf -- "$AVD_ROOT"
}
trap cleanup EXIT

"$ROOT/gradlew" -p "$ROOT" --offline --no-daemon \
  :app:assembleTarget37Debug :app:assembleTarget37DebugAndroidTest

avd_ensure sc-phase11-death 'system-images;android-37.0;google_apis;x86_64'
# The SDK image defaults to a 12 GB userdata image, larger than the isolated /tmp volume.
sed -i '/^disk.dataPartition.size=/d' "$AVD_ROOT/sc-phase11-death.avd/config.ini"
printf 'disk.dataPartition.size=4G\n' >>"$AVD_ROOT/sc-phase11-death.avd/config.ini"
avd_boot sc-phase11-death 5580

APP="$ROOT/app/build/outputs/apk/target37/debug/app-target37-debug.apk"
TEST="$ROOT/app/build/outputs/apk/androidTest/target37/debug/app-target37-debug-androidTest.apk"
"$ADB" -s "$SERIAL" install "$APP" >/dev/null
"$ADB" -s "$SERIAL" install "$TEST" >/dev/null

{
  echo "device: $(avd_fingerprint)"
  sha256sum "$APP" "$TEST"
} | tee "$LOG"

declare -a pids=()
for stage in seed_preview approve_candidate swap_candidate verify_swap; do
  echo "== $stage" | tee -a "$LOG"
  output=$("$ADB" -s "$SERIAL" shell am instrument -w \
    -e process_death_stage "$stage" \
    io.github.tuthan.steamoscompanion.test/io.github.tuthan.steamoscompanion.android.data.ProcessDeathInstrumentation \
    | tr -d '\r') || true
  printf '%s\n' "$output" >>"$LOG"
  grep -q '^INSTRUMENTATION_STATUS: result=passed$' <<<"$output" || {
    echo "FAILED: $stage did not report passed (see $LOG)" >&2
    exit 1
  }
  pid=$(sed -n 's/^INSTRUMENTATION_STATUS: pid=\([0-9][0-9]*\)$/\1/p' <<<"$output" | tail -1)
  [ -n "$pid" ] || { echo "FAILED: $stage has no PID (see $LOG)" >&2; exit 1; }
  for prior in "${pids[@]}"; do
    [ "$pid" != "$prior" ] || { echo "FAILED: reused PID $pid (see $LOG)" >&2; exit 1; }
  done
  pids+=("$pid")
  if [ "$stage" = verify_swap ]; then
    grep -q '^INSTRUMENTATION_CODE: -1$' <<<"$output" || {
      echo "FAILED: final stage did not finish normally (see $LOG)" >&2
      exit 1
    }
  fi
  echo "PASS $stage pid=$pid" | tee -a "$LOG"
done

echo "log: $LOG"
