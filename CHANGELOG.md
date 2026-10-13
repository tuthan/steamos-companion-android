# Changelog

Every user-visible feature and fix in the Android client, in one place. The per-phase records in [`docs/`](docs/) hold the evidence, the tests and the reasoning; this file points at them. No version has been released: `versionName` is `0.0.1` and `versionCode` is 1 (see [`docs/store-release-readiness.md`](docs/store-release-readiness.md) for what a first release still needs).

**Verification key.** *JVM* means unit and Robolectric tests only. *Emulator* means a managed device or AVD run. *Device* means a physical phone against a real Steam Deck. At 2026-10-13 **no item below has a recorded *Device* acceptance result** (no dated report, no diagnostics export). The app is in use on real devices, and on 2026-10-13 the owner confirmed that the fixes prompted by a tester's phone worked there; that is an owner statement, marked *Device (owner statement)* where it applies.

## Unreleased: first release candidate

Code: `main` at `7ac43e4` plus an uncommitted tree dated 2026-10-05 (23 modified files, 28 new). Host contract: Decky `main@0219c27` (161 files, `protocol/SOURCE.json`). Verified 2026-10-13: `check` plus both release variants pass offline (`:core` 684 tests, `:app` 230 per flavor, no failures or skips).

### Pairing and connection

- **Discovery and manual address.** Bounded `/24` scan of the Wi-Fi and Ethernet paths, never over a VPN; a manual address is probed first. Phase 05 · [`phase-05-evidence.md`](docs/phase-05-evidence.md). *JVM; emulator against a fake host.*
- **Both pairing variants.** Nonce pairing with a host-derived comparison code (scrypt) and QR or pasted payload pairing, both certificate-pinned; the phone clock cannot extend a pairing (`dcc6e16`). Phase 05, Phase 07 · [`phase-07-progress.md`](docs/phase-07-progress.md). *JVM, emulator.*
- **Offline QR scanner.** Camera2 with ZXing Core, no ML Kit, no network; a camera failure shows its reason and keeps the paste field (2026-10-05). *JVM only; never run with a physical camera.*
- **Pairing expiry.** "Expires in 1:57" under the code, amber for the last ten seconds, red **Expired**; never announced to TalkBack (2026-10-05). `PairingExpiryTest`. *JVM.*
- **Connection repair.** Rename locally, Edit address (saved only after the device proves its host ID and pin), Find device again (a changed pin is reported, never saved), and Revoke versus Forget as two unselected choice cards (2026-10-05). [`phase-11-progress.md`](docs/phase-11-progress.md) · Docs-vs-code audit gap closure. *JVM.*
- **Replace a pairing** as one transaction: a candidate host is approved and swapped, or cancelled at any stage, without losing the old pairing. Phase 10 · [`phase-08-10-progress.md`](docs/phase-08-10-progress.md). *JVM, emulator process-death run.*
- **Saved pairing recovery** (2026-10-05): a damaged file or lost key shows **Saved pairing unavailable** with a confirmed local **Reset local pairing**; a transient key problem shows Try again and never offers to erase; a newer schema shows **App version mismatch** and is never overwritten. *JVM.*
- **Encrypted state.** Android Keystore AES-256/GCM, schema v6 with migrations from v0, backup and device transfer off, cleartext off, system CAs only. Phase 04 · [`phase-04-evidence.md`](docs/phase-04-evidence.md). *JVM, emulator.*
- **Android local-network permission** (`NEARBY_WIFI_DEVICES`, `maxSdkVersion` 36, `neverForLocation`; the `target37` flavor adds `ACCESS_LOCAL_NETWORK`), with Android 16 wording where the platform classifies the failure (2026-10-05). Phase 02 · [`phase-02-evidence.md`](docs/phase-02-evidence.md). *Emulator (API 26 to 37); no physical roster.*
- **Credential scopes** (`399a4d9`, `c6a033c`, `11bc201`, `aff0557`, 2026-10-04): the app adopts the scopes the host reports; Power and Display controls the owner has not allowed are disabled with the reason; **Access on this host** appears in Connection details; a 403 reads "The host owner has not allowed this action for this pairing" and never removes the pairing. *JVM; no real host reports `credential` yet (Decky `main`, unreleased).*

