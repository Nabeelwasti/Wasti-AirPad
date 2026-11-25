# Wasti AirPad

Current release-candidate line: **1.3.0**. Bluetooth and local Wi-Fi software checks pass in GitHub Actions; real-device acceptance is still required before public release. See [release notes](docs/RELEASE_NOTES_1.3.0.md), [third-party notices](THIRD_PARTY_NOTICES.md), [security policy](SECURITY.md), and the [open-source research roadmap](docs/OPEN_SOURCE_RESEARCH_AND_FEATURE_ROADMAP.md).

Wasti AirPad turns a compatible Android phone into a wireless Bluetooth mouse and touchpad for a paired computer. The phone presents itself as a Bluetooth HID device; the computer receives standard mouse and keyboard reports without a companion desktop application.

## Features

- One-finger pointer movement with adjustable sensitivity.
- Single-finger tap to left-click and two-finger tap to right-click.
- Double-tap and hold to drag.
- Two-finger vertical scrolling.
- Two-finger pinch gesture for Ctrl+wheel zoom on the host.
- Right-edge one-finger scrolling.
- Three- and four-finger shortcuts for supported desktop operating systems.
- Dedicated left- and right-click controls.
- Adjustable pointer sensitivity and natural-scroll preference saved on the device.
- Haptic touch feedback.
- Optional local Wi-Fi control through the Wasti AirPad desktop companion, using TLS certificate pinning and an eight-digit pairing code.

## Requirements and compatibility

- Android 9 (API 28) or newer.
- Bluetooth hardware and **Bluetooth HID Device (peripheral) profile support** on the Android device.
- A computer that supports Bluetooth HID mouse/keyboard devices.
- Bluetooth and Android **Nearby devices** permissions enabled for Bluetooth mode.
- For Wi-Fi mode, a computer running the AirPad desktop companion and both devices on the same trusted local network.

Android exposes the HID Device profile through the system Bluetooth stack, but not every phone manufacturer enables it. A successful APK build does not guarantee that a particular phone supports HID peripheral mode. If AirPad reports that HID registration is unavailable, this is a device/firmware limitation.

## Pair and connect

1. Install and open AirPad on the Android device.
2. Turn on Bluetooth and grant the requested Nearby devices permissions.
3. Tap **Pair mode** and accept Android's discoverability prompt.
4. On the computer, open Bluetooth settings and pair with **Wasti AirPad** (the displayed name may vary by Android version).
5. Return to AirPad and tap **Connect**.
6. Select the paired computer. The status line should show the connection state.
7. Use the touch surface as a trackpad. Dedicated left- and right-click buttons are also available below it.
8. Adjust pointer sensitivity and natural scrolling; these preferences are saved locally.

If Bluetooth connection fails, remove the old pairing from both devices, pair again, and retry. Keep AirPad open while testing. Returning to AirPad after enabling Bluetooth in Android Settings triggers a fresh HID initialization attempt.

## Connect over the same Wi-Fi network

Wi-Fi mode requires the desktop companion because a computer cannot receive ordinary Wi-Fi mouse/keyboard events from a phone without a host-side receiver. The companion is in [wifi-companion](wifi-companion/README.md).

1. On the computer, install Python 3.11 or newer and run the companion using the steps in its README (Windows users can double-click wifi-companion/run-windows.bat).
2. Allow it only on **Private networks** if the operating-system firewall asks.
3. Keep the companion running. It prints the computer's local IPv4 address, port, eight-digit pairing code and certificate SHA-256 fingerprint.
4. Connect the phone and computer to the same trusted Wi-Fi network. Guest Wi-Fi or router client isolation can block local connections.
5. Easiest: scan the companion's QR code with the phone camera and open the Wasti AirPad link. Confirm the computer in AirPad. Or tap **Wi-Fi** → **Find computers on this Wi-Fi** and select the discovered computer. Manual IP entry remains available if discovery is blocked.
6. If entering details manually, enter the eight-digit pairing code and certificate fingerprint. AirPad verifies the certificate before sending input. Tap **Wi-Fi** again to disconnect; tap **Connect** to return to Bluetooth mode.

Wi-Fi input is encrypted with TLS and certificate-pinned. The pairing code is not saved on the phone. Use a private LAN address only; do not forward the companion port from your router. Automatic DNS-SD discovery, QR invitation pairing, manual IP fallback and automatic port fallback are implemented. Automatic reconnect, a desktop tray app and signed desktop installers remain future work.

## Gesture reference

