# Store release readiness review

Date: 2026-10-13. Scope: the Android client at `main` `7ac43e4` plus the uncommitted 2026-10-05 tree, reviewed for a first Google Play release. Nothing in the repository, the Play Console or any signing material was changed by this review; the only artifacts it built are unsigned and live under `app/build/`. The vault copy of the gates is Phase 11 in `steamos-companion/android/phases/11-acceptance-and-release.md`; the feature and fix inventory is [`CHANGELOG.md`](../CHANGELOG.md).

**Verdict.** Not ready for any Play track today. It becomes ready for **internal testing** once the tree is committed and tagged and a signing path exists (rows 1 and 2). It is **not ready for production**: no dated device report exists (the app is in use on a tester's phone, but nothing is recorded as acceptance evidence), there is no privacy policy, no About screen, no reviewer access and no listing assets. *Correction, same day:* rows 4 to 8 below were first marked "production", but the Paddock plan's order puts the declarations, policy and listing before **closed testing**, the first track Google reviews, so they gate that track too. This is the plan's order, not something confirmed in the Console. The sequenced plan is `steamos-companion/android/play-publication-plan.md` in the vault. The vault rule stands: nothing is described as released until A11·R1, A11·R2 and A11·H1 close.

## Verified today

| Check | Result |
| --- | --- |
| `python3 scripts/verify-protocol-hashes.py` | 161 files verified against `protocol/SOURCE.json` (Decky `0219c27`) |
| `python3 scripts/verify-protocol-coverage.py` | 154 cases, 19 routes, 157 fixtures, 676 Kotlin tests accounted for |
| `./gradlew --offline check --continue :app:assembleBaselineRelease :app:assembleTarget37Release` | Build successful in 2 min 7 s; `:core` 684 tests, `:app` 230 per flavor, zero failures, errors or skips; merged-manifest checks pass for debug, R8 probe and release in both flavors |
| `./gradlew --offline :app:bundleBaselineRelease` | Builds `app-baseline-release.aab`, 5,115,437 bytes, SHA-256 `d7bd79bcf2108f6a03e49ee9244ec5ad24d3ad60218b182a9535bb316b3ebd03`. **Unsigned and built from a dirty tree: not a release artifact** |
| Baseline release APK (unsigned) | 2,399,809 bytes, SHA-256 `8f4fc6e0e30bd46c8831a72e10da68099472c5d147cd71967c708f05b2be2e45`, `io.github.tuthan.steamoscompanion`, version `0.0.1` (1), minSdk 26, targetSdk 36. Supersedes the hashes in `phase-11-progress.md`. The target-37 APK is `f9a79cf9d4fd9cad6213f960921bd0257adaed6351a1716304f8c0b9c02a6412` and must not be uploaded |
| 16 KB page size | The one native library, `libandroidx.graphics.path.so`, is in four ABIs; every `LOAD` segment has alignment `0x4000` and `zipalign -c -P 16 -v 4` passes |
| Permissions and exported components | `INTERNET`, `ACCESS_NETWORK_STATE`, `CAMERA`, `NEARBY_WIFI_DEVICES` (`maxSdkVersion` 36, `neverForLocation`) and AndroidX's own dynamic-receiver permission. Exported: the launcher activity and AndroidX's `ProfileInstallReceiver` (guarded by `DUMP`). `allowBackup=false`, backup and transfer rules exclude every domain, cleartext off |
| Telemetry | None. The version catalog has no analytics, advertising, crash-reporting, billing or Play Services library |

Not run: any instrumented or managed-device test, any device, the Play Console. Facts about Google's policies below were read from the Paddock Play plan (`docs-vault/herdr-android/13-google-play-publication-plan.md`, verified against Google's pages on 2026-10-12) and were **not re-fetched today**.

## Findings

