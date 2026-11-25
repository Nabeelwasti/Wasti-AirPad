# Wasti AirPad 1.3.0 — Release candidate notes

## New: local Wi-Fi remote control

- Connect an Android phone and desktop computer on the same trusted local network.
- Discover a running desktop companion automatically with DNS-SD/mDNS, scan a QR invitation, or enter its local IP manually.
- Authenticate with an eight-digit pairing code. QR pairing opens a confirmation prompt in AirPad; the code is session-scoped and is not saved on the phone.
- Verify the desktop's TLS certificate using its SHA-256 fingerprint before input is sent.
- Forward mouse movement, left/right/middle buttons, scrolling, keyboard keys and modifier shortcuts.
- Disconnecting the session causes the desktop receiver to release held mouse buttons.

## Bluetooth remains independent

Bluetooth HID remains available without installing the desktop companion. Wi-Fi is an optional second transport. Selecting Bluetooth closes an active Wi-Fi session.

## Desktop companion

The companion is in wifi-companion/. It currently uses Python 3.11+, pynput, cryptography, zeroconf and qrcode. Windows quick-start is provided; macOS and Linux require platform-specific permissions and input-backend support. Linux Wayland is not certified.

## Security and privacy

- Wi-Fi traffic uses TLS 1.2 or newer with certificate pinning and an eight-digit pairing code.
- Pairing code is not stored on the phone.
- The desktop companion accepts only bounded mouse, keyboard and release messages.
- No cloud relay, telemetry, account, shell execution, screen capture, clipboard sync or file access is included.
- Do not port-forward the companion port. Allow it only on trusted private networks.

## Release status

This is a release candidate, not hardware-certified. CI validates Android builds/signing and desktop protocol syntax/tests. Before public release, test on Android 16 with a Windows 10/11 host and verify discovery, wrong fingerprint/code rejection, all input gestures, disconnect/reconnect, Bluetooth fallback and a sustained 30-minute session. macOS/Linux require their own smoke tests.
