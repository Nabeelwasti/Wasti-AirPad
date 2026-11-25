# Wasti AirPad — Product hardening and release acceptance

**Current version:** 1.3.1 (release candidate)  
**Repository:** https://github.com/Nabeelwasti/Wasti-AirPad  
**Current release baseline:** main at 329bca84; combined 1.3.1 candidate in PR #12  
**Audit date:** 25 November 2026

## Executive assessment

AirPad 1.3.1 combines two independent input transports:

1. **Bluetooth HID:** the Android phone presents itself as a standard mouse/keyboard peripheral. No desktop companion is required.
2. **Local Wi-Fi:** an optional Python desktop companion receives a narrowly allow-listed input protocol over TLS. Android pins the host certificate and requires an eight-digit session pairing code.

The 1.3.1 release-candidate branch has passed Android unit tests, debug/release lint and builds, signed APK validation, Python protocol tests, CLI smoke tests and pip-audit. The main branch remains unchanged while hardware acceptance is pending. These are software/CI results only. Bluetooth HID compatibility, Wi-Fi discovery, synthetic desktop input and sustained use still require real hardware acceptance. Do not describe the app as fully production-certified until those tests are recorded.

## Current verified capabilities

### Android / Bluetooth
- Android 9+ minimum; compile/target API 36.
- Bluetooth HID mouse and keyboard peripheral.
- Pointer movement, sensitivity, click/drag, scrolling, pinch-to-zoom, multi-finger shortcuts, dedicated click controls and natural-scroll preference.
- HID report encoding and gesture policy extracted into pure Kotlin modules with unit tests.
- Host SET_PROTOCOL negotiation for Boot/Report Protocol, GET_REPORT replies, keyboard LED output reports and explicit HID send diagnostics.
- Test HID control for pointer/Space reports with Android accepted/rejected counts.
- HID registration is gated before host connection; sendReport acceptance is checked.
- Lifecycle/cancel/disconnect cleanup sends neutral reports where the transport is available.
- Preferences are local SharedPreferences; no cloud account or telemetry.

### Android / Wi-Fi
- TLS 1.2+ client with SHA-256 host-certificate pinning.
- Private/link-local/loopback destination validation and eight-digit session pairing code.
- DNS-SD/mDNS discovery and QR deep-link pairing with an explicit confirmation dialog.
- Manual IP/port/fingerprint entry remains available.
- Bounded JSON-lines protocol routes mouse, keyboard and release messages only.
- Bounded outbound event queue; queue saturation fails closed rather than throwing into Android touch dispatch.
- A dedicated TLS reader detects desktop EOF/silent shutdown and updates connection state.
- Authenticated 10-second heartbeat, 30-second timeout and bounded 1/2/4/8/16-second reconnect attempts (five maximum); retry budget resets only after 60 seconds of stable connection.
- Bluetooth remains available independently and is the fallback when Wi-Fi is disconnected.

### Desktop companion
- Python 3.11+; cryptography, pynput, zeroconf, and qrcode.
- Persistent ECDSA P-256 self-signed host certificate under ~/.wasti-airpad/.
- DNS-SD advertisement, QR pairing payload and manual connection fallback.
- Default TCP port 47777, with automatic OS-selected port fallback if occupied.
- Pairing-code lockout after five failed attempts for 60 seconds.
- Mouse movement/buttons/wheel, HID-key mapping and modifier shortcuts.
- Held mouse buttons are released when a client disconnects.
- Windows launcher; macOS Accessibility permissions and Linux input-backend constraints apply. Linux Wayland is not certified.

## Supply-chain and CI controls

- Official Gradle Wrapper with distribution SHA-256 pinning and CI wrapper validation.
- Android CI: unit tests, debug/release lint, debug/release APK build, alignment and checksums.
- Protected signing job validates JKS format and PrivateKeyEntry, builds signed APK, verifies APK v2 signature, compares signer certificate to the configured keystore alias and cleans temporary signing material.
- Separate Python CI compiles/imports modules, smoke-tests host.py --help, runs protocol tests and audits dependencies with pip-audit.
- Dependabot is configured for dependency updates; major Gradle/AGP updates require coordinated validation.
- No signing key, Base64 keystore, or password belongs in Git. Keep at least two encrypted backups of the release keystore.

## Known limitations — not yet complete

1. **No background service or unlimited recovery.** Wi-Fi heartbeat and five-attempt bounded reconnect are implemented, but reconnect stops after five failures and requires user action.
2. **No full text-entry keyboard or on-screen keyboard layout.** Current Wi-Fi keyboard support forwards mapped HID key/shortcut events.
4. **No media/volume key mode or presentation remote.**
5. **No clipboard, file transfer, notification sync, screen capture, remote shell, or cloud relay.** Clipboard/file transfer and notifications require separate consent/security designs; shell and unrestricted remote command execution are intentionally excluded.
6. **No desktop tray, background startup option, signed Windows/macOS/Linux installers, or release attestations for desktop binaries.**
7. **No Android 17 API 37 local-network picker migration yet.** Current target is API 36; do not target API 37 until the system-mediated NSD picker is implemented and tested.
8. **No real-device hardware certification.** CI cannot prove the Android vendor exposes Bluetooth HID Device or that each desktop input backend works.
9. **No top-level project licence.** The repository is publicly visible but should not be marketed as open source until the owner chooses and adds a licence. Third-party licence notices do not license the project itself.

## Priority roadmap

### P0 — Release acceptance and transport reliability
- Run the Android + Windows 10/11 hardware matrix below.
- Verify wrong code and wrong fingerprint rejection.
- Verify Wi-Fi drop, companion shutdown, phone app pause/resume and held-button release.
- Verify Bluetooth still works after Wi-Fi disconnect and vice versa.
- Add integration tests for desktop connection teardown and event queue saturation where practical.

