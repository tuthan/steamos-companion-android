# :app

The Android application module: manifest, platform permissions, Android Keystore-backed storage, network binding, lifecycle adapters, manual constructor wiring, ViewModels, and Compose destinations. It depends on `:core`; `:core` never depends on it.

Present: the manifest security baseline (no backup or device transfer, no cleartext, no user CAs), the `baseline`/`target37` flavors with a merged-manifest check per variant, managed-device definitions, and the instrumented fake host in `src/androidTest`. No screens or platform adapters yet.
