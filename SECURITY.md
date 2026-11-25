# Security policy

## Supported versions

Security fixes are made against the latest supported AirPad release and the active development branch. Older APKs and desktop companions may not receive fixes.

## Reporting a vulnerability

Please do not publish an exploitable vulnerability in a public issue. Use GitHub's private vulnerability reporting / Security Advisories for this repository where enabled. Include the affected version, platform, reproduction steps and impact. Do not include passwords, private signing keys, pairing QR screenshots or other credentials in a report.

## Wi-Fi threat model

- Wi-Fi mode is designed for a trusted private LAN, not the public internet.
- The Android client accepts only private, link-local or loopback destination addresses.
- The desktop listener rejects peers outside RFC1918 private, IPv4 link-local/loopback, IPv6 ULA, IPv6 link-local and IPv6 loopback ranges before TLS input processing.
- The desktop companion uses TLS 1.2 or newer and a self-signed host certificate whose SHA-256 fingerprint is pinned by Android.
- First-time pairing requires an eight-digit session code. Five failed attempts trigger a 60-second host lockout.
- After first pairing, the phone receives a random 256-bit bearer token. Android encrypts it with a non-exportable Android Keystore AES-GCM key. The desktop stores only the token SHA-256 digest under ~/.wasti-airpad/.
- Users can forget a host in AirPad. To revoke every phone token on the desktop, stop the companion and run python host.py --forget-clients.
- QR invitations contain the session code and host fingerprint. Treat the QR as a temporary credential; do not share screenshots.
- The desktop protocol allows only bounded mouse, keyboard and release events. It does not expose a shell, arbitrary command execution, screen capture, clipboard, file access or notification access.
- The Android outbound event queue is bounded. If it saturates, stale queued input is discarded and the connection is closed so the desktop can release held mouse buttons rather than replay stale input.
- An authenticated heartbeat runs every 10 seconds. A 30-second read timeout detects an unresponsive host; Android makes at most five automatic reconnect attempts with bounded exponential backoff; the retry budget resets only after a 60-second stable session. The user can always start a fresh connection.
- The desktop companion stores its persistent TLS private key and trusted-client token hashes under ~/.wasti-airpad/. Keep that folder private. Removing the host identity rotates the certificate; deleting trusted-clients.json revokes phone tokens.

## User safety

- Never port-forward the companion's TCP port (47777 by default, or the dynamically selected port) from a router.
- Allow the desktop companion through the firewall only on Private/trusted networks.
- Stop the companion when it is not in use.
- If the certificate fingerprint changes unexpectedly, do not accept it until you have verified the host identity on your own computer.
- Keep Bluetooth and Wi-Fi sessions under direct user control. No background connection service is enabled by default.

## Platform and release limitations

The Wi-Fi companion has automated protocol tests and dependency auditing, but synthetic input still requires real desktop testing. macOS Accessibility permission and Linux input-backend restrictions apply; Linux Wayland is not certified. Do not treat CI success as hardware certification.

When the app moves to target SDK 37 / Android 17, the local-network permission/picker design must be updated according to the Android platform's local-network protections before release.