| Gesture | Action |
| --- | --- |
| One finger move | Move pointer |
| One finger tap | Left click |
| Two finger tap | Right click |
| Double tap, then hold/move | Drag |
| Two finger vertical move | Scroll |
| Two finger pinch | Ctrl + mouse wheel |
| One finger on right edge | Scroll |
| Three finger tap | Win + S |
| Four finger tap | Win + A |
| Three finger vertical swipe | Win + Tab / Win + D |
| Three finger horizontal swipe | Alt + Tab / Shift + Alt + Tab |
| Four finger horizontal swipe | Ctrl + Win + Left / Right |
| Four finger vertical swipe | Win + Tab / Win + D |

Keyboard shortcuts are designed primarily for Windows hosts. Other operating systems may map the same HID key combinations differently.

## Build from source

The project uses Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.14.8, JDK 17, and compiles/targets Android 16 (API 36). The minimum supported Android version remains Android 9 (API 28).

Run: `./gradlew clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease` (Windows: `gradlew.bat ...`). The repository includes the official Gradle 8.14.8 Wrapper and verifies the distribution SHA-256 before use.

The installable debug APK is generated at app/build/outputs/apk/debug/app-debug.apk. The release build is unsigned by default (`app/build/outputs/apk/release/app-release-unsigned.apk`). If all four protected GitHub Actions signing secrets are configured, CI instead produces and uploads `app-release.apk` as the **AirPad-release-signed** artifact.

GitHub Actions validates the Gradle Wrapper, runs Kotlin unit tests, Android debug/release lint, checks APK alignment and checksums, and builds debug/release APKs. A separate Wi-Fi Companion workflow compiles the Python receiver, smoke-tests its CLI, runs protocol tests and audits pinned Python dependencies with pip-audit. A separate protected signing job validates the JKS private-key alias, signs the release APK, verifies APK Signature Scheme v2 and certificate identity, and uploads the signed artifact. The downloadable debug artifact is named **AirPad-apk**. Release artifacts are named **AirPad-release-unsigned** by default or **AirPad-release-signed** when signing secrets are configured.

## License status

This repository does not currently contain a top-level project licence. Public visibility on GitHub does not by itself grant permission to reuse, modify, or redistribute the project. The repository owner must choose a licence before calling AirPad open source or accepting third-party code contributions. Third-party dependency notices are separate and do not grant a licence to AirPad itself.

## Privacy

AirPad does not use an account, analytics SDK, cloud relay or telemetry. Bluetooth input is sent to the paired Bluetooth host. Wi-Fi input is sent only to the local desktop companion over TLS after certificate and pairing-code verification. The desktop companion receives mouse/keyboard events only; it does not provide shell execution, screen capture, file access or clipboard access.

## Known limitations

- Bluetooth HID peripheral support depends on the Android device manufacturer and firmware.
- Real pairing, connection stability, mouse movement, scrolling, and shortcut behavior must be validated on the target phone and host computer.
- A debug APK is for testing. A signed release requires a privately generated keystore and the protected GitHub Actions secrets documented in [Release signing](docs/RELEASE_SIGNING.md). Never commit or send the keystore/passwords in chat.
- Gesture recognition can vary with screen size, touch hardware, and host operating system. AirPad releases local drag state when the app loses focus or is paused.

## Project structure

- app/src/main/java/com/wasti/airpad/MainActivity.kt — UI and Bluetooth HID lifecycle plus Wi-Fi transport routing.
- app/src/main/java/com/wasti/airpad/WifiRemoteClient.kt — pinned TLS local-network client.
- app/src/main/java/com/wasti/airpad/WifiHostDiscovery.kt — DNS-SD/mDNS desktop discovery and legacy multicast compatibility.
- wifi-companion/ — Python desktop receiver, bounded input protocol, tests and setup instructions.
- docs/OPEN_SOURCE_RESEARCH_AND_FEATURE_ROADMAP.md — competitor research, license notes and product roadmap.
- app/src/main/java/com/wasti/airpad/HidReports.kt — HID descriptor and report encoding.
- app/src/main/java/com/wasti/airpad/GesturePolicy.kt — deterministic gesture classification thresholds.
- app/src/main/AndroidManifest.xml — Bluetooth permissions and application declaration.
- app/build.gradle.kts — Android configuration and unit-test dependency.
- app/src/test/ — HID report and gesture policy unit tests.
- docs/PRODUCT_HARDENING_ROADMAP.md — researched engineering roadmap and hardware acceptance matrix.
- .github/workflows/build.yml — CI verification and APK artifact workflow.
