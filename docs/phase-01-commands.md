# Phase 01 validation commands

Run from a clean clone with JDK 17 from `mise.toml` and `sdk.dir` in `local.properties`.

```sh
python3 scripts/verify-protocol-hashes.py
python3 scripts/verify-protocol-coverage.py
./gradlew buildEnvironment check \
  :app:assembleBaselineRelease :app:assembleTarget37Release \
  :app:assembleBaselineDebugAndroidTest :app:assembleTarget37DebugAndroidTest \
  :app:phase01GroupBaselineDebugAndroidTest :app:api37Target37DebugAndroidTest
scripts/avd-smoke.sh 26 "system-images;android-26;google_apis;x86_64"
scripts/lan-matrix.sh
```

The Gradle line is also the task set for regenerating locks and verification metadata (add `--write-locks --write-verification-metadata sha256`), so that every configuration the build and the managed-device runner resolve is covered.
