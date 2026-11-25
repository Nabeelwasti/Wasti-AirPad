# Wasti AirPad — Open-source research, product gap audit and architecture roadmap

Research snapshot: 25 November 2026. This is a curated review of relevant public projects and official platform documentation, not an exhaustive audit of every public repository on GitHub. Repository features and licensing can change; re-check the linked upstream licence and release notes before reusing code or dependencies.

## Executive direction

Keep AirPad's strongest differentiator: **Bluetooth HID works without installing a computer receiver**. Add Wi-Fi as a second transport for computers where Bluetooth HID peripheral mode is unavailable or inconvenient. Do not turn AirPad into a broad remote-desktop suite before the core mouse/keyboard experience is dependable.

Wi-Fi requires two components:
- Android controller: captures gestures and sends authenticated input events.
- Desktop companion: receives events and injects native mouse/keyboard input.

The initial Wi-Fi implementation is on the feature branch and uses TLS with certificate pinning, an eight-digit pairing code, bounded allow-listed input messages, a Python desktop receiver, DNS-SD/mDNS host discovery, QR invitation pairing and manual-IP fallback. It is not yet hardware-certified or ready to merge.

## Current AirPad baseline

Already present on main after PR #1:
- Bluetooth Classic HID mouse and keyboard peripheral mode.
- Pointer surface, sensitivity, click, drag, scroll, pinch-to-zoom and multi-finger shortcut gestures.
- Dedicated left/right click controls and natural-scroll preference.
- Android 16 / API 36 build configuration, Gradle wrapper, deterministic Kotlin unit tests, lint/build CI and signed APK verification.
- Lifecycle cleanup intended to reduce stuck mouse buttons/keys.

Current feature branch adds:
- Optional Wi-Fi mode and an Android TLS client.
- Desktop companion under wifi-companion/.
- Certificate SHA-256 pinning and code-based authentication.
- A bounded JSON-lines protocol allowing only mouse, key and release messages.
- Python protocol unit tests and a separate CI workflow.
- DNS-SD/mDNS discovery, with manual IP/port fallback. No cloud service or relay.

## Competitive and open-source scan

### 1. Beckon — strongest direct architecture reference
Repository: https://github.com/omsingh02/beckon  
Licence shown by upstream: MIT.

Relevant ideas:
- Native Android app plus desktop tray/console receiver.
- Bluetooth HID and Wi-Fi transports.
- Noise IK end-to-end encryption, X25519, ChaCha20-Poly1305 and BLAKE2s.
- QR pairing with one-time invitation token, persistent host identity and trust-on-first-use fingerprint.
- Multicast discovery, reconnect/liveness checks, Windows/Linux/macOS native input injection.
- Loopback protocol and trust-state tests.

Decision: use as an architecture and security benchmark. Its MIT licence is permissive, but do not copy code without preserving required copyright/licence notices. AirPad's current TLS pinning approach is a simpler first implementation; a Noise-based protocol is a future hardening option if independently reviewed.

### 2. KDE Connect — mature feature breadth reference
Android mirror: https://github.com/KDE/kdeconnect-android  
Desktop: https://github.com/KDE/kdeconnect-kde  
Licence shown by Android mirror: GPL-2.0 / GPL-3.0.

Relevant ideas:
- Virtual touchpad and keyboard over Wi-Fi with TLS.
- Shared clipboard, file/URL sharing, notification sync, media control, presentation remote, run commands and device integration.
- Mature device pairing and a cross-platform desktop receiver.

Decision: borrow product ideas, not code. GPL obligations can affect redistribution and derivative works. Do not add remote shell commands to AirPad; they create a much larger security surface than an input remote needs.

### 3. Axon — direct feature comparison
Repository: https://github.com/Kaia-Alenia/Axon  
Licence shown by upstream: GPL-3.0.

Relevant ideas:
- Android client plus Go desktop server.
- Wi-Fi, Bluetooth and USB/ADB connection modes.
- QR/manual connection, web client, native desktop input injection, dynamic ports and platform-specific server builds.
- Gesture, keyboard and scroll feature set.

Decision: use its feature list and release packaging as comparison points only. Do not copy GPL-3.0 code into this project without a deliberate licence decision.

### 4. Deskflow — networking and modularity reference
Repository: https://github.com/deskflow/deskflow

Relevant ideas:
- Clear server/client split for input sharing.
- Cross-platform abstraction around platform-specific input.
- Clipboard and multi-computer support.

Decision: study the protocol and platform abstraction boundaries if AirPad later supports controlling multiple computers. Deskflow is a desktop-to-desktop KVM, not a phone-first product.

### 5. Bluetooth HID-focused references
- https://github.com/jqssun/android-bt-remote
- https://github.com/Tygb99/phonepad