### P1 — Pairing and reconnect
- [x] Pair once with an eight-digit code; issue a random 256-bit phone token.
- [x] Encrypt the phone token with a non-exportable Android Keystore AES-GCM key.
- [x] Store only SHA-256 token hashes on the desktop; provide a local forget action and a host-side revoke-all command.
- [x] Keep host certificate pinning and block on fingerprint mismatch.
- [x] Add a 10-second heartbeat, 30-second timeout and five-attempt exponential reconnect; reset the retry budget only after a 60-second stable session.
- [ ] Add a visible reconnect cancel/disconnect control and more explicit connection-state UI.

### P1 — Input quality
- Add a dedicated text-entry panel with clear Send/Cancel semantics; never capture or transmit arbitrary clipboard content automatically.
- Expand HID key mapping with tests for punctuation, navigation, function keys and platform-specific shortcuts.
- Add media/volume and presentation modes with host-OS capability notes.
- Keep every input event bounded and explicit; release held state on timeout, disconnect and app lifecycle changes.

### P2 — Desktop product quality
- Replace console-only startup with an optional tray UI and explicit start/stop controls.
- Add signed Windows installer and macOS package; add Linux AppImage/Flatpak only after backend support is verified.
- Keep firewall scope private-network only; never enable router port forwarding.
- Publish checksums, provenance and an SBOM for each desktop release.
- Test Windows, macOS and Linux/X11 separately; label Wayland support only after validation.

### P2 — Android platform and UX
- Before target SDK 37, implement Android's NSD system service picker using DiscoveryRequest.FLAG_SHOW_PICKER and service-info callbacks, or deliberately request ACCESS_LOCAL_NETWORK with a clear privacy rationale if a system picker cannot meet requirements.
- Improve responsive layout, onboarding, accessibility semantics, landscape/tablet layout and localization (English, then Urdu).
- Introduce an explicit connection state model rather than relying on loosely coupled booleans.
- Consider DataStore only when the preferences model grows beyond the current small settings set.

### P3 — Optional ecosystem features
- Wi-Fi Direct for no-router/offline use after LAN mode is stable.
- USB/ADB connection for developer/test workflows.
- Multi-host profiles and multi-computer switching.
- Clipboard, file transfer, notifications and remote media integrations only as separately permissioned, opt-in modules.

## Physical acceptance matrix

Record exact device model, Android version, computer model, host OS/version, companion version, network type and test result. Do not mark a row passed from code review alone.

| Test | Required result | Status |
|---|---|---|
| Install signed APK on Redmi Note 14 Pro / Android 16 | Installs and opens; no crash | Pending owner test |
| Bluetooth HID registration | Device registers and is discoverable | Pending owner test |
| Bluetooth Test HID | Android accepts mouse and Space reports; host receives both | Pending owner test |
| Pair and connect to Windows 10/11 | Stable HID connection | Pending owner test |
| Pointer movement / sensitivity | Smooth, no stuck movement | Pending owner test |
| Left/right click, drag and wheel | Correct buttons and scrolling | Pending owner test |
| Keyboard shortcuts / pinch zoom | Expected host action | Pending owner test |
| Wi-Fi DNS-SD discovery | Finds only AirPad companion on same LAN | Pending owner test |
| QR pairing | Correct host selected; explicit confirmation | Pending owner test |
| Wrong pairing code | Rejected and lockout applied | Pending owner test |
| Wrong certificate fingerprint | TLS connection blocked | Pending owner test |
| Manual IP fallback | Connects to private LAN host only | Pending owner test |
| Desktop shutdown / Wi-Fi loss | Android reports disconnect; held mouse buttons released | Pending owner test |
| App pause/resume / screen lock | No stuck buttons or keys | Pending owner test |
| Bluetooth fallback after Wi-Fi | Bluetooth path remains functional | Pending owner test |
| Sustained 30-minute session | No crashes, stuck input or unacceptable heat | Pending owner test |
| macOS and Linux/X11 | Separate host smoke tests | Pending owner test |
| Linux Wayland | Not certified until explicitly tested | Not certified |

## Release checklist

- [x] Android and Python CI workflows pass on the 1.3.1 release-candidate branch; main remains unchanged pending hardware acceptance.
- [x] Signed APK built and signature/certificate verified by CI.
- [x] Python dependencies audited by pip-audit.
- [x] Security policy and third-party notices committed.
- [x] Open-source competitor and architecture research documented.
- [ ] Physical Android 16 + Windows 10/11 acceptance matrix completed.
- [ ] macOS/Linux host support verified or explicitly excluded from release claims.
- [x] Persistent phone trust, host-side token revocation, heartbeat and bounded automatic reconnect implemented.
- [x] Bluetooth Boot/Report Protocol handling, GET_REPORT and Test HID diagnostics implemented.
- [ ] Project licence chosen by the owner and added before any open-source claim.
- [ ] Final release tag, signed artifacts, checksums and rollback instructions published.

## Security and scope boundaries

AirPad is a local input tool, not a remote desktop or general remote administration system. It must not expose shell commands, arbitrary process execution, hidden keylogging, screen capture, unrestricted clipboard access, or public-internet control. Do not port-forward the desktop companion. Keep the receiver on trusted private networks and stop it when unused.

For Android 17 / API 37, Android's official local-network guidance recommends a system-mediated NSD picker for selecting one local service without requesting broad local-network permission. AirPad's one-computer pairing flow is a good fit for that path. See the official Local network permission guide and DiscoveryRequest API.
