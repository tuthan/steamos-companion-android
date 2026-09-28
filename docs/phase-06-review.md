# Phase 06 source review

Status: implementation in progress. The user accepted the documented Compose/Roborazzi candidate set on 2026-09-28 despite missing Socket scores. This resolves the dependency disposition; rendered screens and screenshot gates remain open.

## Inputs checked

- Phase plan: `steamos-companion/android/phases/06-compose-and-fixture-screens.md`.
- Shared UI behavior and copy: `steamos-companion/10-decky-client-ux-and-flows.md`, sections 4–12.
- Android visual profile: `steamos-companion/android/design-profile.md`.
- Android implementation contract: `steamos-companion/android/implementation-spec.md`.
- Protocol source pin: host v0.5.16, commit `9c213b31cdce36e67784355be21e2fc1b2b7570f`; repo fixture source is recorded in `protocol/SOURCE.json`.

## Findings and implementation boundaries

1. **CameraX is not approved in Phase 01.** Phase 06 says CameraX and the decoder were selected and reviewed in Phase 01. The Android build decision records ZXing Core 3.5.4 as the approved decoder, but lists CameraX 1.6.2 as a later candidate. The current live scanner uses platform Camera2 with the approved decoder, so it does not add CameraX. The camera path still needs physical-device and permission-denial evidence before the UI exit is claimed.
2. **Remote display order remains fixture-only here.** The Decky and Omarchy trees contain remote display-order route/client tests and compatibility fixtures. The shared remote-order plan still has status `planned-after-local-validation` and calls for hardware validation. Phase 06 may render its states from fixtures, but it must not invoke the host or claim that a saved order has been applied or observed.
3. **Save-current is in the pinned protocol.** The vendored contract includes `POST /v1/display/save-current`, with saved, stale-target, mutation-conflict, bridge-unavailable, and unconfirmed-readback cases. The UI fixture can ask the owner to confirm the visible picture, but performs no live request in this phase.
4. **The pinned visual source is available locally.** `site/styles.css` at `9c213b31cdce36e67784355be21e2fc1b2b7570f` matches the Decky repository's `1fdacc0` version. The pinned CSS defines the dark root palette and marketing gradients; the Android profile's darker primary/green gradients and light palette are deliberate app accessibility adaptations, not source-token mismatches.
5. **The mark forms are source-backed.** The pinned landing page's `i-mark` and `site/favicon.svg` use the same monitor-with-arrow path. The hero/header lockup is a white disc with navy mark; the favicon lockup is a blue-bright disc with white mark. These are the only two lockups used for app branding and launcher assets.
6. **The fonts are bundled from their licensed upstream files.** The Figtree and JetBrains Mono variable TTFs come from the Google Fonts repository and ship with their unmodified SIL OFL 1.1 texts in Android `res/raw`.
7. **The app icon family follows the exact pinned site's symbols.** `i-mark`, `i-phone`, `i-download`, `i-lock`, `i-check`, `i-radar`, `i-fingerprint`, `i-key`, `i-list`, `i-journal`, `i-shield`, `i-play`, `i-pause`, `i-prev`, `i-next`, `i-replay`, and `i-external` were read from Decky commit `9c213b31cdce36e67784355be21e2fc1b2b7570f` and ported as tintable Android vectors. `ic_status_clock`, `ic_status_unknown`, and `ic_status_blocked` add the design profile's stale/unknown/blocked state glyphs in the same stroke language.
8. **The motion-count requirement conflicts with its own profile.** AD4 describes three signature moments (discovery, code reveal, preview countdown), while the deliverables request a four-moment recording and the motion table also specifies a one-shot Wake packet animation. Inference for the Phase 06 recording: use those four listed moments—discovery sweep, code reveal, preview countdown, and Wake packet—and show each again with reduce motion enabled. Do not add looping effects; the profile's ordinary screen transitions and first-section reveal stay separately governed by their table timings.
9. **Copy was checked against its source before adding Android resources.** Discovery empty, no-network, and partial-scan copy now matches flows §4. Unsupported camera-permission and QR-error sentences were removed because the flow note does not define them; scanner permission/error inputs remain fixtures without invented display copy. Copy for the permission-denied scanner state must be added to the source flow before a rendered UI can pass R11.

