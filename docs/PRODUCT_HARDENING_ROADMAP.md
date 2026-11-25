# Wasti AirPad — Product Hardening & Engineering Roadmap

**Baseline:** `main` at `db78a4ad1e124fca93b45459657f50b91ccf45d7`  
**Baseline version:** 1.1.3  
**Working branch:** `upgrade/reliability-product-quality`  
**Audit date:** 2026-11-25

## Executive assessment

The current app is a compact, offline Android Bluetooth HID touchpad with a passing CI pipeline. It is a useful functional baseline, but it is not yet a production-grade product. Its largest risks are lifecycle correctness, HID report delivery observability, limited automated gesture coverage, a monolithic Activity, an old Android build target, and the absence of a repeatable physical-device compatibility matrix.

This roadmap deliberately separates verified facts from work that requires physical hardware. A green CI build proves that the source compiles and automated tests pass; it does not prove that a phone exposes the HID Device profile or that every desktop accepts the reports.

## Verified baseline

- Package: `com.wasti.airpad`; minimum Android API 28; target/compile API 34.
- Android Gradle Plugin 8.5.2, Kotlin 1.9.24, Gradle 8.7 in CI, JDK 17.
- Main implementation is concentrated in `MainActivity.kt` (Activity, Bluetooth profile lifecycle, UI, HID report encoding, drawing, and gesture recognition).
- Bluetooth HID mouse and keyboard reports use report IDs 1 and 2.
- CI currently runs local unit tests, Android lint, debug APK build, unsigned release APK build, output checks, and artifact upload.
- Latest baseline CI run: [run 45611559284](https://github.com/Nabeelwasti/Wasti-AirPad/actions/runs/45611559284), successful.
- There is no INTERNET permission and no analytics/network SDK in the inspected manifest/dependency list.

## Findings and risks

### P0 — Correctness and release blockers

1. **HID report send result is discarded.** `BluetoothHidDevice.sendReport()` returns a Boolean, but the current wrapper ignores it. The UI can appear connected while a report submission is rejected. Track connection and registration state separately, check every submission result, and surface actionable failures without spamming the UI.
2. **Registration is asynchronous.** A successful `registerApp()` return only means the request was accepted; `onAppStatusChanged()` is authoritative. Model explicit states (unsupported, permission required, Bluetooth off, profile opening, registering, ready, connected, disconnected, error) and prevent connection/report actions until their prerequisites are true.
3. **Lifecycle callbacks can race Activity teardown.** Guard late profile callbacks, avoid updating destroyed views, close the proxy exactly once, and make initialization/cleanup idempotent.
4. **Physical compatibility is unverified.** Test the actual phone firmware and at least Windows 11 plus one additional host OS before making compatibility claims.
5. **Release artifact is unsigned.** Production distribution needs a user-controlled signing key stored only in protected CI secrets. Never add a keystore, passwords, or signing material to Git.

### P1 — Testability and maintainability

1. Extract pure HID report encoding/descriptor validation from Android UI code.
2. Extract gesture recognition into a platform-independent state machine that can be tested with deterministic pointer event sequences.
3. Introduce a Bluetooth HID controller/repository boundary. Keep Android framework calls out of gesture logic.
4. Add tests for tap timing, double-tap drag, cancellation, pointer-count transitions, scroll direction, pinch thresholds, edge scrolling, gesture reset, report clamping, key release, and disconnected sends.
5. Add structured, privacy-safe diagnostics (state transitions and error categories only; no keystrokes, pointer coordinates, host identifiers, or user content).

### P1 — Android platform and build

- Google Play's current requirement (as of this audit) is API 36 for new apps and updates from 2026-08-31. The app currently targets API 34.
- API 36 is supported by AGP 8.9.1 or newer; AGP 8.13 supports API 36.1 and requires Gradle 8.13. Upgrade the toolchain as a coordinated, CI-verified change rather than changing only `targetSdk`.
- Preserve minSdk 28 unless a demonstrated technical reason requires changing it.
- Add the Gradle Wrapper and pin its distribution/checksum so local and CI builds use the same Gradle version.
- Add dependency verification/version catalogs or documented pinned versions as the project grows.
- Add static checks for dependency/license inventory and secret leakage.
- Consider release minification only after adding release smoke checks and validating HID reflection/keep rules (if any).

### P2 — Product experience

- Replace the current basic screen with a coherent Material 3 experience: connection card, clear primary action, large touch surface, compact mouse buttons, visible gesture hints, accessible contrast, and responsive layouts.
- Add a first-run setup flow explaining HID support, permissions, pairing, and the distinction between pairing and HID connection.
- Persist preferences (pointer speed, natural scrolling, haptics, gesture toggles, theme) using AndroidX DataStore.
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
- Check `sendReport()` results and gate sends on confirmed connection.
- Make profile initialization and teardown idempotent and race-safe.
- Guarantee release of pressed mouse buttons/keys on cancellation, disconnect, pause, and teardown where the host is still reachable.
- Add comprehensive pure unit tests.

**Gate:** tests and lint pass; no false “connected/ready” state; no unhandled lifecycle race found in review.

### Phase 2 — Build and supply-chain hardening
- Upgrade to API 36-compatible toolchain.
- Add Gradle Wrapper, dependency pinning/verification, CI permissions and action pinning strategy.
- Add release build checks, APK metadata checks, artifact hashes, and SBOM/license reporting.
- Keep release unsigned until the owner supplies secure signing credentials.

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
| Unit tests / lint / debug and unsigned release CI | Passed on baseline commit |
| Android phone exposes HID Device profile | Not tested |
| Pair and connect to Windows 10/11 | Not tested |
| macOS / Linux host interoperability | Not tested |
| Pointer, buttons, vertical/horizontal scroll | Not tested on hardware |
| Pinch-to-zoom and keyboard shortcuts | Not tested on hardware |
| Disconnect/reconnect and Bluetooth toggle | Not tested on hardware |
| Screen rotation, background/foreground, process death | Not tested on hardware |
| 30–60 minute continuous session / battery / thermal | Not tested |
| Signed production release | Blocked on secure owner-provided signing credentials |

## Non-goals unless separately approved

- No remote control over the internet or Wi-Fi.
- No desktop companion agent as a prerequisite for core Bluetooth HID.
- No silent host commands, keylogging, screen capture, or data collection.
- No automatic permission bypass or Bluetooth setting changes.
- No copying GPL/AGPL code into a differently licensed product without deliberate license review.
- No claiming “perfect”, “certified”, or “production proven” solely from CI success.
