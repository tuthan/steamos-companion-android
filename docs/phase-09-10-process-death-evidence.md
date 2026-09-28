# API 37 process-death persistence journey

Date: 2026-09-28. Android revision base: `404a3cb37f92096adcc38961958db469f11570dd` with the uncommitted Phase 8–10 worktree. Build variant: `target37Debug`. Device: fresh temporary headless API 37 Google APIs x86_64 emulator, serial `emulator-5580`, with a 2 GiB data partition. The temporary AVD was removed after the run. No existing emulator or physical-device app data was changed.

App APK SHA-256: `715aca6aae75c97f07d09e2c89e4194bcd9b0ca8ce566353e4b32cb38f978c54`.
AndroidTest APK SHA-256: `ab52d9cd024ce01f3732b9f6eaa3dfe404615716aa4d19703a02121e85aec42f`.

`./gradlew --offline :app:assembleTarget37Debug :app:assembleTarget37DebugAndroidTest` passed. A normal AndroidJUnit4 test selected through the custom target-37 runner passed `OK (1 test)`, confirming that the manual stage path does not replace ordinary instrumentation execution.

The isolated device was created and loaded with these APKs:

```text
mkdir -p /tmp/sc-phase09-avds
printf 'no\n' | ANDROID_AVD_HOME=/tmp/sc-phase09-avds /home/hvo/Android/Sdk/cmdline-tools/latest/bin/avdmanager create avd -n sc-phase09-death -k 'system-images;android-37.0;google_apis;x86_64' -p /tmp/sc-phase09-avds/sc-phase09-death.avd --force
ANDROID_AVD_HOME=/tmp/sc-phase09-avds /home/hvo/Android/Sdk/emulator/emulator -avd sc-phase09-death -no-window -no-audio -no-snapshot -no-boot-anim -gpu swiftshader_indirect -partition-size 2048 -cores 2 -port 5580
adb -s emulator-5580 install app/build/outputs/apk/target37/debug/app-target37-debug.apk
adb -s emulator-5580 install app/build/outputs/apk/androidTest/target37/debug/app-target37-debug-androidTest.apk
```

The host invoked the same instrumentation component four times, each with a different `process_death_stage` argument:

```text
adb -s emulator-5580 shell am instrument -w -e process_death_stage seed_preview io.github.tuthan.steamoscompanion.test/io.github.tuthan.steamoscompanion.android.data.ProcessDeathInstrumentation
adb -s emulator-5580 shell am instrument -w -e process_death_stage approve_candidate io.github.tuthan.steamoscompanion.test/io.github.tuthan.steamoscompanion.android.data.ProcessDeathInstrumentation
adb -s emulator-5580 shell am instrument -w -e process_death_stage swap_candidate io.github.tuthan.steamoscompanion.test/io.github.tuthan.steamoscompanion.android.data.ProcessDeathInstrumentation
adb -s emulator-5580 shell am instrument -w -e process_death_stage verify_swap io.github.tuthan.steamoscompanion.test/io.github.tuthan.steamoscompanion.android.data.ProcessDeathInstrumentation
```

| Stage | App PID | Result and persisted fact checked |
| --- | ---: | --- |
| `seed_preview` | 4617 | Passed, then intentional `Process crashed.`; durable old host, accepted preview journal, cached recovery profile, and ready candidate written. |
| `approve_candidate` | 4752 | Passed in a new process, then intentional `Process crashed.`; preview card and profile restored from Android Keystore/AtomicFile, old host still active, candidate approved and staged. |
| `swap_candidate` | 4786 | Passed in a new process, then intentional `Process crashed.`; old host and journal still present before confirmation, then candidate atomically accepted. |
| `verify_swap` | 4817 | Passed in a new process, `INSTRUMENTATION_CODE: -1`; new host active, candidate cleared, old-host journal retained, and old recovery-profile cache absent from the new host. |

The first `approve_candidate` attempt returned `IllegalArgumentException` because the test compared the restored host to a fixture without the recovery-profile cache. The assertion was corrected, the AndroidTest APK was reinstalled without clearing target-app data, and the stage then passed against the original `seed_preview` state.

This is real target-app process death and recreation over the production Android Keystore/AtomicFile adapters with synthetic state. It does not exercise rendered UI, a host display preview, or the physical controlled-failure Restore journey. The app has no rendered Compose destinations yet. `git diff --check` passed after the run.