| Resource | Upstream file | SHA-256 |
| --- | --- | --- |
| `font/figtree_wght.ttf` | [Google Fonts Figtree variable](https://raw.githubusercontent.com/google/fonts/main/ofl/figtree/Figtree%5Bwght%5D.ttf) | `26ad3db9b31ff7dde67a91ff515d022d2f495cd506590699cf264f0bfe6fb714` |
| `font/jetbrains_mono_wght.ttf` | [Google Fonts JetBrains Mono variable](https://raw.githubusercontent.com/google/fonts/main/ofl/jetbrainsmono/JetBrainsMono%5Bwght%5D.ttf) | `48715a42ec242c21e9f02692891e147d022299a52e48d5e413e1a942193ffeda` |
| `raw/ofl_figtree.txt` | [Google Fonts Figtree OFL](https://raw.githubusercontent.com/google/fonts/main/ofl/figtree/OFL.txt) | `140d37233e7f3ce7313798befa9600893bcceaf41a55fa0fa5ad52f7f657a268` |
| `raw/ofl_jetbrains_mono.txt` | [Google Fonts JetBrains Mono OFL](https://raw.githubusercontent.com/google/fonts/main/ofl/jetbrainsmono/OFL.txt) | `b2fe5e8987594e9ffd1d2ca52a2f5d73eb8335243893c5d6254b5ad69269591d` |

Resource verification: the four bundled font/license SHA-256 values match this table; AAPT2 resource compilation and linking the launcher/vector resources against the Android 37 platform succeeded. `:app:assembleBaselineDebug :app:assembleTarget37Debug` also passed, and both APKs contain the launcher lockups, icon vectors, variable fonts, and OFL files. This is resource/package validation only; Compose screens and screenshot gates remain outstanding.

## Build verification status

Gradle initially failed because the existing verification metadata lacked a checksum for the Kotlin build-tools classpath's `org.jetbrains.kotlinx:kotlinx-coroutines-bom:1.8.0` POM. The cached POM's SHA-1 matched the publisher's Maven Central `.sha1` sidecar; its SHA-256 is now recorded in `gradle/verification-metadata.xml`. The app lockfile also now records the already-reviewed BouncyCastle versions on each app unit-test runtime classpath; no dependency coordinates or versions changed.

Verified commands:

- `:app:testBaselineDebugUnitTest --write-locks` and `:app:testTarget37DebugUnitTest :core:test --write-locks` — passed; `:core:test` was up to date.
- `:app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` after the vector additions — passed.
- `:app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` after adding the allowlisted navigation snapshot, shared paired recovery state, token contrast/reduced-motion checks, `status.read` operation gating, and unknown save-current route gating — passed.
- `:app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` after registering fixture JSONs as test resources and parsing the complete indexed catalog — passed; the runtime test loads all 177 entries and checks the synthetic/non-secret envelope.
- `:app:processBaselineDebugResources :app:processTarget37DebugResources :app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest` after correcting the source-backed strings and making the paired recovery slot mutually exclusive — passed. AAPT2 caught and verified the Android apostrophe escaping fix.
- `--offline :app:assembleBaselineDebug :app:assembleTarget37Debug` — passed; APK contents were checked with `unzip -l`.
- AAPT2 resource compilation and link — passed (see Resource verification above).
- `git diff --check` — passed.

These earlier results covered the pure-Kotlin action gate, navigation model, token values, fixture inputs, and app resources. The subsequent approved Compose graph and production Activity are recorded below. The screen-state golden matrix, motion recording, and the remaining UI exit checks are still outstanding. Fixture JSON inputs and protocol-fixture path validation are present as described below.

## Component gallery and token audit

The production component set now includes `DangerAction`, `Pill`, `Badge`, `ProgressStrip`, `ChoiceCard`, `WireLog`, `StepRail`, `SegmentTabs`, and an icon-capable `InfoCard` alongside the existing primitives. `CompanionComponentGallery` renders the facts, cards, actions, buttons, and recovery sections from those production composables. Its Roborazzi harness uses Robolectric native graphics at 360 × 640, and 20 reviewed goldens cover all five sections in dark/light themes at 100%/200% font scale. These gallery captures are separate from the required 885 screen-state goldens and cannot substitute for them.

`scripts/phase06-token-audit.py` scans the Compose UI source for literal hex colors and named `Color` constants outside the central token file. `:app:verifyCompanionTokens` is attached to Gradle `check`; the current report covers nine UI files and 131 token references with zero findings. The code display reflows its two four-digit groups at narrow or enlarged type sizes and supplies one grouped screen-reader announcement. Discovery shows an indeterminate progress strip while scanning, offers cancellation, and rejects results from a canceled scan revision. Device-level code reflow, scan cancellation, motion, and full accessibility behavior remain to be checked.

Focused verification on 2026-09-28: the 12 gallery PNGs passed `:app:testBaselineDebugUnitTest --tests io.github.tuthan.steamoscompanion.android.ui.CompanionComponentGalleryCaptureTest` with `--rerun-tasks`; the token audit passed. The managed API 37 baseline suite passed after adding a resumed verification-code accessibility/`FLAG_SECURE` check and a scanner `FLAG_SECURE`/back check (47 reported tests, one skipped). The preview recovery card now follows the device header in the overview, with Restore directly below it and Wake below Display; Power is disabled while a preview or unresolved display result is present. First-viewport screenshot evidence for that live overview layout remains open.

The required inventory is seeded at `app/src/test/fixtures/phase06/screen-state-matrix.csv` with 177 screen/state rows across 13 destinations and named paths for 177 fixture JSON files and 885 goldens (dark/light at 100%/200% plus monochrome). All fixture JSONs are generated from that inventory using synthetic `.invalid` endpoints, non-secret sample identity/mode data, and no pairing payload secret; 38 protocol-derived screen cases name and validate 47 exact pinned contract-fixture files covering pairing, status, power, display recovery, Sunshine recovery, and display-order mutations. Scanner fixtures cover permission prompt/denial, a fixture-only viewfinder, malformed/unrecognized/expired payloads, and a detected-target handoff. The flow review added explicit cases for discovery cancellation, the pairing code mismatch hint, and a Cancel-focused save-current confirmation. `phase06/fixture-index.json` exposes the catalog to the JVM test classpath. `python3 scripts/phase06-fixture-matrix.py --check-inputs` validates the matrix, index, fixture JSONs, and protocol references (177/177 passed on 2026-09-28); the full inventory command still reports 885 golden PNGs missing. The current JSON sample data repeats across scenarios and does not yet encode each scenario's distinct render state. This is input/inventory validation, not evidence that any matrix screen has rendered.

## Gallery design review against the mockups (2026-09-28)

The 12 gallery goldens were compared with the seven artboards in the vault's `android/assets/SteamOS Companion Android Mockups.pdf` and with the design profile's rules. Findings and the changes made in the same pass:

| # | Finding | Rule | Change |
| --- | --- | --- | --- |
| 1 | Light-theme Keep and save used the `bg` token for its label, which is near-white in light mode (about 1.9:1 on the green gradient) | R10 | New `gradGreenLabel` token (`#1B2838` in both palettes); `PrimaryAction(green = true)` uses it |
| 2 | Disabled rows printed the same sentence twice when `secondary` and `reason` matched | AD8 | `ActionRow` suppresses a reason equal to its secondary |
| 3 | Connected state had no dot or green word; `StatusLine` tinted only the icon | AD7, R8, state vocabulary | `StatusLine(text, tone, glyph, detail)`: headline in the tone colour, `StatusGlyph` override (dot with glow, clock, slashed circle, key, fingerprint, info, question, pending ring, radar), dim qualifier after ` · ` |
| 4 | `ChoiceCard` selection was a border colour only | R8 | Radio ring plus blue 10 % fill |
| 5 | Wire-log direction chips were all dim outlines | Diagnostics anatomy | `WireDirection` carries arrow and tone: → blue Sent, ← green Received, ! amber Local, ! red Blocked |
| 6 | Preview countdown used the unknown-state ring glyph; no question, no draining strip, no device name | Preview card anatomy | `PreviewCard` gains `deviceName`, `question`, `totalSeconds`; digits render bold and tabular, amber for the last five seconds; `ProgressStrip` along the card top |
| 7 | Navigation rows had no chevron | Row anatomy | `ActionRow(navigates = true)` |
| 8 | Badges were outlined mixed case | Tag / badge type role | Uppercase, tracked, tone text on a 14 % tint, optional icon |
| 9 | `DangerAction` used a full-strength red border | Danger recipe | Red 40 % border, red 6 % fill; `ActionRow(danger = true)` for the Remove and Shut down rows |
| 10 | `PrimaryAction` / `GhostAction` could not carry an icon | Component catalogue | Optional 18 dp leading icon; disabled state at 45 % alpha |
| 11 | `StatusLine` forced a 48 dp height on non-interactive facts | Spacing | Sized to its text; the icon aligns with the first line |
| 12 | Gradient ran left to right | Gradient table | `−60°` gradient with the 1 px inset top highlight |
| 13 | `DeviceHeader` had no kicker or age qualifier | Overview anatomy | `kicker`, `glyph`, `detail` parameters |
| 14 | Titles used the `white` token, invisible on light cards | R10 | New `heading` token (white on dark, navy on light) |
| 15 | Kickers and badges grew without bound at 200 % | Typography (1.3× cap for decorative labels) | `cappedLabelStyle` caps kickers, badges, and tab labels at 1.3× |
| 16 | Back navigation was an `ActionRow` with a `←` glyph | Plugin header anatomy | `CompanionAppBar`: `bgDeep`, 8 dp back square, plain app title, status-bar inset |
| 17 | Onboarding was a kicker and two lines of text | Onboarding anatomy | `BrandLockup` (white disc, stacked wordmark), tagline, three `Pill`s, navy backdrop, iconed entry actions |

New stroke vectors in the site's language: `ic_chevron`, `ic_close`, `ic_status_dot`, `ic_status_info`, `ic_status_pending`, `ic_status_question`. New composables: `StatusGlyphIcon`, `CompanionAppBar`, `BrandLockup`, `FactTable` / `FactRow`, `ScopeBadges`. Live screens adopted the new options: overview rows navigate with chevrons and use the vocabulary glyphs; Display mode rows use `selected` plus a Current badge instead of a `✓` string; Connection details renders permissions as badges, the journal as a `WireLog`, and removal as a danger row; Power renders Shut down as a danger row with a red confirm; pairing shows the waiting line in blue with the pending ring, the codes-differ hint, and requested access chips.

Not changed, and still open: the 885 screen-state goldens, the four-moment motion recording, monochrome captures, first-viewport screenshots of the live overview, and the pairing expiry countdown (the candidate exposes `expiresAtMillis`, but the ticking countdown is Phase 07 live behaviour). The mockup's Display order tab and Sunshine process card remain fixture-only per findings 2 and the Phase 06 plan. The vault phase note still says pending; nothing here closes an exit check.

Verification on 2026-09-28 after the changes: `PHASE06_RECORD=1` re-recorded the 20 gallery PNGs, which were reviewed in both themes and both font scales before being promoted; `:app:testBaselineDebugUnitTest :app:testTarget37DebugUnitTest :app:verifyCompanionTokens --rerun-tasks` passed with 50 reported tests and the gallery test in verify mode; the token audit reports 131 token references and zero literal colours.

## Dependency review record

The proposed stack is the project's queued Phase 06 candidate set: Compose BOM `2026.09.00`, Material 3, `activity-compose` `1.13.0`, `lifecycle-viewmodel-compose` `2.11.0`, the Compose compiler plugin matching Kotlin `2.4.20`, and a Compose-capable screenshot setup using Roborazzi's `roborazzi` and `roborazzi-compose` modules at `1.73.0` with Robolectric `4.17`. Compose UI preview/tooling and UI-test artifacts would be versioned by the BOM. Navigation can use a small in-app route graph, so no separate navigation library is required.

The queued Roborazzi `1.75.0` candidate was not published on the review date; `1.73.0` is the latest stable version listed by Maven Central on 2026-09-26. The approval request was corrected to this version.

No `depscore` MCP tool is exposed in this session, so the dependency-guard skill's Socket CLI fallback was used. Socket CLI `1.1.176` was reachable for read-only score attempts, but its deep-score API returned `404 / no result` for:

- `pkg:maven/androidx.compose/compose-bom@2026.09.00`
- `pkg:maven/androidx.compose.material3/material3@1.4.0`
- `pkg:maven/io.github.takahirom.roborazzi/roborazzi@1.73.0`
- `pkg:maven/io.github.takahirom.roborazzi/roborazzi-compose@1.73.0`

These responses contain no category scores, alert/capability data, install-hook information, or transitive graph. The `activity-compose@1.13.0` attempt was rate-limited (HTTP 429). Other planned AndroidX/test artifacts have not been scored. These are unscored, not clean results. The user accepted the missing Socket evidence on 2026-09-28 after reviewing this candidate set. The dependency-guard matrix classification remains `block_pending_human_review`, with the human disposition recorded in `docs/build-decision.md`. The approved coordinates are now in the catalog and app manifest; the resolved Gradle classpaths are locked and SHA-256 verified.

The dependency coordinates themselves were fact-checked against the official Android Developers release records on 2026-09-26: the Compose BOM page lists `2026.09.00`; Material 3 lists stable `1.4.0` (updated 2026-09-23); Activity confirms `activity-compose:1.13.0` (released 2026-03-11); Lifecycle lists stable `2.11.0` (updated 2026-09-23) and its Compose/ViewModel artifact. This confirms publication/version availability only; it does not replace Socket scoring or human disposition. Sources: [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Activity release notes](https://developer.android.com/jetpack/androidx/releases/activity), [Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle).

A temporary Android Gradle project successfully resolved the proposed AndroidX/Compose and screenshot dependency graph and generated Socket facts for 334 components. The facts file is 174,806 bytes, SHA-256 `6db40a43148d5020459a800bdc0a5c28ad76c68d9f1a031af5e825b8170921b1`; a local content check found no reference to the Android repository path, `/home` paths, source filenames, or secret markers. The scratch directory contains only the generated facts file and temporary Gradle project metadata; no source, credentials, or repository files were copied into it. No dependency scan report was uploaded. An explicit-target retry first failed locally because Socket's Gradle discovery could not determine a usable wildcard IP. An elevated retry was then rejected by automatic review because uploading this payload to the external Socket account was not explicitly authorized. The user subsequently accepted the dependency set without Socket scores; that approval did not authorize a Socket upload. This result adds no package scores and leaves the matrix classification at `block_pending_human_review` with a recorded human disposition.

## Token comparison

| App token | Pinned site source | App value | Result |
| --- | --- | --- | --- |
| `bg` | `--bg` | `#1B2838` | Exact |
| `bgDeep` | `--bg-deep` | `#171A21` | Exact |
| `panel` | `--panel` | `#16202D` | Exact |
| `panel2` | `--panel-2` | `#1C2B3B` | Exact |
| `line` | `--line` | white at 8% | Exact |
| `lineStrong` | `--line-strong` | white at 15% | Exact |
| `text` | `--text` | `#C6D4DF` | Exact |
| `textSoft` | `--text-soft` | `#DFE6EC` | Exact |
| `textDim` | `--text-dim` | `#8F98A0` | Exact |
| `blue` | `--blue` | `#66C0F4` | Exact |
| `blueBright` | `--blue-bright` | `#1A9FFF` | Exact |
| `blueDeep` | `--blue-deep` | `#417A9B` | Exact |
| `green` | `--green` | `#A1CD44` | Exact |
| `red` | `--red` | `#E57373` | Exact |
| `amber` | `--amber` | `#FFB321` | Exact |
| `mono` | `.q-mono` / `.wire` | `#9FD3F5` | Exact |
| `navy` | hero background / mark | `#0D2C5E` | Exact |
| `field` | `.q-btn` | `#1B2531` | Exact |
| `slab` | `.wire` / `.relay` | `#0F1822` | Exact |
| `gradPrimary` | `--grad-btn` | `#3579AB → #254F6E` | Deliberately darkened for ≥4.5:1 white-label contrast |
| `gradGreen` | `--grad-green` | `#A1CD44 → #7FA82A` | Dark-label contrast is 8.05:1 at the light stop and 5.36:1 at the dark stop; the site endpoint `#5C7E10` would be 3.16:1 |
| Light theme tokens | None | Derived palette | Intentional: site has no light theme |

The source site declares Motiva Sans before Figtree in its CSS fallback list. The app follows the adopted Android profile by bundling Figtree and JetBrains Mono, with the latter used only for protocol facts, codes, and mode values. The pinned app gradients also meet their declared worst-stop contrasts: white on `gradPrimary` is 4.69:1 / 8.67:1; `bg` on `gradGreen` is 8.05:1 / 5.36:1.

## Phase exit audit (current)

| Phase requirement | Current evidence | Status |
| --- | --- | --- |
| Production Compose theme, components, navigation, and ViewModels | Approved dependencies, theme, component gallery primitives, launchable Activity, `SavedStateHandle` navigation, onboarding, and Phase 08–10 action ViewModels compile; full design checks remain | Partial |
| Render every inventoried screen/state from fixtures | The JVM test runtime parses all 177 fixture JSON inputs; 38 protocol-backed cases validate; no production renderer | Inputs complete, rendering incomplete |
| Keep preview/unresolved cards reachable across paired destinations | The overview and Display screen render durable preview/pending and display-operation projections; reachability across every paired destination has not been verified | Partial |
| Restore non-secret navigation after process death | The allowlisted route graph is wired to `SavedStateHandle`; a rendered process-death navigation journey has not run | Integration compiled, runtime evidence incomplete |
| Scanner and confirmation UI with fixture coverage | Power, Keep, save-current, scanner, and pairing screens are wired to production flows; fixture interaction and live camera/pairing evidence remain absent | Partial |
| Component gallery and screenshot matrix | Production-component gallery and 12 dark/light, 100%/200% Roborazzi goldens are present; 177 fixture inputs have no screen-state renderer or 885 matrix goldens | Gallery partial; matrix incomplete |
| Launcher icon set and two brand lockups | Vector resources exist, were checked against pinned site paths, AAPT2-compiled, and included in baseline/target-37 APKs | Assets verified |
| Four-moment motion recording, reduced motion off/on | Motion duration tokens and reduced-motion unit test exist; recording and UI toggle exercise are absent | Incomplete |
| Token diff at pinned Decky commit | Token comparison table records CSS/app values and deliberate contrast adaptations | Complete |
| R1 token fidelity / no literal hex in composables | Token audit scans nine UI Kotlin files, reports 98 token references and zero inline-color findings; full visual token review remains | Source audit passed; visual review pending |
| R2 one primary action per screen | Initial screens exist; full fixture/screenshot review has not run | Incomplete |
| R3 elevation assertion | Component code keeps shadow on PreviewCard; no Compose assertion yet | Incomplete |
| R4 monospace facts | Endpoint/fingerprint components use the bundled mono font; screen review remains | Incomplete |
| R5 compare/payload `FLAG_SECURE`, code reflow and reflow goldens | Managed API 37 checks passed for resumed comparison code semantics and secure flag, plus scanner secure flag and clearing on back. Gallery recovery goldens show 200% two-row code; physical-device reflow and payload-pairing route evidence remain | Partial |
| R6 first-viewport recovery at 360×640 dp and 200% | Overview recovery card moved directly below device header; no first-viewport screen-state capture or assertion yet | Incomplete |
| R7 motion budget and reduce-motion UI | Duration helper test passes; animation recording and UI behavior are absent | Incomplete |
| R8 word, glyph, and color in monochrome | Status vectors exist; no monochrome golden PNGs | Incomplete |
| R9 Figtree/JetBrains Mono and font scaling | Both variable fonts and OFL texts are bundled and verified; gallery components have 100%/200% captures, while the 177 screen states do not | Assets and gallery verified; screen evidence incomplete |
| R10 contrast in both themes and gradients | Pure Kotlin contrast checks pass for token pairs and gradient stops; pixel sampling on rendered goldens is absent | Token values verified, screenshot evidence incomplete |
| R11 copy against the flows note | Resource copy was source-checked; discovery text was corrected and unsupported scanner wording removed. No rendered string review exists, and the flow source lacks camera-permission-denied copy | Source copy reviewed, rendered review incomplete |
| R12 sanctioned brand lockups | Hero/favicon marks and adaptive icon assets match the pinned visual source | Assets verified |
| Accessibility: touch targets, screen-reader order, keyboard/back, confirmations | Five managed API 37 Activity checks passed for onboarding, paired routes, and synthetic replacement confirm/cancel; full interaction and ordering evidence remain | Incomplete |
| Exact build/protocol revisions and evidence | Protocol pin and dependency disposition are recorded; baseline/target-37 compilation and five managed API 37 Activity checks passed. Full screen captures remain absent | Partial |

The Activity route checks do not close any row marked incomplete. Phase 06 remains pending until the production tree renders all fixtures, screenshot/motion/accessibility evidence is produced, and the full checklist passes.
