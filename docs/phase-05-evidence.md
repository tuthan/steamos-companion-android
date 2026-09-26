# Phase 05 implementation and validation evidence

Date: 2026-09-26

Code: initial implementation is in the Phase 05 commit; the audit corrections and evidence are committed separately.

Protocol contract: Decky fixture commit `804ebf1864f391358183dfcd1cacf61e289862e8`, protocol tree `4b661630f69bf7f2752862112f7f35288f571b18`, with host runtime behavior v0.5.16 (`9c213b31cdce36e67784355be21e2fc1b2b7570f`). The fixture commit adds the deterministic host QR payload and bundles it; the Android pin records it in [`SOURCE.json`](../protocol/SOURCE.json). Omarchy comparison source is release 0.5.14 at [`5c7c9611156aa4cad7e10f3879d51ea7f7f42de3`](https://github.com/tuthan/steamos-companion-omarchy/tree/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3).

## Implemented

- Added bounded IPv4 discovery over selected LAN subnets, including subnet skip reporting, global target cap, self-address filtering, duplicate suppression, 16-probe concurrency, total scan deadline, manual-host validation, cancellation/generation invalidation, and the caller-selectable three-second API 36 compatibility budget. Pairing stays bound to the discovery/payload-selected network; it will not fall back to another active LAN if that network disappears.
- Added strict payload parsing for the `steamos-companion:v1:` format, bounded/canonical Base64url and JSON, identity and pin validation, and local expiry checks. Added nonce generation and Bouncy Castle scrypt comparison-code derivation, with the three pinned host/Omarchy known answers.
- Added exact request builders for nonce and payload variants, including poll-session fields and no comparison code in either body. Client names and payload secrets obey the pinned host’s 96- and 256-character limits; session handles follow the host’s 16–256-character token format.
- Added a durable pairing coordinator covering pre-dispatch persistence, pending-session persistence, expiry and rate limits, safe not-sent retry, lost initial-reply uncertainty, one-time credential uncertainty, approval, replacement, cancellation, and stale-callback rejection. Local payload cancellation never calls the nonce-only remote cancel route.
- Added approved credential staging/finalization with the host’s granted scopes, identity, pinned endpoint, certificate pin, and wake target committed together. Bootstrap nonce, comparison code, payload secret, and session are cleared as soon as the one-time credential is staged. Current persisted schema is v3; known v0/v1/v2 states migrate forward, and unsupported legacy-code candidates are discarded. A missing older-schema comparison code is recomputed and saved by the coordinator worker before the candidate is displayed or dispatched.
- Added the planned `core/client/ClientCore` façade for discovery, manual probes, both pairing forms, resume/cancel/poll, network changes, and authenticated host-status observation. Status boot IDs are saved against the exact active-host identity before journal entries capture them.
- Added deterministic fake-gateway/clock tests for restart, cancel, remote approval, payload behavior, expiry, rejection, rate limiting, process death, concurrent coordinators, network binding, stale replies, failed credential finalization/recovery, and migration. Updated module READMEs to match the current implementation.

## Factcheck notes

- The pinned host’s [request validator](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/protocol.py) defines the distinct verification and payload body forms, 256-character payload secret cap, 16–256 pairing-session length, and 96-character client-name limit. The [pairing service](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/service.py) binds the first request to TLS channel binding and returns the session only for pending requests. Its [payload and SAS implementation](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/pairing.py) confirms the payload prefix and scrypt salt/parameters.
- The [pinned Omarchy client](https://github.com/tuthan/steamos-companion-omarchy/blob/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3/client/client_core.py) validates payload secrets as 16–256 characters, uses the same default scope set and pairing body fields, and implements the same scrypt derivation. Its [known-answer tests](https://github.com/tuthan/steamos-companion-omarchy/blob/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3/tests/test_client.py) assert `21800881`, `39693759`, and `67630249` for the recorded nonce/fingerprint inputs.
- The nonce and payload cancel semantics and lost-reply rules follow the pinned Phase 05 plan. The server’s `/v1/pair/cancel` request validator accepts a verification nonce, pairing ID, client ID, and optional session; payload cancellation is therefore local-only.
- Android exposes exactly two pairing variants. The pinned host still has a legacy `pairing_code` compatibility form, but Android rejects it at request validation and migrates any old local `legacy_code` candidate by discarding it; it is not reachable in the Android route or navigation allowlist.

## Validation run

From the repository root:

```text
GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline --no-daemon :core:test
BUILD SUCCESSFUL
core JVM suite: 217 tests, 0 failures, 0 errors, 0 skipped

GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline --no-daemon :core:check :app:compileBaselineDebugKotlin :app:compileTarget37DebugKotlin
BUILD SUCCESSFUL
core boundary verifier and both Android Kotlin compile variants: passed

git diff --check
passed
```

No new dependency was added. This phase used JVM fake transport/state inputs and compiled the Android integration; it did not pair with a physical host or run an API 36 permission-matrix device test. The existing A0 physical TLS/LAN and permission gates remain open. The production UI stays disconnected until the later onboarding phase.

## Audit correction verification — 2026-09-26

```text
GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline :core:test :app:testBaselineDebugUnitTest :app:compileBaselineDebugAndroidTestKotlin
BUILD SUCCESSFUL
core JVM suite: 234 tests, 0 failures, 0 errors, 0 skipped
baseline app JVM suite: 20 tests, 0 failures, 0 errors, 0 skipped
Android instrumentation sources compiled

python3 scripts/verify-protocol-hashes.py
protocol bundle verified: 144 files

python3 scripts/verify-protocol-coverage.py
coverage inventory verified: 140 cases, 17 routes, 141 fixtures, 226 Kotlin tests

Decky fixture checkout: PYTHONDONTWRITEBYTECODE=1 python3 -m unittest discover -s tests -p test_protocol.py
Ran 15 tests; OK
```

Named corrections cover the five-second manual probe, the three-second API 36 budget, lost-poll resume, late approval after payload cancellation, and an initial Retry-After that extends past payload expiry. Retry deadlines are clamped to pairing expiry, and resume/dispatch expires the record without another request. The host QR fixture parses with Android’s validator and is generated and checked by the Decky codec. Android instrumentation could not be run because `adb` is not installed. No dependency was added in this phase.