These projects reinforce the value of serverless Bluetooth HID: it works with generic hosts without a companion application. Keep this path intact and do not make Wi-Fi a prerequisite.

## Platform documentation and free building blocks

| Resource | What it enables | AirPad decision |
|---|---|---|
| Android Network Service Discovery (NSD) | mDNS/DNS-SD discovery of a desktop service on the LAN | Preferred automatic discovery path; no hard-coded IP or port |
| Android Wi-Fi Direct | Direct peer connection without a router; WPA2 protected P2P group | Future offline/no-router mode, after normal LAN Wi-Fi is stable |
| Google Nearby Connections | Offline nearby discovery and encrypted data transfer over available radios | Evaluate only if a native cross-platform desktop receiver is practical; adds Google Play services dependency |
| Python pynput | Cross-platform desktop mouse/keyboard injection | Used by the initial companion; Wayland support varies |
| Python cryptography | Host key and self-signed certificate generation | Used by the initial companion |
| PyInstaller | Package Python companion into desktop executables | Candidate for signed Windows/macOS/Linux release artifacts |
| Python qrcode | BSD-3-Clause QR generation | Prints a session-scoped deep-link invitation; Android camera/QR scanner opens AirPad. No camera permission or scanning SDK is required in AirPad |
| Noise Protocol Framework | Well-specified authenticated key exchange and encrypted transport patterns | Security benchmark; do not roll a custom cryptographic protocol |

Official Android references:
- NSD: https://developer.android.com/develop/connectivity/wifi/use-nsd
- Wireless connectivity overview: https://developer.android.com/develop/connectivity/wifi
- Wi-Fi Direct: https://developer.android.com/develop/connectivity/wifi/wifip2p
- Nearby Connections: https://developers.google.com/nearby/connections/overview
- Local network permission: https://developer.android.com/privacy-and-security/local-network-permission

### Android 17 local-network permission planning

The current app targets API 36. Android's current documentation says local-network protections become mandatory for apps targeting API 37 / Android 17. Direct TCP/UDP and NSD access will require the new ACCESS_LOCAL_NETWORK runtime permission, or a privacy-preserving system NSD picker flow where applicable. Do not add the future permission prematurely to an API 36 build; implement and test the correct migration when targetSdk moves to 37.

## Wi-Fi transport design

### Current protocol

- Transport: TLS TCP on default port 47777.
- TLS: TLS 1.2 minimum; Android pins the SHA-256 fingerprint of the desktop's self-signed certificate.
- Pairing: eight-digit code displayed by the desktop process; code is not persisted by Android.
- Host identity: ECDSA P-256 key and certificate persisted in the user's home configuration folder.
- Framing: newline-delimited UTF-8 JSON, maximum 4096 bytes per message.
- Allow-list: mouse, key and release events only. Unknown commands, malformed JSON, invalid numeric types and out-of-range values are rejected.
- No cloud, remote relay, shell execution, file transfer, clipboard sync, notification access or screen capture.

### Security requirements before release

1. Add connection-wide authentication rate limiting and clear lockout feedback.
2. Keep host key private; protect it with platform-native file permissions/ACLs.
3. Bind or firewall the listener to trusted private networks only; never instruct users to port-forward it.
4. Add explicit disconnect and reconnect state, exponential backoff, heartbeat and bounded outbound event queue.
5. [x] Add automatic DNS-SD discovery without accepting arbitrary unauthenticated devices.
6. [x] Add a QR invitation containing host address, certificate fingerprint and a session-scoped pairing code; keep certificate pin verification and an explicit Android confirmation mandatory. The code is valid only while that companion process is running, and is rate-limited.
7. Persist trusted host identity separately from the short-lived pairing code; support revoke/forget host.
8. Validate all keyboard/mouse events, maximum rates, frame sizes and protocol versions on both sides.
9. Release all held input on every disconnect, app pause, server shutdown and protocol error.
10. Add dependency scanning, SBOM, signed desktop artifacts and a documented vulnerability-reporting process.

## Product gap audit and roadmap

### P0 — required before Wi-Fi 1.0
- [x] Android TLS client with certificate pinning and pairing-code authentication.
- [x] Desktop companion baseline for mouse, buttons, wheel and keyboard.
- [x] Input allow-list, bounded message size and protocol unit tests.
- [x] Separate Python CI workflow.
- [ ] Verify Python CI and Android CI on the feature PR.
- [ ] Test on the user's Android 16 phone and at least one Windows host.
- [ ] Validate mouse acceleration/sensitivity, keyboard layout and every gesture over Wi-Fi.
- [ ] Add rate limiting, bounded event queue, heartbeat and reconnect.
- [ ] Build and publish a signed desktop companion for Windows; test Linux/macOS separately.
- [ ] Complete threat model and dependency/license inventory before public release.

