# `:core`

Kotlin/JVM only. `verifyCoreBoundary` (part of `check`) fails the build on any `android`, `androidx`, or `com.android` import, and the build script rejects project dependencies.

The module owns validated protocol models and closed routes, pinned bounded TLS/HTTP transport, LAN discovery policies, both pairing state machines, encrypted-state transactions, the operation journal, and reconciliation decisions. Clock, persistence, sockets, and network binding come from injected ports so JVM tests can use deterministic fakes.

Phase 03–05 coverage lives in `src/test`: contract fixtures, transport edge cases, IPv4 scan bounds/cancellation, scrypt vectors and exact pairing request bodies, pairing persistence/restart/cancel/error handling, and schema migration. Android framework code stays in `:app`.