### Status and diagnostics

- **Foreground status polling** with network-change reads, ephemeral source identity per credential, and unreachable handling. Phase 07. *JVM, emulator; one phone paired and showed status.*
- **Host health** (`877c4bd`): certificate, listener, Steam bridge, temperature, Sunshine, plugin and protocol versions, with word, glyph and colour for every state; never shown stale. *JVM; Decky `v0.5.18` hosts do not send it.*
- **Host operation journal** (`e8d5b12`, `95c9e15`): what the host recorded, read-only, with the app's own log renamed **Requests from this phone**. *JVM; older hosts answer 404 and the section says so.*
- **Display link advisory** (`9604c69`): "Heads up" before a preview the display link never listed, and the physical link line on the Resolution tab; advisory only, never blocks. *JVM.*
- **Redacted diagnostic export** (`2fcb539`, `format_version` 3): explicit export through the document picker, bounded to 12 KiB, byte-for-byte equal to the cross-client vector; nothing is sent anywhere. *JVM.*

### Power, Wake and Sunshine

- **Power** (Suspend, Restart, Shut down) with a confirmation bound to the exact target and verb. A request the host accepts is shown as "accepted by the device"; suspend and shutdown settle when the device then goes offline, restart when a new boot ID returns (2026-09-30). `PowerSettlementTest`. *JVM for the settlement logic; the tester ran Suspend and Restart from a phone against a Deck on 2026-09-30, which is how the `observed_return` lane block was found, but no acceptance run is recorded.*
- **Sunshine recovery** only when the process is observed stopped, with distinct reasons for monitoring off, provider unavailable and unknown state. *JVM.*
- **Wake** sends the 102-byte packet and keeps three separate facts: packet sent, authenticated response, Steam ready, with a 30-second no-response state and no automatic resend. *JVM, emulator (`WakeSendLiveTest` on API 36).*
- **Wake over a VPN** (`0c5ea8b`, 2026-09-30): VPN networks are tunnel paths; the packet is unicast to a per-host relay saved in Connection details, pre-filled (not saved) with the LAN gateway. Wake through a Raspberry Pi relay was reported working by the tester on 2026-09-30. [`docs/wake-over-vpn.md`](docs/wake-over-vpn.md). *Device (tester), not a recorded acceptance run.*
- **Wake failures say why**: per-reason messages and a bounded platform detail line (`SocketException: … EPERM`), and a refused destination no longer stops the next one (`Partial` versus `Failed`).

### Display recovery and order

- **Preview, Keep, Revert, Restore, Save current** with journaled, distinct requests, host-deadline anchoring, no automatic Revert after an ambiguous Keep, and the verified-profile Restore reachable during the client's own preview. Phase 09. *JVM, emulator; the blind-recovery journey on a TV has not run.*
- **Display screen tabs**: Resolution, Saved, Display order; Preview sits under the selected mode and Restore under the selected saved row. *JVM.*
- **Recovery profiles** are shown deduplicated by output, resolution and refresh rate, newest first; the host's list is parsed without the old eight-profile cap, and the offline cache keeps the last eight distinct entries (`fdb93fd`). *JVM; Device (owner statement); the tester's ninth-profile report is the origin.*
- **Display order editing** (`03eddf9`): numbered priority rows, Save for next session, Save and restart Gaming Mode behind a confirmation that names the target, Use automatic order; a stale generation or connected set marks the draft stale. *JVM, emulator; never run against a two-monitor host.*
- **Host labels are tolerated**: empty names and descriptions are accepted, control characters become spaces, a blank label falls back to the output ID, and a failed read shows a bounded reason (`98b19b5`, `cfdb617`).

### Operation journal and recovery from a stuck state

