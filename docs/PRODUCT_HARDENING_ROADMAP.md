# Wasti AirPad — Product Hardening & Engineering Roadmap

**Baseline:** `main` at `db78a4ad1e124fca93b45459657f50b91ccf45d7`  
**Baseline version:** 1.1.3  
**Working branch:** `upgrade/reliability-product-quality`  
**Audit date:** 2026-11-25

## Executive assessment

The upgrade branch contains the Android 16 toolchain update, HID lifecycle/report hardening, extracted HID report and gesture-policy modules, unit tests, local preferences, click controls, protected release signing, and an official Gradle 8.13 Wrapper with the published distribution SHA-256 pinned. The signed-release workflow validates strict Base64 input, JKS store password, private-key alias, APK v2 signature, signer certificate identity, and APK alignment; temporary signing files are removed after the job. Run [45634634281](https://github.com/Nabeelwasti/Wasti-AirPad/actions/runs/45634634281) passed both jobs before the latest Wrapper/CI hardening changes. A new full CI run on the current branch head is required to validate those final changes. Physical Bluetooth HID compatibility and longer-duration device testing remain intentionally pending.

This roadmap deliberately separates verified facts from work that requires physical hardware. A green CI build proves that the source compiles and automated tests pass; it does not prove that a phone exposes the HID Device profile or that every desktop accepts the reports.

## Verified baseline

- Package: `com.wasti.airpad`; minimum Android API 28; target/compile API 36 on the upgrade branch.
- Upgrade toolchain: Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13, JDK 17.
- `MainActivity.kt` still owns the Activity, Bluetooth profile lifecycle, programmatic UI, drawing, and touch-event orchestration. HID descriptor/report encoding and gesture thresholds are extracted into separate pure Kotlin modules.
- Bluetooth HID mouse and keyboard reports use report IDs 1 and 2; report submission results are now checked and failure messaging is rate-limited.
- Activity teardown and HID registration failure paths have defensive cleanup guards. This does not replace device-level lifecycle testing.
- Unit tests cover report encoding/bounds/descriptor markers and tap/pinch/swipe policy thresholds. Full MotionEvent state-machine, UI, and Android instrumentation coverage is still outstanding.
- Pointer sensitivity and natural scrolling are persisted with SharedPreferences; DataStore migration is not implemented.
- CI runs unit tests, Android Lint, debug and unsigned release builds in an independent verification job. A separate signing job validates protected Actions secrets, JKS format, store password and alias before building and uploading the signed release. Malformed signing secrets fail only the signing job and no longer prevent the automated app checks from running; absent secrets leave the unsigned artifacts available.
- The original baseline CI run [45611559284](https://github.com/Nabeelwasti/Wasti-AirPad/actions/runs/45611559284) passed. The latest upgrade CI run must pass before merge; see [PR #1](https://github.com/Nabeelwasti/Wasti-AirPad/pull/1). Run [45631031747](https://github.com/Nabeelwasti/Wasti-AirPad/actions/runs/45631031747) passed both jobs and uploaded a signed APK after the secret was corrected; its previous APK verification command was inadequate. The replacement `apksigner` plus certificate-match validation passed in run [45634038228](https://github.com/Nabeelwasti/Wasti-AirPad/actions/runs/45634038228).
- There is no INTERNET permission and no analytics/network SDK in the inspected manifest/dependency list.

## Findings and risks

### P0 — Correctness and release blockers

1. **HID report send result is now checked.** `BluetoothHidDevice.sendReport()` acceptance is observed and failures are surfaced once until the next successful host connection. Device/host delivery still needs hardware verification.
2. **Registration is asynchronous.** A successful `registerApp()` return only means the request was accepted; `onAppStatusChanged()` is authoritative. Model explicit states (unsupported, permission required, Bluetooth off, profile opening, registering, ready, connected, disconnected, error) and prevent connection/report actions until their prerequisites are true.
3. **Lifecycle callback hardening is partial.** Late callbacks are guarded after Activity teardown and registration failures close the proxy. A complete lifecycle state machine and repeated background/foreground/process-death testing remain outstanding.
4. **Physical compatibility is unverified.** Test the actual phone firmware and at least Windows 11 plus one additional host OS before making compatibility claims.
5. **APK signature verification was corrected.** The signing secrets now validate and Gradle produces a release APK. The previous `jarsigner` check was not a valid APK-signature check; the workflow now requires Android `apksigner` v2 verification and a certificate fingerprint match against the configured JKS alias. The corrected check passed in run 45634038228. Never add a keystore, passwords, or signing material to Git.

### P1 — Testability and maintainability

1. Extract pure HID report encoding/descriptor validation from Android UI code.
2. Extract gesture recognition into a platform-independent state machine that can be tested with deterministic pointer event sequences.
3. Introduce a Bluetooth HID controller/repository boundary. Keep Android framework calls out of gesture logic.
4. Add tests for tap timing, double-tap drag, cancellation, pointer-count transitions, scroll direction, pinch thresholds, edge scrolling, gesture reset, report clamping, key release, and disconnected sends.
5. Add structured, privacy-safe diagnostics (state transitions and error categories only; no keystrokes, pointer coordinates, host identifiers, or user content).

### P1 — Android platform and build

- The upgrade branch compiles and targets API 36 using AGP 8.13.2, Kotlin 2.3.0, Gradle 8.13, and JDK 17. The official Wrapper pins the Gradle distribution and its published checksum; the next CI run must verify the Wrapper-based build.
- Preserve minSdk 28 unless a demonstrated technical reason requires changing it.
- The official Gradle 8.13 Wrapper is now committed, its distribution SHA-256 is pinned, and CI uses `./gradlew`; Gradle's setup action validates the Wrapper JAR.
- Add dependency verification/version catalogs or documented pinned versions as the project grows.
- Add static checks for dependency/license inventory and secret leakage.
- Consider release minification only after adding release smoke checks and validating HID reflection/keep rules (if any).

### P2 — Product experience

- Replace the current basic screen with a coherent Material 3 experience: connection card, clear primary action, large touch surface, compact mouse buttons, visible gesture hints, accessible contrast, and responsive layouts.
- Add a first-run setup flow explaining HID support, permissions, pairing, and the distinction between pairing and HID connection.
- Current pointer sensitivity and natural-scroll preferences use SharedPreferences. Migrate to AndroidX DataStore only when a broader settings model is introduced.
- Add per-host profiles only after host identification and reconnect behavior are proven reliable.
- Add keyboard, media remote, presentation mode, and numpad as separately testable modes after the HID descriptor is deliberately expanded and hosts are re-paired.
- Add localization (English first, then Urdu) and accessibility semantics.
- Avoid adding cloud accounts, telemetry, ads, network permissions, or unnecessary permissions to an offline input tool.

## Research references and reuse policy

The following public projects informed the design review:

- [Kurza](https://github.com/patrikflorek/Kurza) — MIT; useful separation of Bluetooth controller, HID service, touchpad UI, and gesture handler.
- [LinkPad](https://github.com/Devdas-gupta/linkpad) — MIT; useful reference for dedicated HID service/report sender, host profiles, settings, and modular app structure.
- [PhonePad](https://github.com/Tygb99/phonepad) — GPL-3.0-or-later; useful product scope and CI/maintenance practices. Do not copy or adapt code unless the project deliberately accepts GPL obligations.
- [Bluetooth HID Remote](https://github.com/jqssun/android-bt-remote) — GPL-3.0; useful cross-platform HID behavior reference. Do not copy or adapt code unless GPL compliance is deliberately chosen.
- [Bluke](https://github.com/arnv-kr/Bluke) — AGPL-3.0-or-later according to its project listing; feature/UX inspiration only unless license obligations are reviewed.
- [Android BluetoothHidDevice API](https://developer.android.com/reference/android/bluetooth/BluetoothHidDevice.html) — authoritative lifecycle and report API contract.
- [Android foreground-service types](https://developer.android.com/about/versions/14/changes/fgs-types-required) — relevant if a connected-device foreground service is evaluated.

No third-party source code is copied into this repository by this audit. Before adopting any implementation, record its exact source, license, version/commit, modifications, and required notices in a third-party notices file.

## Delivery sequence and acceptance gates

### Phase 1 — Safety and correctness
- Model Bluetooth/HID states explicitly.
- Check `sendReport()` results, gate sends on confirmed connection, and prevent host connection requests until HID registration is confirmed.
- Make profile initialization and teardown idempotent and race-safe.
- Guarantee release of pressed mouse buttons/keys on cancellation, disconnect, pause, and teardown where the host is still reachable.
- Add comprehensive pure unit tests.

**Gate:** tests and lint pass; no false “connected/ready” state; no unhandled lifecycle race found in review.

### Phase 2 — Build and supply-chain hardening
- Upgrade to API 36-compatible toolchain.
- Add Gradle Wrapper, dependency pinning/verification, CI permissions and action pinning strategy.
- Add release build checks, APK metadata checks, artifact hashes, and SBOM/license reporting.
- Release signing is wired to four protected GitHub Actions secrets, which are now provisioned. CI validates the JKS, builds the signed APK, verifies it with `apksigner`, and checks the signing certificate fingerprint.

**Gate:** clean reproducible CI build, debug install artifact, unsigned release artifact, and documented provenance.

### Phase 3 — Architecture and product UI
- Extract Bluetooth, HID reports, gestures, preferences, and UI into separate packages.
- Introduce DataStore-backed preferences and polished accessible UI.
- Add onboarding, diagnostics, and host profiles.

**Gate:** UI and gesture tests, preference migration tests, no behavior regression against the current gesture reference.

### Phase 4 — Hardware validation and release
- Test on the target Android phone and a documented set of hosts.
- Record Android version/device model, host OS, pairing, reconnect, sleep/wake, screen rotation, Bluetooth toggles, long-run stability, and battery behavior.
- Fix all critical/high issues and publish a signed release only after the owner provisions the signing key.

**Gate:** completed hardware matrix, signed APK/AAB, release notes, checksum, privacy statement, license notices, and rollback plan.

## Hardware validation matrix

| Test | Status |
| --- | --- |
| Unit tests / lint / debug and unsigned release CI | Passed in run 45634634281 before latest Wrapper/CI changes; final rerun pending |
| Android phone exposes HID Device profile | Not tested |
| Pair and connect to Windows 10/11 | Not tested |
| macOS / Linux host interoperability | Not tested |
| Pointer, buttons, vertical/horizontal scroll | Not tested on hardware |
| Pinch-to-zoom and keyboard shortcuts | Not tested on hardware |
| Disconnect/reconnect and Bluetooth toggle | Not tested on hardware |
| Screen rotation, background/foreground, process death | Not tested on hardware |
| 30–60 minute continuous session / battery / thermal | Not tested |
| Signed production release | Passed in run 45634634281 before latest Wrapper/CI changes; final rerun pending |

## Non-goals unless separately approved

- No remote control over the internet or Wi-Fi.
- No desktop companion agent as a prerequisite for core Bluetooth HID.
- No silent host commands, keylogging, screen capture, or data collection.
- No automatic permission bypass or Bluetooth setting changes.
- No copying GPL/AGPL code into a differently licensed product without deliberate license review.
- No claiming “perfect”, “certified”, or “production proven” solely from CI success.
