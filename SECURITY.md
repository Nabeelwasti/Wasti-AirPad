# Security policy

## Supported versions

Security fixes are made against the latest supported AirPad release and the active development branch. Older APKs and desktop companions may not receive fixes.

## Reporting a vulnerability

Please do not publish an exploitable vulnerability in a public issue. Use GitHub's private vulnerability reporting / Security Advisories for this repository where enabled. Include the affected version, platform, reproduction steps and impact. Do not include passwords, private signing keys, pairing QR screenshots or other credentials in a report.

## Wi-Fi threat model

- Wi-Fi mode is designed for a trusted private LAN, not the public internet.
- The Android client accepts only private, link-local or loopback destination addresses.
- The desktop companion uses TLS 1.2 or newer and a self-signed host certificate whose SHA-256 fingerprint is pinned by Android.
- Pairing requires an eight-digit session code. Five failed attempts trigger a 60-second host lockout.
- QR invitations contain the session code and host fingerprint. Treat the QR as a temporary credential; do not share screenshots.
- The desktop protocol allows only bounded mouse, keyboard and release events. It does not expose a shell, arbitrary command execution, screen capture, clipboard, file access or notification access.
- The Android outbound event queue is bounded. If it saturates, the connection is closed so the desktop can release held mouse buttons rather than replay stale input.
- The desktop companion stores its persistent TLS private key under ~/.wasti-airpad/. Keep that folder private. Removing it rotates the host identity and invalidates the pinned fingerprint.

## User safety

- Never port-forward TCP port 47777 from a router.
- Allow the desktop companion through the firewall only on Private/trusted networks.
- Stop the companion when it is not in use.
- If the certificate fingerprint changes unexpectedly, do not accept it until you have verified the host identity on your own computer.
- Keep Bluetooth and Wi-Fi sessions under direct user control. No background connection service is enabled by default.

## Platform and release limitations

The Wi-Fi companion has automated protocol tests and dependency auditing, but synthetic input still requires real desktop testing. macOS Accessibility permission and Linux input-backend restrictions apply; Linux Wayland is not certified. Do not treat CI success as hardware certification.

When the app moves to target SDK 37 / Android 17, the local-network permission/picker design must be updated according to the Android platform's local-network protections before release.