After the temporary AVD was removed, `./gradlew --offline :app:api37Target37DebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=io.github.tuthan.steamoscompanion.android.data.AndroidPersistenceTest` passed on Gradle's managed API 37 emulator. The result XML reports 8 tests, 0 failures, 0 errors, and 0 skips with the new runner. Gradle cleaned up its emulator; `adb devices -l` then listed no attached devices.

## Startup journal recovery rerun

On 2026-09-28, a second fresh temporary API 37 Google APIs x86_64 emulator used the same isolated `emulator-5580` slot and 2 GiB data partition. This build calls `recoverPreparedEntries()` during production `CompanionApplication` startup. The manual instrumentation invokes that startup path in every new process, then reads the production Android Keystore/AtomicFile store. The `seed_preview` stage wrote both an accepted display preview and a `PREPARED` power operation before killing the process. Subsequent stages required the power entry to be `UNKNOWN` while the accepted preview, cached recovery profile, candidate, and later replacement state survived.

App APK SHA-256: `839d930a6206b5fd916757d5dbeeb91988c039b64fdda7eb9bacac0825dc6434`.
AndroidTest APK SHA-256: `3477c59b0a01dbeab763e248f3ae0422a10da3f39f4d9817e294b3e2f85dd6fb`.

The same four `adb shell am instrument` commands above passed in order:

| Stage | App PID | Result and persisted fact checked |
| --- | ---: | --- |
| `seed_preview` | 4547 | Passed, then intentional process kill after writing accepted preview, prepared power entry, cached profile, and ready candidate. |
| `approve_candidate` | 4579 | Passed in a new process; startup had changed power `PREPARED` to `UNKNOWN`, while the pending preview card and cached profile were restored. Candidate approval was staged before intentional process kill. |
| `swap_candidate` | 4615 | Passed in a new process; the old host and both journal entries were retained until candidate confirmation atomically selected the new host. Intentional process kill followed. |
| `verify_swap` | 4644 | Passed in a new process with `INSTRUMENTATION_CODE: -1`; new host active, candidate cleared, new host profile cache empty, both old-host journal entries retained, and power still `UNKNOWN`. |

The first three commands reported `INSTRUMENTATION_STATUS: result=passed` followed by Android's expected `shortMsg=Process crashed.` after the intentional kill. The final command reported `result=passed`. This is synthetic persistence and startup recovery evidence; it does not establish a physical power transition or a live display recovery outcome.

After the rerun, the isolated emulator was shut down, `/tmp/sc-phase09-avds` was removed, `adb devices -l` listed no attached devices, and `git diff --check` passed.

## Current schema v6 attempt

On 2026-09-28, the manual harness was extended to seed a failed preview Restore with `READBACK_NOT_CONFIRMED` alongside the accepted preview and `PREPARED` power entry. Its later stages check that the typed failure survives target-app recreation and the atomic pairing swap. `./gradlew --offline :app:assembleTarget37Debug :app:assembleTarget37DebugAndroidTest` passed. App APK SHA-256: `f773e4b695138e079172be15e698c7237fb2e7f10d1689b020eb4998870733e4`. AndroidTest APK SHA-256: `454dd3fce38b0adf6d4456138d89a320030f36beb9075e4cb8004ef8b4abea52`.

The four-stage run did not start. Two fresh isolated API 37 emulator boots reported Android boot completion, but each ADB transport became `offline` before APK installation. ADB reconnect, server reset, and direct local-port connection did not restore the transport. Both emulator processes were stopped and their `/tmp` AVD directories removed; the stale direct connection was disconnected. This is a device-harness limitation for the current build, not a passing process-death result. The prior schema v5 kill journey above remains the last completed four-stage run.

On a further fresh isolated API 37 attempt, both current APKs assembled again. App APK SHA-256: `d27e24397bafe027bba9b38745fb4687cf87b4c27d363cb22968c6bcba7128c2`. AndroidTest APK SHA-256: `db306987e5405fd0368f2beeb8d1fd95b7e9d8b3970db96dca2ef1b03daa0923`. The emulator was visible to ADB as `device`, and `sys.boot_completed` returned `1`. The first APK installation then returned `device offline`; reconnecting the offline transport, restarting the ADB server, and connecting directly to `127.0.0.1:5581` did not recover it. No APK was installed and no manual stage ran. The emulator exited, its dedicated `/tmp/sc-phase09-v6-avds` directory was removed, and the stale direct ADB connection was disconnected. An unrelated sandboxed inspection command also reported a temporary-files quota error during this run; the emulator and ADB ports remained live at that point, so the record does not assign that error as the transport cause.
