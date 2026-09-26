# Phase 07 implementation record

Status: in progress. This file records code and test evidence, not A3 exit evidence.

Source plan: `steamos-companion/android/phases/07-live-onboarding-and-status.md` in the documentation vault. Starting repository commit: `d02533df77757cd1487120f688a2531e7ad3eb3c`. Vendored protocol source: `804ebf1864f391358183dfcd1cacf61e289862e8`, host behavior v0.5.16 at `9c213b31cdce36e67784355be21e2fc1b2b7570f`.

## Implemented so far

- A paired status reader uses the saved endpoint, certificate pin, and bearer. It verifies the response host ID, checks that the active pairing is still the one used for the request, and persists a changed boot ID. Outputs and display order are read only after a successful status read.
- The reader now tries active LAN paths in order, preferring the stored Wake interface when present. If no path succeeds and one reports a certificate mismatch, the result requires re-pairing. Dependent output/order reads use the same successful path.
- The single foreground `StatusPoller` has a two-second success cadence and six-second failure cadence after each completed cycle. Manual refresh coalesces during an in-flight read. Stopping cancels its work and clock. A one-second monotonic tick marks a read stale at 15 seconds; unreachable takes headline precedence without erasing stale state.
- `PairedGraphSession` now owns exactly one poller and network monitor, exposes `onStart`/`onStop` for the paired graph lifecycle, and delivers state and Wake-interface notices on the Android main thread. The actual Compose ViewModel still needs to create and drive this session.
- A paired Android network callback invalidates discovery and requests one fresh status read. It exposes a hook to re-evaluate the Wake interface. It sends no mutation.
- The network monitor deduplicates the initial capability/link callbacks for each available network, then reacts to actual transport or link-address changes and losses. This prevents a single registration burst from repeatedly cancelling its own fresh read.
- Connection repair probes a proposed address without a credential, compares both the announced host ID and certificate fingerprint to the saved pairing, and atomically updates only the endpoint if both match. A mismatch leaves the existing credential and endpoint intact.
- The production runtime exposes the existing crash-safe operation journal, a pinned mutation sender, connection repair/removal services, and a local alias store. Revoke is journaled for the exact selected pairing; a lost reply keeps the credential and an `UNKNOWN` entry. Explicit Forget removes local credentials and that pairing's journal context. The alias store holds only a display name keyed by a digest of host/client identity; it never stores a credential.
- The journal now checks the returned operation's request ID and the self-revoke operation kind before it can mark a revoke successful and clear the paired credential. Mismatched replies remain unresolved.
- Android's monotonic clock now uses `SystemClock.elapsedRealtimeNanos()`, including device sleep.
- Transport replies carry an ephemeral request/response monotonic interval and host `Date` header. `HostDeadlineAnchor` conservatively accounts for HTTP Date's one-second precision and the full round trip, rounds countdowns down, and returns no anchor when timing is missing or implausible. No elapsed-realtime value is persisted.
- The pairing coordinator now bounds its wall-time checks with elapsed time and the greatest time it has already observed in this process, so setting the phone clock back cannot extend a pending pairing. A fresh timed pending reply supplies an in-memory host deadline anchor; restoring a pending request supplies no anchor until another host read.
- The approved ZXing Core 3.5.4 decoder is in the app catalog/lockfile with SHA-256 verification metadata. `QrFrameDecoder` extracts padded Y-plane frames and limits work to QR codes; the platform Camera2 adapter accepts a preview surface, requires an existing CAMERA grant, decodes off the main thread, and stops after one payload. This avoids adding the unreviewed CameraX candidate. The preview surface, permission launcher, and payload handoff still need a production screen.
- Camera2 callbacks now claim their device/session under the same lock as scanner shutdown, so closing the scanner cannot leave a late callback's camera resource open. A queued QR result is discarded if its owner closed before delivery.

## Remaining plan requirements

