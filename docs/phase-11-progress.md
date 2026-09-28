# Phase 11 acceptance and release: initial audit

Date: 2026-09-28. Status: **in progress; release pending**. Source plan: `steamos-companion/android/phases/11-acceptance-and-release.md` in the documentation vault. Repository base revision: `404a3cb37f92096adcc38961958db469f11570dd` (`main`) plus the existing uncommitted Phase 06–10 worktree and this Phase 11 audit. Protocol source is pinned in [`protocol/SOURCE.json`](../protocol/SOURCE.json); no vendored protocol file was changed.

## Integrated build and contract checks

- `git diff --check`, `scripts/verify-protocol-hashes.py`, and `scripts/verify-protocol-coverage.py` passed. The pinned bundle has 144 verified files; the inventory accounts for 140 cases, 17 routes, 141 fixtures, and 320 generated Kotlin contract tests.
- `./gradlew --offline --no-daemon :core:check :app:check :app:assembleBaselineRelease :app:assembleTarget37Release` passed again after the onboarding fix. JUnit XML reports 328 core tests, 25 baseline app tests, and 25 target-37 app tests, all with zero failures, errors, or skips. Core boundary, token audit, lint, and all debug/R8-probe/release merged-manifest checks passed. Lint reports zero errors and 76 warnings.
- Both R8-shrunk release variants assembled. `aapt dump badging` confirms application ID `io.github.tuthan.steamoscompanion`, version `0.0.1` (`versionCode` 1), minSdk 26, baseline target 36, and target-37 test flavor target 37. Only the target-37 flavor requests `ACCESS_LOCAL_NETWORK`. These APKs are **unsigned** and are not distribution artifacts; `apksigner verify` rejects the baseline APK with `Missing META-INF/MANIFEST.MF`, as expected for this unsigned output.

| Variant | Bytes | SHA-256 |
| --- | ---: | --- |
| `app-baseline-release-unsigned.apk` | 2,219,437 | `b2eab15e8316689f6f37252d4dc9650abe007ddce42cf3977a227110c2c98367` |
| `app-target37-release-unsigned.apk` | 2,219,465 | `de1b1a6abe25d5b0f637a4a4a2e288d8ac5031c499796660b221ce9f856dc922` |

The Phase 06 fixture validator found all 177 state rows and their references, but **885 required golden PNGs are missing**. Its nonzero exit is a visual acceptance failure, not a missing protocol fixture. The component-gallery captures and limited Activity navigation checks recorded in [`phase-06-review.md`](phase-06-review.md) do not close the full screenshot, motion, large-text, and accessibility matrix.

CI now builds both minified release variants alongside `check` and the debug/test APKs on each push or pull request. No signing key is stored in CI or the repository.

## Release policy and decisions

Checked 2026-09-28: [Google Play's target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en) require new mobile apps and updates submitted from 2026-08-31 to target API 36 or higher. The baseline flavor meets that *target API requirement only*. The Play rule does not determine the project's release channel, signing ownership, or readiness. The target-37 flavor remains a separate permission-test configuration pending its full matrix.

Release channel and signing-key owner have not yet been recorded. No release signing configuration or checksum for a signed artifact exists. The project version is still `0.0.1`; it should be set deliberately before a release candidate is signed.

## Open acceptance gates

1. **Earlier phases:** Phase 00 prerequisite and A0 TLS/physical LAN evidence are still open. Phase 06 lacks the 885 goldens and full accessibility/fixture checks; Phase 07 lacks physical live pairing and QR evidence. Phases 08–10 have substantial code and synthetic tests, but no live control, Wake capture, display recovery, or display-order acceptance. See [`phase-08-10-progress.md`](phase-08-10-progress.md) and [`phase-09-10-process-death-evidence.md`](phase-09-10-process-death-evidence.md).
2. **Integrated devices:** Repeat the complete runtime/target LAN permission matrix with the final build, including API 36 restricted/unrestricted and API 37 target-36/target-37 denial, revocation, and network-change paths. The managed-device results below cannot substitute for the physical roster. `adb devices -l` listed no attached devices after the emulator runs.
3. **Release journeys:** On physical hardware, complete fresh pairing, saved recovery profile, process-kill and suspend/wake reconnect, controlled blank-picture restore, timed preview/Keep, ten Wake cycles with packet/authenticated-response/Steam-ready timestamps, and install/revoke/reinstall of the signed APK.
4. **People and presentation:** Complete TalkBack, 200% text on 360 × 640 dp, reduced motion, dark/light/monochrome, and three formative usability sessions. A wrong target, accidental mutation, or unreachable recovery control blocks release under the phase plan.
5. **Supply chain and artifact:** Recheck the outstanding deep dependency review signals in [`build-decision.md`](build-decision.md), confirm distribution channel and signing custody, then build and verify a signed baseline artifact against exact code/protocol/toolchain revisions. Do not distribute the unsigned APKs above.

## Managed-device rerun

`./gradlew --offline --no-daemon :app:api36BaselineDebugAndroidTest :app:api37Target37DebugAndroidTest` passed after one production UI fix. The managed API 36 baseline and API 37 target-37 JUnit XML files each report 46 tests, zero failures/errors, and one skip. The skipped `LanMatrixTest.privateAddressReachabilityMatchesCompatState` requires the separate API 36 compatibility-flag/reboot harness; it is not a passing restricted/unrestricted result. These tests use debug variants, not the unsigned release APKs.

The first API 36 run failed two launch assertions because the production onboarding page omitted the source-defined “Connect a remote device” heading while rendering its instructions and actions. The title was present in `strings.xml` and the shared flows note but was never used by the screen. `OnboardingScreen` now renders it with heading semantics. The complete API 36 and 37 suites then passed. The initial failed run is retained here as the reason for the fix; it is not counted as acceptance evidence.
