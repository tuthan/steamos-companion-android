# `:app`

The Android application module: manifest and permission variants, Android Keystore and AtomicFile storage, clocks, LAN network selection/DNS/socket binding, and the instrumented pinned-TLS fake host. It depends on `:core`; `:core` never depends on it.

The `baseline` and `target37` flavors retain merged-manifest checks. Discovery and pairing are implemented as testable `:core` state machines. Production screens remain for Phase 06, and live onboarding wiring follows in Phase 07.
