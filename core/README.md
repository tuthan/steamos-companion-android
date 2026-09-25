# :core

Kotlin/JVM only. `verifyCoreBoundary` (part of `check`) fails the build on any `android`, `androidx`, or `com.android` import, and the build script rejects project dependencies.

Keep protocol models and validation, closed route definitions, pairing state, bounded transport policies, discovery selection, Wake packet construction, and reconciliation decisions here. Depend on injected ports for clocks, state storage, sockets (TCP and UDP), and network binding so the core runs without Android.

Present: the `Clock`, `StateStore`, `SocketFactory`, `NetworkAccess`, and `CorePorts` constructor-injection boundary and its JVM smoke test. No production protocol or transport yet.