| # | Area | Finding | Needed before | Action |
| --- | --- | --- | --- | --- |
| 1 | Source control | **Resolved 2026-10-13 except the tag and the Decky pin.** The 51 uncommitted paths were committed in three commits and pushed to the public `tuthan/steamos-companion-android`; CI ran for the first time and is green on `f7c67f6` after two fixes (see `phase-11-progress.md`, "First CI runs"). Still open: the Decky commit `0219c27` that `protocol/SOURCE.json` pins exists only in the local Decky checkout (14 commits ahead of its remote), so a stranger's clean clone cannot verify the pin against upstream; and no release tag exists | internal testing | Push the Decky pin (A01·P1), then tag (A11·P2) |
| 2 | Signing and bundle | `release` has no `signingConfig`; no document mentions an app bundle, a Play upload key or Play App Signing; the only signed APKs are debug-signed test builds. Play requires a bundle and, for a new app, a separate upload key | internal testing | Create the keys outside the repository, CI and logs; add a signing step for the bundle; record the key custody (A11·R1, A11·R6) |
| 3 | Version | `0.0.1` / 1 was never chosen. Every Play upload spends a version code | internal testing | Pick the first candidate on purpose (the Paddock plan uses `0.1.0-rc1` for internal testing and `0.1.0` for production on a second version code) (A11·R2) |
| 4 | Reviewer access | A reviewer has no Steam Deck, so every screen past onboarding is unreachable. Google's App access rule says details "must" be given for anything behind a gate and names no hardware exception (Paddock review, 2026-10-12). The iOS plan (I11·D1) currently rules out a demo mode | closed testing | Decide: in-app demo host that opens no socket, or written instructions with a recording, or both. One answer for both stores (A11·D1) |
| 5 | Privacy policy | None exists. Play requires a policy URL for every app that completes the Data safety form, linked in the console and in the app. The app requests `CAMERA` | closed testing | Write one page from the facts below, host it at a stable URL (the Decky project site on GitHub Pages is the existing candidate; a PDF is not accepted), link it from About (A11·R5) |
| 6 | About screen | None exists. The design profile specifies one (wordmark, meta pills, info cards) and the iOS Phase 11 checklist assumes one, but no phase row builds it. The app shows no version, no third-party licence text (AndroidX, Compose, ZXing and BouncyCastle ship under Apache-2.0 or MIT terms; the two OFL texts are packaged in `res/raw` but never shown), no "not affiliated with Valve" line and no privacy link | closed testing | Build it once, from one spec, for Android and iOS (A11·F1) |
| 7 | Listing and declarations | A 512 × 512 icon exists in the vault (RGB, no alpha). There is no feature graphic (1024 × 500), no phone screenshots, no title or descriptions, and none of the declarations (content rating, target audience, ads, App access, Data safety, Advertising ID) is recorded | closed testing | Draft the text, capture screenshots from real screens, record every answer with its date in `docs/privacy-checklist.md` (A11·R7) |
| 8 | Name | The app name and listing use "SteamOS" and "Steam Deck". Not a confirmed policy breach; a trademark complaint is a plausible way for a listing to be taken down, and the title is permanent once the package name is published | closed testing | Decide the title; put the not-affiliated sentence in the first lines of the description and in About (A11·R7) |
| 9 | Account | The owner confirmed on 2026-10-13 that the Console account is **personal**. The creation date was not stated, but the Paddock plan records a Play account active on 2026-10-12, so it is assumed to be after 2023-11-13 (confirm the date). On such an account, production needs 12 testers opted in for 14 unbroken days. Developer verification has been enforced in Singapore since 2026-09-30; an app on and off Play registers its package name and key | closed testing | Record the account facts; if personal, open the closed track at `0.1` so the 14 days run during the hardware work (A11·R6) |
| 10 | Hardware evidence | No dated device report, TalkBack pass, 200 % text check or usability session. The app is in use on real devices and the owner confirmed on 2026-10-13 that the tester-reported fixes work there (an owner statement; see `CHANGELOG.md`, Known issues), but no diagnostics export or recorded session backs it | production | The Phase 11 procedure, A11·H1 to H3 and A11·P1; export the diagnostics before tapping Mark as reviewed if the lane wedges again |
| 11 | Accessibility | 19 text pairs on tint fills are below 4.5:1 (lowest 3.67:1); Play's pre-launch report and TalkBack both read this | production | Decide A06·D1, then re-record the 20 gallery goldens |
| 12 | Dependencies | A11·R4 is open: the deep Socket score is 36 on `bctls` and `bcutil` through an aggregation artefact, and the Compose, Material 3, Roborazzi and Robolectric set was accepted unscored on 2026-09-28 | production | Authenticated deep scores, or record the acceptance as final in `build-decision.md` |
| 13 | Repository hygiene | `core/src/test/resources/python-tls/fake-host-key.pem` is a tracked private key. It is a test fixture for a fake host, but a committed key is what GitHub secret scanning flags on the first push | first push | Note it as a fixture in the README of that folder, or generate it at test time |
| 14 | Docs | The README still said the production transport and Compose tree were in progress; the first section of `phase-11-progress.md` carries superseded counts (144 files, 17 routes) | n/a | README corrected on 2026-10-13; the dated sections of `phase-11-progress.md` stay as the record, this file and `CHANGELOG.md` carry the current numbers |

