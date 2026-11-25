# AirPad 1.3.1 — Release Candidate

## Bluetooth HID reliability
- Handles host protocol negotiation and emits the correct Boot Protocol or Report Protocol mouse and keyboard reports.
- Responds to HID GET_REPORT requests and supports keyboard LED output reports over control and interrupt channels.
- Reports Android-side input acceptance/rejection rather than silently ignoring failed HID sends.
- Adds a **Test HID** control that sends a small pointer movement and Space-key press and reports accepted/rejected report counts.
- Restores the original palm-rejection threshold.
- Preserves one-finger pointer, left/right tap, edge scrolling, two-finger scrolling/pinch, and three/four-finger shortcut gestures.

## Wi-Fi reliability
- Persistent per-host phone trust: phone token encrypted by Android Keystore; desktop stores only SHA-256 token hashes.
- Authenticated heartbeat every 10 seconds and a 30-second idle timeout.
- Automatic reconnect with 1, 2, 4, 8 and 16 second backoff, up to five attempts.
- Retry budget resets only after a 60-second stable connection, preventing rapid reconnect loops.
- Manual disconnect/retry remains available.

## Security and privacy
- Wi-Fi transport remains TLS with pinned desktop certificate.
- Local-network address validation and first-pairing code protection remain enabled.
- No cloud account, telemetry, or external service is required.

## Verification
- Android unit tests, lint, debug/release builds, APK alignment and signed APK verification run in GitHub Actions.
- Python companion tests and dependency audit run in GitHub Actions.
- A successful CI build does not replace physical acceptance testing.

## Required acceptance tests
1. Install on Redmi Note 14 Pro running Android 16 and keep AirPad in the foreground.
2. Pair with a Windows host and verify the **Test HID** pointer movement and Space key.
3. Verify normal pointer movement, left/right click, drag, scrolling and keyboard shortcuts.
4. Test Bluetooth disconnect/reconnect, Bluetooth toggle, app pause/resume and a sustained 30-minute session.
5. Test Wi-Fi first pairing, trusted reconnect, heartbeat loss, router/Wi-Fi interruption and bounded retry behavior.
6. Confirm host trust revocation and pairing recovery.

## Installation identity note
The original Termux prototype used application ID `com.example.airpad`. The branded application uses `com.wasti.airpad`, so Android treats them as separate installed apps. Open **Wasti AirPad** to use this release candidate.