- Phase 06 production Compose screens, ViewModels, screenshot and accessibility gates are incomplete; their dependency review is pending an explicit human disposition. The paired session has lifecycle hooks but no ViewModel or rendered UI calls them yet.
- A fresh read-only Socket lookup of `pkg:maven/androidx.compose/compose-bom@2026.09.00` on 2026-09-26 again returned `404 / no result` for deep scores. No repository data was uploaded and no dependency was added. The dependency-guard decision remains `block_pending_human_review`.
- Live discovery, manual address, QR capture/paste, candidate details, both pairing variants, approval outcomes, alias/address/find-again, revoke/Forget, and runtime permission launchers are not connected to screens. Find-again, local-alias, revoke, and Forget primitives are present, but their UI is not.
- The camera adapter has not been exercised with a physical camera. The current minified release build strips the scanner and ZXing because no screen calls them, so the APK cannot yet establish the scanner's release-size cost.
- The host deadline anchor is not yet consumed by the pairing/preview UI. After process restoration the UI must show Checking, obtain a fresh host deadline, and disable deadline-sensitive confirmation when no anchor can be made.
- Physical-phone pairing, status and revoke, process/network interruption, permission, and failure-matrix checks have not run. A3 cannot pass without that device evidence.
- On 2026-09-26, the SDK's `adb devices -l` listed only `emulator-5554` (`sdk_gphone64_x86_64`); no physical phone was attached for the required A3 run.

## Local verification

- `./gradlew --offline :core:test --tests '*StatusPollerTest' :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` — passed.
- `./gradlew --offline :core:test :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest :app:checkMergedManifests` — passed. Both target-36 and target-37 debug/release/R8 probe manifest checks passed.
- `git diff --check` — passed before this record was added; it is rerun before commit.
- `./gradlew --offline :core:test --tests '*ConnectionRepairTest' --tests '*StatusPollerTest'` — passed.
- `./gradlew --offline :core:test :app:compileBaselineDebugKotlin :app:compileTarget37DebugKotlin` — passed after the repair/removal and deadline-anchor changes.
- `./gradlew --offline :core:test :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` — passed after the monotonic pairing-deadline change and the no-background-polling assertion.
- `./gradlew --offline :app:compileBaselineDebugKotlin :app:compileTarget37DebugKotlin` — passed after the network callback update.
- `./gradlew --offline :app:compileBaselineDebugKotlin :app:compileTarget37DebugKotlin` — passed after the multi-path status reader update.
- `./gradlew --offline :app:compileBaselineDebugKotlin :app:compileTarget37DebugKotlin` — passed after adding the paired-graph lifecycle session.
- `./gradlew --offline :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest :app:assembleBaselineRelease :app:assembleTarget37Release` — passed with QR decoding over padded and interleaved camera Y planes plus a blank frame. Both minified release variants passed lint; R8's usage reports show ZXing removed, so the APKs cannot establish scanner-size cost.
- The same app test and both release-build tasks passed after synchronizing Camera2 callback handoff with shutdown. This verifies compilation, JVM decoding, and release lint, but no physical camera behavior.
- `./gradlew --offline :app:api37Target37DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.tuthan.steamoscompanion.android.LocalNetworkPermissionRuntimeTest` — API 37 managed emulator, target37 variant: 6 test methods, 0 failures/errors. Only the API 37 target37 branches actively exercised their permission-specific assertions; other conditional methods returned early.
- `./gradlew --offline :app:api37Target37DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.tuthan.steamoscompanion.android.Phase02PairingGateTest` — API 37 managed emulator, target37 variant: 3 tests, 0 failures/errors; nonce and payload pairing plus pin mismatch against a loopback fake host.
- `./gradlew --offline :core:test --tests '*OperationJournalTest'` — passed after validating revoke operation identity before credential removal.
- `./gradlew --offline :app:api37Target37DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.tuthan.steamoscompanion.android.Phase07StatusGatewayTest` — API 37 managed emulator, target37 variant: 4 tests, 0 failures/errors; pinned status, dependent reads, boot ID persistence, pin change, confirmed journaled revoke, and lost-reply revoke followed by explicit local Forget against a loopback fake host. The XML result is under `app/build/outputs/androidTest-results/managedDevice/debug/flavors/target37/api37/`.

The `connectedTarget37DebugAndroidTest` task was also attempted, but it reported zero tests and failed because this emulator is a Gradle Managed Device. Its result is not test evidence; the managed-device runs above executed the methods.

These are JVM/build checks, not live-device or rendered-screen evidence.
