# Shared helpers for plain-AVD runs (API 26 and the API 36 LAN matrix). Source, don't execute.
# Requires ANDROID_HOME and a JDK 17 JAVA_HOME (see mise.toml).

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
ADB="$ANDROID_HOME/platform-tools/adb"
EMULATOR="$ANDROID_HOME/emulator/emulator"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="$ROOT/build-logs"
mkdir -p "$LOG_DIR"

avd_ensure() { # name system-image
  if ! "$EMULATOR" -list-avds | grep -qx "$1"; then
    echo no | "$AVDMANAGER" create avd --name "$1" --package "$2" --device pixel_6 --force >/dev/null
  fi
}

avd_boot() { # name port -> sets SERIAL
  SERIAL="emulator-$2"
  "$EMULATOR" -avd "$1" -port "$2" -no-window -no-audio -no-snapshot -no-boot-anim \
    -gpu swiftshader_indirect >"$LOG_DIR/emulator-$1.log" 2>&1 &
  EMULATOR_PID=$!
  avd_wait_boot
}

avd_wait_boot() {
  "$ADB" -s "$SERIAL" wait-for-device
  local deadline=$((SECONDS + 300))
  until [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
    [ "$SECONDS" -lt "$deadline" ] || { echo "boot timed out on $SERIAL" >&2; return 1; }
    sleep 2
  done
}

avd_stop() {
  "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
  wait "$EMULATOR_PID" 2>/dev/null || true
}

avd_fingerprint() {
  "$ADB" -s "$SERIAL" shell getprop ro.build.fingerprint | tr -d '\r'
}
