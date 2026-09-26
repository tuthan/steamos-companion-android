# Phase 07 implementation record

Status: in progress. This file records code and test evidence, not A3 exit evidence.

Source plan: `steamos-companion/android/phases/07-live-onboarding-and-status.md` in the documentation vault. Starting repository commit: `d02533df77757cd1487120f688a2531e7ad3eb3c`. Vendored protocol source: `804ebf1864f391358183dfcd1cacf61e289862e8`, host behavior v0.5.16 at `9c213b31cdce36e67784355be21e2fc1b2b7570f`.

## Implemented so far

- A paired status reader uses the saved endpoint, certificate pin, and bearer. It verifies the response host ID, checks that the active pairing is still the one used for the request, and persists a changed boot ID. Outputs and display order are read only after a successful status read.
- The single foreground `StatusPoller` has a two-second success cadence and six-second failure cadence after each completed cycle. Manual refresh coalesces during an in-flight read. Stopping cancels its work and clock. A one-second monotonic tick marks a read stale at 15 seconds; unreachable takes headline precedence without erasing stale state.
- A paired Android network callback invalidates discovery and requests one fresh status read. It exposes a hook to re-evaluate the Wake interface. It sends no mutation.
- Connection repair probes a proposed address without a credential, compares both the announced host ID and certificate fingerprint to the saved pairing, and atomically updates only the endpoint if both match. A mismatch leaves the existing credential and endpoint intact.
- The production runtime exposes the existing crash-safe operation journal, a pinned mutation sender, connection repair/removal services, and a local alias store. Revoke is journaled for the exact selected pairing; a lost reply keeps the credential and an `UNKNOWN` entry. Explicit Forget removes local credentials and that pairing's journal context. The alias store holds only a display name keyed by a digest of host/client identity; it never stores a credential.
- Android's monotonic clock now uses `SystemClock.elapsedRealtimeNanos()`, including device sleep.

## Remaining plan requirements

- Phase 06 production Compose screens, ViewModels, screenshot and accessibility gates are incomplete; their dependency review is pending an explicit human disposition. The poller has no paired-graph `ON_START`/`ON_STOP` owner or rendered UI yet.
- Live discovery, manual address, QR capture/paste, candidate details, both pairing variants, approval outcomes, alias/address/find-again, revoke/Forget, and runtime permission launchers are not connected to screens. Find-again, local-alias, revoke, and Forget primitives are present, but their UI is not.
- Host `Date`/round-trip deadline anchoring and countdown rendering remain. A missing or untrusted time source must disable deadline-sensitive confirmation.
- Physical-phone pairing, status and revoke, process/network interruption, permission, and failure-matrix checks have not run. A3 cannot pass without that device evidence.

## Local verification

- `./gradlew --offline :core:test --tests '*StatusPollerTest' :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` — passed.
- `./gradlew --offline :core:test :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest :app:checkMergedManifests` — passed. Both target-36 and target-37 debug/release/R8 probe manifest checks passed.
- `git diff --check` — passed before this record was added; it is rerun before commit.
- `./gradlew --offline :core:test --tests '*ConnectionRepairTest' --tests '*StatusPollerTest'` — passed.

These are JVM/build checks, not live-device or rendered-screen evidence.
