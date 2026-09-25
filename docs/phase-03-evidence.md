# Phase 03 implementation and validation evidence

Date: 2026-09-25

Code: this evidence is committed with the Phase 03 implementation.

Protocol source: Decky fixture commit `4c35efb294e689a0451caecf3cb2e3fddcde7555`, protocol tree `1ebaf97d4645b145ccdd937a0d3a2663c84b7636`; pinned host behavior is `v0.5.16` (`9c213b31cdce36e67784355be21e2fc1b2b7570f`). See [`SOURCE.json`](../protocol/SOURCE.json).

## Implemented

- Added Android-free typed models, closed route/body validation, bounded strict UTF-8 JSON parsing, version/error handling, and response models in `core/protocol`.
- Added strict single-response HTTP/1.0/1.1 framing, route body caps, absolute monotonic deadlines, bounded asynchronous DNS, cancellation by socket closure, Retry-After parsing, and deterministic pre-send/unknown/unresolved-pairing outcomes in `core/transport`.
- Added the pinned platform TLS path and the TLS 1.2, full-handshake-only pairing bootstrap that exports `tls-unique`; pin checks run before application request bytes.
- Added the only unpinned path as a one-request, unauthenticated discovery probe. It checks the service/version/host identity and verifies the returned fingerprint against the leaf DER captured on that same connection.
- Added Android adapters for per-network DNS, pre-connect socket binding, Wi-Fi/Ethernet path discovery, Android clocks, and unbound sockets. No mutation is connected to a UI control.
- Added 140 named contract-fixture tests, strict parser/validation/deadline tests, and Android loopback fake-host transport tests. The protocol coverage verifier now confirms that each inventory row maps to a Kotlin test method that exists.

## Factcheck notes

- The HTTP parser follows the pinned host's HTTP/1.0 `Content-Length` and connection-close behavior. RFC 9112 defines the declared length as the body boundary and a shorter received body as incomplete; this implementation rejects truncation and never interprets bytes after the declared body as another response. [RFC 9112](https://www.rfc-editor.org/rfc/rfc9112.html)
- Retry-After accepts nonnegative decimal seconds or an HTTP date. When a `Date` header exists, that date is the reference; otherwise the app's UTC wall clock is used. Missing or invalid values use the specified two-second fallback. [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)
- Android's `Network.getAllByName` resolves on the selected network, and `Network.bindSocket` routes an unconnected socket over that network. TCP binding is performed before connect; the same selected network is used for DNS. [Android `Network` API](https://developer.android.com/reference/android/net/Network)
- Java's socket read timeout is an inactivity limit on reads, so it cannot enforce one deadline over DNS, connect, TLS, writes, and reads. A separate monotonic watchdog therefore closes the registered socket and cancels resolver futures at the total deadline. [Java 17 `Socket` API](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/net/Socket.html)
- The JSON parser rejects duplicate member names as a deliberate deterministic-client policy. RFC 8259 says object names should be unique and documents divergent receiver behavior for duplicates. [RFC 8259](https://www.rfc-editor.org/rfc/rfc8259.html)
- Bouncy Castle `1.86`/`1.86.1` was already reviewed and approved in [`build-decision.md`](build-decision.md); Phase 03 moves the artifacts from test-only use into `:core` because initial pairing must export TLS 1.2 `tls-unique`. It is not registered as a global JCA provider. Versions are pinned in Gradle lockfiles.

## Validation run

Commands, from the repository root:

```text
python3 scripts/verify-protocol-hashes.py
protocol bundle verified: 143 files

python3 scripts/verify-protocol-coverage.py
coverage inventory verified: 140 cases, 17 routes, 140 fixtures, 152 Kotlin tests

GRADLE_USER_HOME=/tmp/gradle-hvo /home/hvo/.gradle/wrapper/dists/gradle-9.6.1-bin/4ticwg1pgcbps2hj28r8so764/gradle-9.6.1/bin/gradle --offline :core:test :app:phase01GroupTarget37DebugAndroidTest
BUILD SUCCESSFUL
core JVM suite: 160 tests, 0 failures, 0 errors, 0 skipped
Android target37Debug instrumentation: API 27, 28, 35, 36, and 37 all passed. Each device ran the eight Phase03 transport tests; the pre-existing radio-dependent LanMatrix test was skipped on managed emulators.

git diff --check
passed
```

The Android tests use the checked-in fake TLS host over loopback. They validate Android TLS/socket behavior, pin mismatch before request bytes, bootstrap channel binding, discovery isolation, lost/partial POST classification, cancellation, slow reads, rate limits, and the short status-poll deadline. They do not establish physical Wi-Fi/Ethernet behavior or replace the outstanding A0 hardware acceptance gates.

## Gate boundary

The Phase 03 implementation and listed automated exits passed on the stated contract revision. The plan file lives outside this repository and still lists its prerequisite as Phase 02 with all A0 gates closed. The Phase 00/A0 physical acceptance work and Phase 02 physical TLS/permission matrix were not performed by this code task, so this evidence does not mark those gates closed or claim physical Decky validation. No mutation UI was added.
