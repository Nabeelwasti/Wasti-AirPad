# Wasti AirPad 1.2.0 — Release Candidate

**Version:** 1.2.0  
**Version code:** 6  
**Minimum Android version:** Android 9 (API 28)  
**Target Android version:** Android 16 (API 36)  
**Package:** `com.wasti.airpad`

## Highlights

- Bluetooth HID mouse and keyboard input from an Android device.
- Multi-touch gestures for pointer movement, scrolling, click actions, and supported keyboard shortcuts.
- Dedicated left- and right-click controls.
- Adjustable pointer sensitivity and natural-scroll preference.
- Improved HID registration, host-connection gating, callback cleanup, and report-send failure handling.
- Neutral mouse and keyboard reports are sent when local input is cancelled, the app pauses, or a host begins disconnecting, reducing the chance of a stuck input state.
- Improved Android 15/16 system-bar and display-cutout insets and touch-surface accessibility semantics.

## Privacy and permissions

- The app does not require internet access.
- Bluetooth permissions are requested at runtime on Android 12 and newer.
- Pairing and Bluetooth HID connections are managed by Android's Bluetooth stack.
- App backup is disabled.

## Usage notes

- Pair the Android device with the computer or other supported Bluetooth host.
- Open AirPad, wait for HID registration to complete, and connect to the paired host.
- Keep AirPad open while using it. This release does not implement a persistent background service.
- Bluetooth HID Device profile support varies by Android device manufacturer and host operating system.

## Validation status

The release candidate was built and signed by GitHub Actions. CI runs unit tests, debug/release lint, debug and release builds, APK alignment checks, JKS/private-key validation, APK Signature Scheme v2 verification, and signer-certificate fingerprint matching.

The signed APK is a **software-validated release candidate**. Physical Bluetooth pairing, input behavior, reconnect handling, and sustained-use testing on the target phone and host computer remain the final hardware acceptance gate. Do not describe those hardware scenarios as tested until they have actually been completed.
