# Phase 01 build decision record

**Status:** toolchain pinned and building; dependency reviews recorded below with their human dispositions.
**Recorded:** 2026-09-25. Owner of dispositions: Hung Vo.

This record is the Gate A0.3 handoff. Every coordinate in `gradle/libs.versions.toml` has a row in [Dependency reviews](#dependency-reviews). Reviews used `socket package score` (transitive/deep) and repository scans through the Socket CLI 1.1.176 with the configured `atas-tech` organization token, and are classified with the dependency-guard decision matrix. A `block` or `block_pending_human_review` result is only used after a recorded human disposition.

## Toolchain

| Component | Pinned | Basis |
| --- | --- | --- |
| JDK | Eclipse Temurin 17.0.20+8 (`mise.toml`) | AGP 9.3 requires JDK 17. `org.gradle.java.installations.auto-download=false`; CI uses `actions/setup-java` with the same version. |
| Gradle wrapper | 9.6.1, `-bin` | Inside both AGP 9.3's minimum (9.5) and KGP 2.4.20's documented range (7.6.3–9.7.0). Distribution SHA-256 `9c0f7faeeb306cb14e4279a3e084ca6b596894089a0638e68a07c945a32c9e14` is in `gradle-wrapper.properties` and was checked against services.gradle.org; wrapper JAR SHA-256 `497c8c2a7e5031f6aa847f88104aa80a93532ec32ee17bdb8d1d2f67a194a9c7` matches Gradle's published value and is re-checked in CI. |
| Android Gradle Plugin | 9.3.3 | Kotlin's compatibility table lists KGP 2.4.20 as supported through AGP 9.3.1; 9.3.3 is a patch release on that line. AGP 9.4.x (Gradle ≥ 9.6.0) is outside the documented Kotlin range and was not chosen. |
| Kotlin | 2.4.20 (`org.jetbrains.kotlin.jvm` for `:core`) | `:app` uses AGP 9 built-in Kotlin, which runs on the same KGP from the build classpath; the legacy `kotlin-android` plugin is not applied. |
| Compose compiler plugin | 2.4.20 (tracks Kotlin), added with the first Compose code | Not applied in Phase 01: there is no Compose code yet. |
| compileSdk | 37 (`platforms;android-37.0` r2) | Required by the target-37 configuration; compileSdk is module-wide, so both flavors compile against 37. |
| Build Tools | 36.0.0 | AGP 9.3 default. |
| minSdk / targetSdk | 26 / 36 (`baseline`, release) and 37 (`target37`, permission tests) | Shared specification. |
| SDK host tools | cmdline-tools 23.0 (Android CLI 1.0.16406183), platform-tools 37.0.1, emulator 37.1.11 | Installed under `~/Android/Sdk` on the development machine. |

Repositories: Google Maven restricted to `com.android.*`, `com.google.*`, and `androidx.*`, then Maven Central. No Gradle Plugin Portal. `FAIL_ON_PROJECT_REPOS`.

Lock and verify: every configuration in both modules and the root build-script classpath is locked (`core/gradle.lockfile`, `app/gradle.lockfile`, `buildscript-gradle.lockfile`). `gradle/verification-metadata.xml` holds SHA-256 for all 347 resolved components. That includes the plugins and AGP's Unified Test Platform (managed-device runner: `com.android.tools.utp`, netty, gRPC, protobuf), which falls under the build-only AGP disposition. Regenerate both only as a reviewed change: `./gradlew --write-locks --write-verification-metadata sha256 <the task set in docs/phase-01-commands.md>`.

Application: ID and namespace `io.github.tuthan.steamoscompanion` (matches the Omarchy plugin ID). Manual constructor injection; no Hilt.

## Test tiers

| Tier | Exact environment | Runs |
| --- | --- | --- |
| JVM | Temurin 17.0.20+8, Gradle 9.6.1; CI on `ubuntu-24.04` | `./gradlew check`: `:core` tests and boundary check, `:app` unit tests, per-variant merged-manifest checks, lint |
| Emulator (managed) | Gradle managed devices `api27` (`system-images;android-27;default;x86_64` r1), `api28` (`android-28;google_apis` r11), `api35` (`android-35;google_apis` r9), `api36` (`android-36;google_apis` r7), `api37` (`android-37.0;google_apis` r6), group `phase01` | Instrumented smoke tests against the fake host |
| Emulator (plain AVD) | API 26 `system-images;android-26;google_apis;x86_64` r16 via `scripts/avd-smoke.sh` (below the managed-device floor of 27); API 36 google_apis r7 via `scripts/lan-matrix.sh` | API 26 smoke; `RESTRICT_LOCAL_NETWORK` off/on with reboot |
| Physical | One API 26–28 phone, one API 36 phone, one API 37 phone when available, Deck on the pinned host, laptop on the same Wi-Fi | Phase 02 and later hardware evidence. The roster with OS builds is recorded in the Phase 01 evidence report when the devices are supplied. |

Emulator networking is NAT (`10.0.2.15` guest, `10.0.2.2` host): pinned HTTPS works, LAN discovery and Wake cannot. Those are emulator-incapable, not failures. Emulator Keystore is software-backed.

The JVM screenshot tool is chosen in Phase 06 after its review. Roborazzi 1.75.0 (Robolectric 4.17) and Paparazzi 1.3.5 are queued for review; neither is in the catalog.

## Dependency reviews

<!-- socket:begin -->
Sources, all 2026-09-25:

- Per-package deep scores: `socket package score` (CLI 1.1.176; rate-limited to about one deep score every ten minutes).
- Repository scan `6e0dc108-e96d-4d5e-86b9-50bf7de44465` (org `atas-tech`, temporary, branch `phase-01`): `socket manifest gradle --include-configs='*CompileClasspath,*RuntimeClasspath'` resolved every compile/runtime classpath in both modules into a facts file; the scan found 25 third-party components, verdict **healthy** against the org policy. Its only warn-level alert is `obfuscatedFile` in junit 4.13.2 (`org/junit/experimental/max/MaxHistory.class`); everything else is ignore- or monitor-level.
- ZXing scan `1ff2bcca-ab48-47b1-8690-7a97dfc59005`: healthy; one ignore-level `usesEval`; zero runtime dependencies.

A pattern to keep in mind: every package with a single dependency (bctls→bcprov, bcutil→bcprov, junit→hamcrest) reports deep supply-chain 36, while each named dependency scores 100 on its own. This looks like a Socket aggregation artefact, not a finding about BouncyCastle. Recheck with an authenticated deep score before A6.

| Package | Scope | Socket result | Matrix | Human disposition (Hung Vo, 2026-09-25) |
| --- | --- | --- | --- | --- |
| `com.android.tools.build:gradle` 9.3.3 (AGP) | Build only | No package result (Socket returned 404 for 9.3.0 earlier; 9.3.3 was rate-limited). Its POM depends on `kotlin-gradle-plugin` and `commons-io`, so it inherits the KGP alerts. It already runs `bcprov`/`bcpkix` 1.79 and `commons-io` 2.16.1 on the build classpath. | block_pending_human_review | **Accepted as build-only tooling.** Controls: repositories filtered by group, no plugin portal, locked build-script classpath, SHA-256 verification metadata. Nothing from it ships in the APK. |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` 2.4.20 | Build only | Package 100 in all categories. Deep over 516 transitives: overall 27, supply chain 27. High: `cve` in npm `serialize-javascript` 6.0.2, `obfuscatedFile` in `commons-io` 2.16.1. Mediums are mostly npm Kotlin/JS tooling (`installScripts` in `@parcel/watcher`, `shellAccess`). | block | **Accepted as build-only tooling**, same controls as AGP. The npm tree is only installed for Kotlin/JS or Wasm targets, which this project doesn't have. Follow up the `commons-io` `obfuscatedFile` alert when AGP/KGP next move. |
| `org.jetbrains.kotlin:kotlin-stdlib` 2.4.20 (+ `org.jetbrains:annotations` 13.0) | **Ships** | Package 100. Deep: overall 89, supply chain 98, license 100. Medium `networkAccess` and `usesEval`, which are expected APIs of a standard library. | block_pending_human_review | **Approved.** Inseparable from Kotlin; R8 shrinks it. |
| `org.bouncycastle:bctls-jdk18on` 1.86.1 | androidTest now; bootstrap TLS later | Package 100. Deep over 2: overall 36, supply chain 36, license 80. Medium `miscLicenseIssues`, `networkAccess`, `usesEval` (via bcprov). | block | **Approved; regular line chosen over LTS `lts8on` 2.73.13** (LTS was not scored). Reasons: it's the only library the specification allows to export `tls-unique`; the deep score conflicts with bcprov's own 100 (see above); AGP already uses the same family. |
| `org.bouncycastle:bcprov-jdk18on` 1.86 | as above, pinned explicitly | Package 100 in all categories. Medium `miscLicenseIssues`, `networkAccess`, `usesEval`; low `unidentifiedLicense`. No transitives. | block_pending_human_review | **Approved.** The licence is the MIT-style Bouncy Castle licence; the licence alerts are a detection gap. Never registered as a JCA provider. |
| `org.bouncycastle:bcutil-jdk18on` 1.86 | as above, pinned explicitly | Package 100. Deep over 1: overall 36, supply chain 36 (attributed to bcprov). Medium `usesEval`. | block | **Approved** on the same basis as bctls. |
| `junit:junit` 4.13.2 (+ `org.hamcrest:hamcrest-core` 1.3) | Test only | Package 100. Deep over 1: overall 36, supply chain 36 (attributed to hamcrest). High `obfuscatedFile` (warn in the org policy) in `MaxHistory.class`; medium `usesEval`; low `copyleftLicense`/`nonpermissiveLicense` (EPL-1.0), `unmaintained`. | block | **Approved, test-only.** Neither the EPL-1.0 licence nor the flagged class reaches the APK. It's required by `androidx.test.ext:junit`. |
| `org.jetbrains.kotlin:kotlin-test-junit` 2.4.20 (+ `kotlin-test`) | Test only | Package 100. Deep over 5: overall 27, supply chain 27, license 70. High `obfuscatedFile` (inherited through junit). | block | **Approved, test-only**, same basis as junit. |
| `androidx.test:runner` 1.7.0, `androidx.test.ext:junit` 1.3.0 (+ `androidx.test:core` 1.7.0, `monitor` 1.8.0, `services:storage` 1.6.0, `androidx.annotation` 1.7.0, `androidx.concurrent` 1.2.0, `androidx.lifecycle:lifecycle-common` 2.3.1, `androidx.tracing` 1.1.0) | androidTest only | No alerts in the repository scan. Per-package deep scores were not obtained (rate limit). | block_pending_human_review (deep score unavailable) | **Approved, androidTest only.** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` 1.8.1 (via androidx.concurrent), `com.google.guava:listenablefuture` 1.0 | androidTest only (transitive) | Coroutines: ignore-level `networkAccess`, `usesEval`. listenablefuture: `noLicenseFound`, `unmaintained` (monitor). It's Guava's empty conflict-avoidance stub. | block_pending_human_review | **Approved, androidTest only.** The coroutines version for `:core`, if adopted, gets its own review. |
| `com.google.zxing:core` 3.5.4 | Not in the catalog yet (scanner phase) | Scan healthy: one ignore-level `usesEval`, zero runtime dependencies. | block_pending_human_review (medium eval) | **Approved as the QR decoder.** It enters the catalog with the scanner code. |
| GitHub Actions `actions/checkout` v7.0.1 `3d3c42e`, `actions/setup-java` v6.0.1 `de7274f` | CI | Not reviewable by package score; workflow scanning is experimental. Pinned by full commit SHA, `permissions: contents: read`, `persist-credentials: false`. | block_pending_human_review (tooling unavailable) | **Approved** as first-party GitHub actions pinned by SHA. |

Candidates for later phases stay out of the catalog until reviewed in their phase: the Compose BOM 2026.09.00 and Material 3, `activity-compose` 1.13.0, `lifecycle-viewmodel-compose` 2.11.0, `kotlinx-serialization-json` 1.11.0 with its plugin, `kotlinx-coroutines-core` 1.11.0, CameraX 1.6.2, and the screenshot tool (Roborazzi 1.75.0 or Paparazzi 1.3.5).
<!-- socket:end -->

## Barcode decoder

<!-- barcode:begin -->
**ZXing Core 3.5.4 is the decoder.** It is pure Java, Apache-2.0, has no runtime dependencies and no Play Services, and works offline. The bundled ML Kit scanner (17.3.0) was rejected on policy rather than size: its dependency graph includes Google's datatransport/Firebase logging components, which send usage telemetry unless disabled. That conflicts with the specification's "no analytics, no third-party network calls" rule. The release-APK size of ZXing with R8 is measured when the scanner code lands; ML Kit was not measured because the telemetry rule excludes it.
<!-- barcode:end -->

## Release channel (before A6)

Release channel, signing-key owner, and the official target-API policy check date are not decided. They are A6 inputs and do not block A0.3.
