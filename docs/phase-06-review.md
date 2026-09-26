# Phase 06 source review

Status: implementation in progress. Compose dependency review is waiting on an explicit human disposition because Socket returned no package scores for the checked AndroidX/Compose and screenshot artifacts.

## Inputs checked

- Phase plan: `steamos-companion/android/phases/06-compose-and-fixture-screens.md`.
- Shared UI behavior and copy: `steamos-companion/10-decky-client-ux-and-flows.md`, sections 4–12.
- Android visual profile: `steamos-companion/android/design-profile.md`.
- Android implementation contract: `steamos-companion/android/implementation-spec.md`.
- Protocol source pin: host v0.5.16, commit `9c213b31cdce36e67784355be21e2fc1b2b7570f`; repo fixture source is recorded in `protocol/SOURCE.json`.

## Findings and implementation boundaries

1. **CameraX is not approved in Phase 01.** Phase 06 currently says CameraX and the decoder were selected and reviewed in Phase 01. The Android build decision records ZXing Core 3.5.4 as the approved decoder, but lists CameraX 1.6.2 as a later candidate. Phase 06 therefore renders scanner, payload-pairing, and camera-permission-denied fixtures only; live camera capture remains disconnected pending its own dependency review and Phase 07 integration.
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

These results cover the pure-Kotlin action gate, navigation model, token values, fixture inputs, and current app resources only. The navigation model is not yet connected to Android `SavedStateHandle` or any ViewModel. Compose dependencies, production composables/ViewModels, golden screenshots, motion recording, and the remaining UI exit checks are still outstanding. Fixture JSON inputs and protocol-fixture path validation are now present as described below.

The required inventory is seeded at `app/src/test/fixtures/phase06/screen-state-matrix.csv` with 177 screen/state rows across 13 destinations and named paths for 177 fixture JSON files and 885 goldens (dark/light at 100%/200% plus monochrome). All fixture JSONs are generated from that inventory using synthetic `.invalid` endpoints, non-secret sample identity/mode data, and no pairing payload secret; 38 protocol-derived screen cases name and validate 47 exact pinned contract-fixture files covering pairing, status, power, display recovery, Sunshine recovery, and display-order mutations. Scanner fixtures cover permission prompt/denial, a fixture-only viewfinder, malformed/unrecognized/expired payloads, and a detected-target handoff. The flow review added explicit cases for discovery cancellation, the pairing code mismatch hint, and a Cancel-focused save-current confirmation. `phase06/fixture-index.json` exposes the catalog to the JVM test classpath. `python3 scripts/phase06-fixture-matrix.py --check-inputs` validates the matrix, index, fixture JSONs, and protocol references; the full inventory command still reports 885 golden PNGs missing. This is input/inventory validation, not evidence that any screen has rendered.

## Dependency review record

The proposed stack is the project's queued Phase 06 candidate set: Compose BOM `2026.09.00`, Material 3, `activity-compose` `1.13.0`, `lifecycle-viewmodel-compose` `2.11.0`, the Compose compiler plugin matching Kotlin `2.4.20`, and a Compose-capable screenshot setup using Roborazzi's `roborazzi` and `roborazzi-compose` modules at `1.73.0` with Robolectric `4.17`. Compose UI preview/tooling and UI-test artifacts would be versioned by the BOM. Navigation can use a small in-app route graph, so no separate navigation library is required.

The queued Roborazzi `1.75.0` candidate was not published on the review date; `1.73.0` is the latest stable version listed by Maven Central on 2026-09-26. The approval request was corrected to this version.

No `depscore` MCP tool is exposed in this session, so the dependency-guard skill's Socket CLI fallback was used. Socket CLI `1.1.176` was reachable for read-only score attempts, but its deep-score API returned `404 / no result` for:

- `pkg:maven/androidx.compose/compose-bom@2026.09.00`
- `pkg:maven/androidx.compose.material3/material3@1.4.0`
- `pkg:maven/io.github.takahirom.roborazzi/roborazzi@1.73.0`
- `pkg:maven/io.github.takahirom.roborazzi/roborazzi-compose@1.73.0`

These responses contain no category scores, alert/capability data, install-hook information, or transitive graph. The `activity-compose@1.13.0` attempt was rate-limited (HTTP 429). Other planned AndroidX/test artifacts have not been scored. These are unscored, not clean results. Per the dependency-guard decision matrix, the proposed set remains `block_pending_human_review` until a human accepts the missing Socket evidence or supported reports become available. No Compose candidate coordinate has been added to a manifest or lockfile. The existing app lockfile and verification-metadata adjustments described above are limited to already-reviewed BouncyCastle resolution and the missing coroutines BOM checksum.