### P1 — high-value usability
- [x] Automatic desktop discovery with NSD/mDNS and a manual-IP fallback.
- [x] QR invitation pairing with a session-scoped code and Android confirmation.
- [ ] Saved trusted computer list with rename, forget and revoke.
- [ ] Reconnect with bounded exponential backoff and visible connection quality/latency.
- [ ] Better keyboard mapping, layout-aware text input, media keys and horizontal scrolling.
- [ ] Desktop tray app, start-at-login opt-in, pause/lock control and uninstall path.
- [ ] One-click firewall guidance and clear private-network-only setup.
- [ ] Landscape/tablet layout, one-hand mode, adjustable gesture thresholds and accessibility review.

### P2 — useful differentiators
- [ ] Media playback controls and volume/mute.
- [ ] Presentation remote (next/previous/blank screen).
- [ ] Configurable shortcuts and user-defined gesture profiles.
- [ ] Multiple saved computers and fast host switching.
- [ ] Optional on-screen keyboard/text entry.
- [ ] Per-host sensitivity and scroll settings.
- [ ] Optional haptic strength and low-power mode.

### P3 — only after the core remote is stable
- [ ] Clipboard sharing, off by default and with sensitive-content exclusions.
- [ ] File/URL transfer with explicit per-transfer consent.
- [ ] Multi-computer control and a desktop-to-desktop mode.
- [ ] Wi-Fi Direct for no-router environments.
- [ ] Browser-based fallback controller served locally by the companion.
- [ ] Accessibility/assistive use cases with explicit permission design.

### Out of scope unless a clear need emerges
- Remote screen viewing/control, notification/SMS mirroring, remote shell/command execution, camera/microphone streaming, internet relay, accounts and cloud synchronization. These add privacy, permissions, attack surface and maintenance costs unrelated to a dependable mouse/keyboard remote.

## Test and release gates

- Android: unit tests, lint, debug/release builds, APK alignment, signature verification and checksum.
- Desktop: unit tests for protocol validation and keyboard mapping; integration tests using a loopback TLS test server; platform-specific smoke tests.
- Security: wrong fingerprint must fail; wrong pairing code must fail and rate-limit; malformed/oversized messages must be rejected; no unapproved message type may execute; disconnect must release held buttons.
- Network: same router, guest network isolation, changing DHCP address, firewall denial, Wi-Fi loss/recovery, VPN interface and port conflict.
- Hardware: Redmi Note 14 Pro / Android 16 plus Windows 10/11 host; sustained 30-minute session; test Bluetooth and Wi-Fi independently.
- Release: publish Android APK and desktop companion together with checksums, version compatibility and clear install/uninstall instructions.

## Research conclusion

The best route is a dual-transport input tool, not a clone of a full phone-integration suite. Keep Bluetooth HID serverless; make Wi-Fi secure, easy to discover, and cross-platform through a small desktop companion. Prioritize trust, input correctness, reconnect behavior and release packaging before clipboard, notifications or other feature expansion.


## Build dependency update audit

- Kotlin Android Gradle plugin 2.4.21 was tested independently against the current Android build and both CI jobs passed in run 45639469349. The version is included in the Wi-Fi branch so the full feature build will validate it again.
- Dependabot's Gradle 9.9.2 wrapper PR failed because Android Gradle Plugin 8.13.2 relies on a Gradle internal API removed in Gradle 9.6. Do not accept this wrapper major update alone; upgrade AGP and Gradle together only after a coordinated compatibility migration.
- Dependabot's AGP 9.5.2 PR failed during dependency resolution because Maven Central returned HTTP 429 (rate limiting), so that run did not prove AGP compatibility. Keep the known-good toolchain until a clean, uncached coordinated test can validate it.
- Dependabot is configured to ignore major Gradle-wrapper and AGP updates until a deliberate migration is ready. Minor/patch updates remain eligible for CI validation.


## Project and dependency licensing audit

- The AirPad repository currently has no top-level LICENSE file. Public visibility does not grant others reuse rights; do not describe the app itself as open source until a project licence is selected and added.
- The companion's current pip-installed dependencies are pynput (LGPL-3.0), python-zeroconf (LGPL-2.1-or-later), and cryptography (Apache-2.0 OR BSD-3-Clause), qrcode (BSD-3-Clause). See THIRD_PARTY_NOTICES.md and upstream licence files. If these are bundled into a desktop executable later, review LGPL notice and relinking obligations before distribution.
- No source code was copied from Beckon, Axon, KDE Connect, or Deskflow. They were used as feature/architecture references only.
