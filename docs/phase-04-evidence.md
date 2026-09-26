# Phase 04 implementation and validation evidence

Date: 2026-09-26 (audit correction run)

Code: committed with this evidence; see the Phase 04 commit in `git log`.

Protocol source: Decky fixture commit `804ebf1864f391358183dfcd1cacf61e289862e8`, protocol tree `4b661630f69bf7f2752862112f7f35288f571b18`; host runtime behavior remains `v0.5.16` (`9c213b31cdce36e67784355be21e2fc1b2b7570f`). See [`SOURCE.json`](../protocol/SOURCE.json).

## Implemented

- Added versioned state serialization (current schema v3) and a bounded `SCAS` envelope with an app-owned key alias, AES-256/GCM, provider-generated 96-bit IVs, 128-bit authentication tags, and fixed AAD. The complete active host, candidate, staged credentials, and operation journal are encrypted together. Known v0/v1/v2 states migrate to v3; legacy-code candidates are discarded during migration.
- Added an Android Keystore cipher that does not create a key while decrypting, does not require user authentication, and distinguishes missing, invalidated, temporarily unavailable, and authentication-failed keys. It can report hardware backing when the provider exposes `KeyInfo`.
- Added an app-private `AtomicFile` adapter for `filesDir/companion-state.bin`, size-bounded reads, cleanup of AtomicFile sidecars, and path-shared locking. Core serializes transactions across repository instances because AtomicFile itself does not lock.
- Added validated load/commit/reset results, a known-empty-v0 migration, refusal to overwrite newer schemas, explicit corruption/key-loss states, and recoverable I/O failures.
- Added generation-checked pairing transactions for replacement, cancellation, acceptance, Forget, and reset. An approved staged credential cannot be silently replaced, cancelled, changed, or removed by Forget; its discard path first commits a self-revocation journal record and then attempts revocation with the staged credential.
- Added the operation journal for the pinned power, display, Sunshine, and self-revocation routes. It commits `prepared` before invoking the sender, retains the exact encoded body and SHA-256, persists returned operation IDs, marks recovered `prepared` entries unresolved, checks host/client/endpoint/pin/operation/request identity during reconciliation, and never replays an ambiguous mutation. Historical entries deliberately are not cross-checked against the active host, so they survive re-pairing the same host with another client ID. Capacity handling checks the exact encoded next state against both count and 64 KiB limits, preserves unresolved entries until acknowledgement, and rejects before dispatch with `HISTORY_FULL` when no entry can be pruned.
- Active-host state stores the last authenticated status `boot_id`; `ClientCore.recordAuthenticatedStatus` persists it against the exact host snapshot, and each prepared journal entry copies that observed ID. Entries created before a status observation keep a null boot ID.
- Added tests for full-disk/write failures, exact bytes, missing operation IDs, lost results, history capacity, terminal pruning, stale callbacks, candidate replacement/Forget, self-revocation, authentication-tag corruption, missing keys, schema migration, interrupted AtomicFile writes, and explicit reset.
- Excluded credential-protected and device-protected app data from legacy backup, cloud backup, and device transfer. `allowBackup` remains false. Updated only the existing Gradle lock configuration entries for release variants; dependency versions did not change.

## Factcheck notes

- Android recommends Android Keystore for app cryptographic keys and authenticated encryption such as AES/GCM. `KeyGenParameterSpec` supports GCM, randomized encryption, and disabling user-authentication requirements; the provider supplies the IV. [Android cryptography guidance](https://developer.android.com/privacy-and-security/cryptography), [`KeyGenParameterSpec`](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec).
- Hardware-backed key storage is optional for the API 26 minimum. The managed emulators reported `hardwareBacked=false` on APIs 27, 28, 35, 36, and 37; this records emulator software-backed keys, not physical-device capability.
- `AtomicFile.finishWrite` commits the new file and `failWrite` abandons it; AtomicFile does not provide locking. Its implementation has used a legacy `.bak` rollback path on older Android and a `.new` write-and-rename path on current Android. Instrumentation exercised rollback on API 27 and `.new` recovery on API 35, and the complete persistence suite passed on both families. [Android `AtomicFile` API](https://developer.android.com/reference/android/util/AtomicFile), [AOSP `AtomicFile` source](https://android.googlesource.com/platform/frameworks/base/%2B/HEAD/core/java/android/util/AtomicFile.java).
- `allowBackup=false` does not reliably exclude device-to-device transfer on every Android/OEM combination. Both backup resource files explicitly exclude root, file, database, shared-preference, external, and device-protected domains. [Android Auto Backup](https://developer.android.com/identity/data/autobackup).
- At the pinned host revision, the server retains at most 256 operation records and may prune terminal `unknown` records; it does not guarantee a minimum deduplication lifetime. The client therefore does not infer a retry window and never resends an unresolved request.

## Validation run

Commands, from the repository root:

```text
GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline :core:check :app:compileBaselineDebugKotlin :app:compileBaselineDebugAndroidTestKotlin :app:checkMergedManifests :app:assembleBaselineRelease :app:assembleTarget37Release
BUILD SUCCESSFUL
core JVM suite: 187 tests, 0 failures, 0 errors, 0 skipped
core Android-import boundary verifier: passed
all baseline/target37 debug/release merged manifest checks: passed
baseline and target37 release APK assembly, including lint vital: passed

GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline :app:phase01GroupTarget37DebugAndroidTest
BUILD SUCCESSFUL
managed APIs 27, 28, 35, 36, and 37: 19 tests each, 0 failures, 0 errors, 1 skipped
each API: 6 Android persistence tests and 8 Phase 03 transport tests passed; the existing emulator LAN reachability test was skipped

git diff --check
passed
```

The Android tests use temporary app-cache files so they do not touch a live pairing. They also assert that the production adapter points to `filesDir/companion-state.bin`. No API 26 emulator or physical handset was available in this run; physical hardware backing and the existing A0 LAN/TLS/permission acceptance gates remain open. No mutation UI is connected in this phase.

## Audit correction verification — 2026-09-26

```text
GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline :core:test :app:testBaselineDebugUnitTest :app:compileBaselineDebugAndroidTestKotlin
BUILD SUCCESSFUL
core JVM suite: 234 tests, 0 failures, 0 errors, 0 skipped
baseline app JVM suite: 20 tests, 0 failures, 0 errors, 0 skipped
Android instrumentation sources compiled; runtime execution unavailable because adb is not installed

git diff --check
passed
```

The size-boundary test proves that an oversized proposed state returns `HISTORY_FULL` before the sender is called. The boot-ID test covers authenticated observation through `ClientCore` and capture in the prepared journal entry. The first-write `.first-write` marker treats an interrupted, invalid initial AtomicFile envelope as empty only when no backup exists; its Android runtime test compiled but could not run here because `adb` is unavailable. DataStore preferences and diagnostics export remain unimplemented and are recorded as later-phase work.