The dependency coordinates themselves were fact-checked against the official Android Developers release records on 2026-09-26: the Compose BOM page lists `2026.09.00`; Material 3 lists stable `1.4.0` (updated 2026-09-23); Activity confirms `activity-compose:1.13.0` (released 2026-03-11); Lifecycle lists stable `2.11.0` (updated 2026-09-23) and its Compose/ViewModel artifact. This confirms publication/version availability only; it does not replace Socket scoring or human disposition. Sources: [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Material 3 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3), [Activity release notes](https://developer.android.com/jetpack/androidx/releases/activity), [Lifecycle release notes](https://developer.android.com/jetpack/androidx/releases/lifecycle).

A temporary Android Gradle project successfully resolved the proposed AndroidX/Compose and screenshot dependency graph and generated Socket facts for 334 components. The facts file is 174,806 bytes, SHA-256 `6db40a43148d5020459a800bdc0a5c28ad76c68d9f1a031af5e825b8170921b1`; a local content check found no reference to the Android repository path, `/home` paths, source filenames, or secret markers. The scratch directory contains only the generated facts file and temporary Gradle project metadata; no source, credentials, or repository files were copied into it. No dependency scan report was uploaded. An explicit-target retry first failed locally because Socket's Gradle discovery could not determine a usable wildcard IP. An elevated retry was then rejected by automatic review because uploading this payload to the external Socket account was not explicitly authorized. Stop here pending explicit authorization; do not retry through a different command or route. This result adds no package scores and leaves the proposed set at `block_pending_human_review`.

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
| Production Compose theme, components, navigation, and ViewModels | Pure Kotlin tokens, action gate, route model, and paired recovery-state model compile; no Compose dependency disposition, Activity, composables, or ViewModels yet | Incomplete |
| Render every inventoried screen/state from fixtures | The JVM test runtime parses all 177 fixture JSON inputs; 38 protocol-backed cases validate; no production renderer | Inputs complete, rendering incomplete |
| Keep preview/unresolved cards reachable across paired destinations | Pure paired-surface model returns both cards on each paired destination; no Compose integration | Model verified, integration incomplete |
| Restore non-secret navigation after process death | Allowlisted route snapshot round-trips and rejects credentials, malformed endpoints, and invalid transitions; not wired to `SavedStateHandle` | Model verified, integration incomplete |
| Scanner and confirmation UI fixture-only; no live calls before Phase 07 | Scanner, power, and save-current fixture cases exist; no UI is present to exercise | Incomplete |
| Component gallery and screenshot matrix | 177 fixture inputs are present; 885 goldens and the Compose screenshot harness are absent | Incomplete |
| Launcher icon set and two brand lockups | Vector resources exist, were checked against pinned site paths, AAPT2-compiled, and included in baseline/target-37 APKs | Assets verified |
| Four-moment motion recording, reduced motion off/on | Motion duration tokens and reduced-motion unit test exist; recording and UI toggle exercise are absent | Incomplete |
| Token diff at pinned Decky commit | Token comparison table records CSS/app values and deliberate contrast adaptations | Complete |
| R1 token fidelity / no literal hex in composables | No composables exist, so lint/token-usage evidence cannot yet be produced | Incomplete |
| R2 one primary action per screen | No rendered screens to review | Incomplete |
| R3 elevation assertion | No Compose surfaces or assertion | Incomplete |
| R4 monospace facts | Font and mono color tokens exist; screen rendering is absent | Incomplete |
| R5 compare/payload `FLAG_SECURE`, code reflow and reflow goldens | Pairing route model and fixture cases only; no Activity, window flag, or rendered code | Incomplete |
| R6 first-viewport recovery at 360×640 dp and 200% | No device/emulator screenshot evidence | Incomplete |
| R7 motion budget and reduce-motion UI | Duration helper test passes; animation recording and UI behavior are absent | Incomplete |
| R8 word, glyph, and color in monochrome | Status vectors exist; no monochrome golden PNGs | Incomplete |
| R9 Figtree/JetBrains Mono and font scaling | Both variable fonts and OFL texts are bundled and verified; 100%/200% rendered captures are absent | Assets verified, UI evidence incomplete |
| R10 contrast in both themes and gradients | Pure Kotlin contrast checks pass for token pairs and gradient stops; pixel sampling on rendered goldens is absent | Token values verified, screenshot evidence incomplete |
| R11 copy against the flows note | Resource copy was source-checked; discovery text was corrected and unsupported scanner wording removed. No rendered string review exists, and the flow source lacks camera-permission-denied copy | Source copy reviewed, rendered review incomplete |
| R12 sanctioned brand lockups | Hero/favicon marks and adaptive icon assets match the pinned visual source | Assets verified |
| Accessibility: touch targets, screen-reader order, keyboard/back, confirmations | No Compose UI test or device interaction evidence | Incomplete |
| Exact build/protocol revisions and evidence | Protocol pin is recorded; baseline/target-37 JVM tests and resource/APK checks passed, but screen build variants and runtime captures do not exist | Partial |

The current source-only tests do not close any row marked incomplete. Phase 06 remains pending until the Compose dependency disposition is recorded, the production tree renders all fixtures, screenshot/motion/accessibility evidence is produced, and the full checklist passes.
