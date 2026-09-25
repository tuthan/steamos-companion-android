# Phase 05 implementation and validation evidence

Date: 2026-09-26

Code: committed with the Phase 05 implementation.

Protocol contract: Decky fixture commit [`4c35efb294e689a0451caecf3cb2e3fddcde7555`](https://github.com/tuthan/steamos-companion-decky/tree/4c35efb294e689a0451caecf3cb2e3fddcde755), protocol tree `1ebaf97d4645b145ccdd937a0d3a2663c84b7636`, with pinned host behavior v0.5.16 (`9c213b31cdce36e67784355be21e2fc1b2b7570f`). Omarchy comparison source is release 0.5.14 at [`5c7c9611156aa4cad7e10f3879d51ea7f7f42de3`](https://github.com/tuthan/steamos-companion-omarchy/tree/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3). See [`SOURCE.json`](../protocol/SOURCE.json).

## Implemented

- Added bounded IPv4 discovery over selected LAN subnets, including subnet skip reporting, global target cap, self-address filtering, duplicate suppression, 16-probe concurrency, total scan deadline, manual-host validation, cancellation/generation invalidation, and the caller-selectable three-second API 36 compatibility budget. Pairing stays bound to the discovery/payload-selected network; it will not fall back to another active LAN if that network disappears.
- Added strict payload parsing for the `steamos-companion:v1:` format, bounded/canonical Base64url and JSON, identity and pin validation, and local expiry checks. Added nonce generation and Bouncy Castle scrypt comparison-code derivation, with the three pinned host/Omarchy known answers.
- Added exact request builders for nonce and payload variants, including poll-session fields and no comparison code in either body. Client names and payload secrets obey the pinned host’s 96- and 256-character limits; session handles follow the host’s 16–256-character token format.
- Added a durable pairing coordinator covering pre-dispatch persistence, pending-session persistence, expiry and rate limits, safe not-sent retry, lost initial-reply uncertainty, one-time credential uncertainty, approval, replacement, cancellation, and stale-callback rejection. Local payload cancellation never calls the nonce-only remote cancel route.
- Added approved credential staging/finalization with the host’s granted scopes, identity, pinned endpoint, certificate pin, and wake target committed together. Bootstrap nonce, comparison code, payload secret, and session are cleared as soon as the one-time credential is staged. Added schema-v2 candidate fields and a schema-v1 migration that retains existing fields and conservatively carries forward payload expiry. A missing schema-v1 comparison code is recomputed and saved by the coordinator worker before the candidate is displayed or dispatched.
- Added deterministic fake-gateway/clock tests for restart, cancel, remote approval, payload behavior, expiry, rejection, rate limiting, process death, concurrent coordinators, network binding, stale replies, failed credential finalization/recovery, and migration. Updated module READMEs to match the current implementation.

## Factcheck notes

- The pinned host’s [request validator](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/protocol.py) defines the distinct verification and payload body forms, 256-character payload secret cap, 16–256 pairing-session length, and 96-character client-name limit. The [pairing service](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/service.py) binds the first request to TLS channel binding and returns the session only for pending requests. Its [payload and SAS implementation](https://github.com/tuthan/steamos-companion-decky/blob/4c35efb294e689a0451caecf3cb2e3fddcde7555/host/backend/pairing.py) confirms the payload prefix and scrypt salt/parameters.
- The [pinned Omarchy client](https://github.com/tuthan/steamos-companion-omarchy/blob/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3/client/client_core.py) validates payload secrets as 16–256 characters, uses the same default scope set and pairing body fields, and implements the same scrypt derivation. Its [known-answer tests](https://github.com/tuthan/steamos-companion-omarchy/blob/5c7c9611156aa4cad7e10f3879d51ea7f7f42de3/tests/test_client.py) assert `21800881`, `39693759`, and `67630249` for the recorded nonce/fingerprint inputs.
- The nonce and payload cancel semantics and lost-reply rules follow the pinned Phase 05 plan. The server’s `/v1/pair/cancel` request validator accepts a verification nonce, pairing ID, client ID, and optional session; payload cancellation is therefore local-only.

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