## What the privacy policy and Data safety answers rest on

These are the facts the repository supports today; the policy text and the form answers are not written.

- No developer server, no account, no analytics or crash upload, no advertising identifier, no purchases.
- Traffic goes only to the paired Steam Deck over the local network or the user's own VPN, over pinned TLS; the Wake packet carries the host's MAC to the user's own relay or broadcast address.
- The bearer token and pairing state are stored encrypted with an Android Keystore key, never backed up or transferred.
- The camera is used only to read a pairing QR code on the device; no image is stored or sent. The diagnostic export is written only when the owner chooses a file, is redacted of identifiers, addresses, pins, tokens and MAC addresses, and is never uploaded.
- Proposed stance, following the Paddock plan: "no data collected, none shared", after one recorded measurement of the app's own sockets on a phone showing only the paired host. This is the publisher's judgement; the form makes it so.

## Decisions for the owner

| # | Question | Recommendation |
| --- | --- | --- |
| D1 | Reviewer access: demo host, written instructions, or both | Written instructions plus a recording first, a demo only as the fallback if Play rejects for access. This is the lower-cost reversible step and matches the iOS plan's I11·D1 order, but it is a judgement: the Paddock review recommended a demo host for the same rule, because Google's page names no hardware exception. If a demo is built it must open no socket and stay out of the production pairing path; the 177 `phase06` screen-state fixtures already encode the states and no renderer draws them yet (A06·F1). The iOS plan must follow the answer |
| D2 | Which cut goes first | `0.1.0-rc1` to internal testing from a tagged tree; `0.1.0` to production only after A11·H1 closes |
| D3 | Account and entity | Personal account (confirmed 2026-10-13). A free app shows only the legal name, but the 12-tester rule applies if it was created after 2023-11-13, which is the working assumption: start the closed track as early as the declarations allow so the 14 days run during the rest of the work |
| D4 | Title | Keep "SteamOS Companion" with the not-affiliated line, or rename before the package name and listing are published |
| D5 | Where the privacy policy lives | The Decky project site (`site/`, GitHub Pages) so one URL serves Android and iOS |
| D6 | Whether the 19 contrast pairs block the first release | A06·D1: choose the straightforward reading of R10 (raise tint alphas, re-record goldens) |

## Suggested order

1. Commit, remote, CI, tag (rows 1, 13). Decide D2 and D4.
2. Keys, bundle signing, version (rows 2, 3). Upload `0.1.0-rc1` to internal testing; install from Play on a phone; measure the app's sockets (row 5 input).
3. About screen, privacy policy, declarations, listing assets (rows 5 to 8), after D1 and D5.
4. The hardware report and TalkBack pass on the installed build (rows 10, 11); closed testing for the soak (row 9).
5. Production cut `0.1.0`, staged rollout.
