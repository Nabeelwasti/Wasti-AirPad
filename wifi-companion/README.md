# Wasti AirPad Wi-Fi Companion

The desktop companion lets the Android AirPad app control a computer over the same local Wi-Fi/LAN network. It is a separate desktop receiver; Bluetooth HID mode does not need it.

## Current scope

- Windows, macOS, and Linux desktop input through pynput. Linux Wayland support depends on the desktop/input backend; X11 is the most compatible.
- TLS 1.2 or newer with a persistent, self-signed host certificate.
- Certificate SHA-256 pinning in the Android app.
- An eight-digit pairing code checked by the desktop before input is accepted.
- Mouse movement, left/right/middle buttons, wheel, keyboard keys and modifier shortcuts.
- Input release on disconnect.
- Automatic local desktop discovery using DNS-SD/mDNS, with manual IP entry as a fallback.
- No cloud account, analytics, telemetry, or internet relay.

## Start on Windows

1. Install Python 3.11 or newer from https://www.python.org/downloads/ and enable the Python launcher.
2. Extract or clone this repository.
3. Double-click run-windows.bat. The first run installs the three free Python dependencies.
4. If Windows Firewall asks, allow AirPad on Private networks only.
5. Keep the companion window open. It prints the computer's local IPv4 address, port (47777), eight-digit pairing code, and certificate SHA-256 fingerprint. It also advertises the host on the local network for AirPad discovery.

On macOS/Linux, open a terminal in this folder and run:

    python3 -m pip install -r requirements.txt
    python3 host.py

## Connect from Android

1. Connect the phone and computer to the same Wi-Fi router/network. Guest networks and router client/AP isolation can prevent devices from communicating.
2. In AirPad, tap Wi-Fi, then Find computers on this Wi-Fi.
3. Select the computer found. AirPad fills its local IP, port and certificate fingerprint. If discovery is blocked by the router, enter those values manually from the companion window.
4. Enter the eight-digit pairing code and tap Connect. The phone rejects the connection if the certificate fingerprint does not match.

The certificate and private key are stored in ~/.wasti-airpad/ and reused on later launches. Do not share host-key.pem. To rotate the computer identity, stop the companion and remove that folder; the next launch creates a new certificate and pairing code.

## Security notes

- The receiver binds to local interfaces on the selected port. Do not forward that port from your router or expose it to the public internet.
- TLS certificate pinning protects against a local-network impostor; the pairing code adds a second check.
- The pairing code changes each time the companion starts. The certificate fingerprint remains stable until the host identity files are removed.
- The receiver accepts only bounded mouse, key, and release messages. It does not provide shell execution, file access, clipboard access, or screen capture.
- Only allow the firewall prompt on private/trusted networks. Stop the companion when it is not needed.
- This first implementation uses manual IP entry. Automatic discovery, QR pairing, automatic reconnect, signed installers, and physical hardware certification are not yet implemented.


## Platform notes

- On macOS, grant the terminal or companion the Accessibility permission requested by the operating system so it can generate input.
- On Linux, X11 is the most compatible desktop session. Wayland compositors may restrict synthetic input; this is not yet certified.
- On Windows, elevated applications may reject synthetic input from a non-elevated companion. Keep the companion unprivileged unless you deliberately need to control an elevated window.
