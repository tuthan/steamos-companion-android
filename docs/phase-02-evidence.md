# Phase 02 implementation and validation evidence

Date: 2026-09-26

## Implemented

- Implemented the bootstrap transport with Bouncy Castle TLS 1.2, certificate pin verification before application bytes, and RFC 5929 `tls-unique` export. The request deadline and cancellation path close the socket when local-network access is revoked.
- Added an independent CPython/OpenSSL TLS reference fixture. The JVM test verifies that the production channel-binding bytes match Python's `tls-unique` value.
- Extended the instrumented fake host and production pairing path to cover nonce and QR payload pairing. Tests verify the host-derived comparison code, binding header, returned credential persistence, TLS version/cipher properties, full-handshake behavior, and rejection of a wrong pin before HTTP bytes.
- Added runtime permission policy for API 26–37 and both target SDKs. API 36 can request nearby-device access once for a user-started action and still allows one bounded attempt after denial; API 37/target 37 blocks until `ACCESS_LOCAL_NETWORK` is granted; API 37/target 36 retains implicit access. Revocation cancels in-flight transport.
- Added target-36 and target-37 merged-manifest checks. `NEARBY_WIFI_DEVICES` is limited to API 36 with `neverForLocation`; only target 37 declares `ACCESS_LOCAL_NETWORK`.
- Added `CompanionApplication` and `CompanionRuntime` to compose the production state store, transport, pairing coordinator, discovery scanner, and local-network adapter. Release keep rules retain this graph until screens call it directly. The shipping R8 APKs retain Bouncy Castle TLS and are substantially smaller than the unminified debug APK.

`LocalNetworkAccess` exposes callbacks for a visible UI to request permission or open app settings. No production Activity currently invokes them, and no permission prompt can be opened at startup or from background transport.

## Validation

The commands ran from the repository root using the installed Android SDK, `ANDROID_USER_HOME=/tmp/phase02-android`, and `GRADLE_USER_HOME=$PWD/build/phase02-gradle-home`.

```text
./gradlew --no-daemon --write-locks check :app:assembleBaselineDebugAndroidTest :app:assembleTarget37DebugAndroidTest
BUILD SUCCESSFUL
Core checks/tests, app lint and manifest checks, and both debug instrumentation APKs passed.

Android emulator matrix (direct API 37 Google APIs AVD plus API 26/28/35/36 AVDs)
API 26 / target 36: 29 tests, 0 failures
API 26 / target 37: 29 tests, 0 failures
API 28 / target 36: 29 tests, 0 failures
API 28 / target 37: 29 tests, 0 failures
API 35 / target 36: 29 tests, 0 failures
API 35 / target 37: 29 tests, 0 failures
API 36 / target 36: 29 tests, 0 failures
API 36 / target 37: 29 tests, 0 failures
API 37 / target 36: 29 tests, 0 failures
API 37 / target 37: 29 tests, 0 failures

API 36 LAN compatibility matrix
RESTRICT_LOCAL_NETWORK disabled: private-address TCP probe connected.
RESTRICT_LOCAL_NETWORK enabled: probe timed out at 3.052 seconds; bounded attempt passed.

./gradlew --offline --no-daemon :app:assembleBaselineRelease :app:assembleTarget37Release
BUILD SUCCESSFUL
Both minified release APKs assembled. Baseline target-36 APK: 553,672 bytes;
target-37 APK: 553,704 bytes (32-byte variant delta). The matching debug APKs
are 4,412,296 bytes and 4,416,766 bytes, respectively.

Target-37 release-configured R8 probe, API 37 emulator: 3 tests, 0 failures.
Wrong-pin alert-before-bytes, nonce pairing, and QR payload pairing all passed.
```

The R8 probe used `:app:assembleTarget37R8Probe :app:assembleTarget37DebugAndroidTest`, installed both APKs with `adb -s emulator-5554 install -r -t`, then ran:

```text
adb -s emulator-5554 shell am instrument -w -r \
  -e class io.github.tuthan.steamoscompanion.android.Phase02PairingGateTest \
  io.github.tuthan.steamoscompanion.test/androidx.test.runner.AndroidJUnitRunner
```

The `check` run includes the Python binding comparison, TLS 1.3-only failure, permission policy matrix, and transport cancellation cases. Each initial pairing request in the instrumented tests must finish inside six seconds; the emulator result does not establish physical-device timing.

The normal emulator suites skip the raw private-address LAN probe because it needs an explicit compatibility state and expected outcome; the API 36 restriction matrix above runs that probe separately. The saved direct-suite summaries are in `build-logs/api*.txt`; the API 36 compatibility results are in `build-logs/lan-matrix-20260926T032718Z.log`. The R8 probe result is in `build-logs/r8-target37-probe-pairing.txt`.

The shipping release APK was also locally signed and installed to try the instrumentation APK against the exact target-37 R8 output. AndroidX instrumentation crashed before test discovery with `NoClassDefFoundError: kotlin.jvm.internal.Intrinsics`, which R8 had removed or renamed for the standalone test classloader. The `r8Probe` variant uses the same release/minify configuration and production keep rules, with test-only Kotlin and Bouncy Castle symbol keeps; its three pairing checks passed in 1.079 seconds. This exercises the minified production path but does not claim that the exact unsigned shipping APK completed the AndroidX instrumentation run.

## Gate boundary

- No physical Android phone or Steam Deck was attached. The live pairing against the pinned SteamOS host v0.5.16, physical API 26–28 and API 36/37 checks, and real Wi-Fi/Ethernet behavior remain open. Emulator runs do not verify OEM permission dialogs, hardware-backed Keystore behavior, network changes, Wake, or physical timing.
- API 36 grant/denial and API 37 target-37 missing-permission behavior are covered by emulator tests. The production request and Settings callbacks still need an onboarding Activity to call them; foreground re-entry and network-change handling are not integrated with a screen or poller yet.
- Phase 00 recovery acceptance remains pending. Phase 01 still lacks the physical device roster and the Decky fixture push, so its A0.3 gate is not closed.
- The external Phase 02 vault note remains `pending`. Its exit requires the upstream gates and physical evidence above. No status change was made in the external vault.