- **Journal with reconciliation**: a pre-dispatch commit, read-only reconciliation by operation ID, no replay of an unknown result. Phase 04, Phase 08–10.
- **Kept or reverted previews no longer wedge the screen** (`03eddf9`): the host leaves the original preview operation open after Keep or a completed revert; the app closes it locally. A preview that times out is closed only when the host reports a verified restore.
- **Accepted asynchronous operations** (restore, revert) are re-read every 3 s, at most 20 times per entry, while the paired screen is visible.
- **Power requests no longer block display or order changes** after the host ends them at `observed_return`.
- **Mark as reviewed** (`0c5ea8b`): a confirmed way out for an entry that can never settle (no operation ID, legacy entry, host-pruned record), offered at the top of the Display, Power and Sunshine screens whenever the lane is blocked; it resends nothing and keeps the recorded phase. The Display order tab uses the same lane check as the dispatcher.
- **Failures keep the host's reason** ("failed: Steam rejected display mode") and the journal line shows "Not sent: …" when no byte left the phone.
- **Control requests try every network in order**: the network holding the host first, then a VPN tunnel, moving on only after a request was provably not sent (`HostPaths`); a tunnel path the platform refuses to bind no longer fails and follows the default network. Reported by the tester over a VPN; *Device (owner statement 2026-10-13)*.

### Design, accessibility and assets

- Compose UI with the site's tokens (checked against Decky `styles.css` by `SiteTokenDiffTest`), system light and dark, bundled Figtree and JetBrains Mono (OFL texts in `res/raw`), Dynamic type to 200 %, polite live regions, `StepRail`, `ChoiceCard`, and four motions that cut under reduced motion. Phase 06 · [`phase-06-review.md`](docs/phase-06-review.md). *JVM only; 20 gallery goldens, none for individual screens.*
- Launcher icon no longer cropped by the launcher mask (`018b8a8`).
- `FLAG_SECURE` on the comparison-code and scanner screens.

### Build, supply chain and process

- Two flavors on compileSdk 37: `baseline` (target 36, the release candidate) and `target37` (permission test configuration). Release variants are R8-shrunk and **unsigned**.
- Vendored Decky protocol re-pinned seven times in `0ca0ba2`..`a0ba034`, now at `0219c27`; byte-identity and coverage verifiers pass (161 files, 154 cases, 19 routes, 157 fixtures). [`docs/protocol-coverage.md`](docs/protocol-coverage.md).
- Dependency dispositions in [`docs/build-decision.md`](docs/build-decision.md); locks and SHA-256 verification committed. No analytics, advertising, crash-reporting or billing library is present.
- CI runs on GitHub (`tuthan/steamos-companion-android`, public). The first run on 2026-10-13 failed twice for reasons found only on a cold cache: four buildscript-classpath checksums were missing from `gradle/verification-metadata.xml` (`691a3e7`), and the operation-review tests dropped their own calls on a slow runner (`f7c67f6`). The third run is green (`jvm`, 9 min 26 s). Warm-cache offline builds hid both.

## Known issues and unverified claims

- **No recorded device acceptance exists.** The Phase 11 hardware procedure as a dated report, TalkBack, 200 % text on a phone and the usability sessions are open ([`docs/phase-11-progress.md`](docs/phase-11-progress.md), Open acceptance gates).
- **Tester-reported failures: fixed on the phone, causes never observed.** On 2026-10-13 the owner confirmed the fixes worked on a real device: display lists with more than eight profiles, the kept-preview banner, display order and restart, Suspend and Restart settling, and previews over the VPN (Wake through the relay was reported working on 2026-09-30). No diagnostics export was ever taken, so the original causes of the Android 16 display read, the stuck "needs review" lane, the first Wake failure and the black screen after an order change remain inferred from code review, not observed.
- **19 text pairs below 4.5:1** on the profile's tint fills (lowest 3.67:1), pinned as a ratchet in `KnownContrastFindings` pending a design decision (A06·D1).
- **Not built**: an About screen (version, licences, privacy link, the not-affiliated line), a Grant-permission action for the Android 16 wording, Request again on Expired and Rejected, the onboarding tile collage and device frame, the 885 screen-state goldens, the monochrome set.
