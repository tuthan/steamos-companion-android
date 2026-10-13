# SteamOS Companion for Android

The Android client for SteamOS Companion. Application ID `io.github.tuthan.steamoscompanion`.

- `:core` — Kotlin/JVM protocol, pairing, transport, discovery, Wake, and client state. No Android imports.
- `:app` — Android adapters, permissions, storage, lifecycle, and Compose UI. Depends on `:core`.

The host contract is the exact Decky protocol tree recorded in `protocol/SOURCE.json`, copied unchanged. Builds and tests consume only that checked-in bundle, never a sibling checkout. `docs/protocol-coverage.md` maps every pinned route and case to its fixture, HTTP status, and Kotlin contract test. `docs/build-decision.md` records the exact toolchain, test tiers, and every dependency review.

## Build

JDK 17 comes from `mise.toml`; the Android SDK path goes in `local.properties` (`sdk.dir=...`).

```sh
python3 scripts/verify-protocol-hashes.py      # vendored bundle matches SOURCE.json
python3 scripts/verify-protocol-coverage.py    # inventory rows match vendored fixtures
./gradlew check                                 # JVM tests, :core boundary, merged manifests, lint
./gradlew :app:phase01GroupBaselineDebugAndroidTest :app:api37Target37DebugAndroidTest  # managed devices
scripts/avd-smoke.sh 26 "system-images;android-26;google_apis;x86_64"
scripts/lan-matrix.sh                           # API 36 with RESTRICT_LOCAL_NETWORK off, then on
```

Two flavors share compileSdk 37: `baseline` targets 36 and is the release target; `target37` is the Android 17 permission-test configuration that adds `ACCESS_LOCAL_NETWORK`. Dependencies are locked (`gradle.lockfile`) and verified (`gradle/verification-metadata.xml`); add nothing to `gradle/libs.versions.toml` without a Socket review row in the build decision record.

The client is feature-complete to the planned scope: discovery and both pairing variants, status, Power, Wake (including over a VPN relay), Sunshine recovery, display preview and recovery, display order, pairing replacement, host health, host journal and a redacted diagnostic export. It is verified by JVM tests and emulator runs only. [`CHANGELOG.md`](CHANGELOG.md) lists every feature and fix and what each was verified with; the [Phase 11 acceptance audit](docs/phase-11-progress.md) records the integrated checks and open gates; [`docs/store-release-readiness.md`](docs/store-release-readiness.md) records what a Google Play release still needs. Phase plans live in the documentation vault under `steamos-companion/android/`. No release is signed, no version has been published, and physical-device acceptance evidence is still pending.
