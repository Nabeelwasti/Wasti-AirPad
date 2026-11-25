# Wasti AirPad

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

## Requirements and compatibility

- Android 9 (API 28) or newer.
- Bluetooth hardware and **Bluetooth HID Device (peripheral) profile support** on the Android device.
- A computer that supports Bluetooth HID mouse/keyboard devices.
- Bluetooth and Android **Nearby devices** permissions enabled.

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

If connection fails, remove the old pairing from both devices, pair again, and retry. Keep AirPad open while testing. Returning to AirPad after enabling Bluetooth in Android Settings triggers a fresh HID initialization attempt.

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

The project uses Android Gradle Plugin 8.13.2, Kotlin 2.3.0, Gradle 8.13, JDK 17, and compiles/targets Android 16 (API 36). The minimum supported Android version remains Android 9 (API 28).

Run: gradle clean testDebugUnitTest lintDebug assembleDebug

The installable debug APK is generated at app/build/outputs/apk/debug/app-debug.apk. The release build is generated as app/build/outputs/apk/release/app-release-unsigned.apk and is intentionally unsigned.

GitHub Actions runs unit tests, Android lint, and both debug and unsigned release APK builds on pushes to main, pull requests, and manual workflow dispatch. The downloadable workflow artifact is named **AirPad-apk**.

## Privacy

AirPad does not need an internet connection, account, analytics SDK, or server. Bluetooth permissions are used to register the HID peripheral, discover bonded hosts, and send input reports. Input is sent to the connected Bluetooth host.

## Known limitations

- Bluetooth HID peripheral support depends on the Android device manufacturer and firmware.
- Real pairing, connection stability, mouse movement, scrolling, and shortcut behavior must be validated on the target phone and host computer.
- The CI artifact is a **debug APK**, not a signed production release. A distributable release requires a securely managed signing key and a release-signing process; never commit a keystore or passwords to this repository.
- Gesture recognition can vary with screen size, touch hardware, and host operating system. AirPad releases local drag state when the app loses focus or is paused.

## Project structure

- app/src/main/java/com/wasti/airpad/MainActivity.kt — UI and Bluetooth HID lifecycle.
- app/src/main/java/com/wasti/airpad/HidReports.kt — HID descriptor and report encoding.
- app/src/main/java/com/wasti/airpad/GesturePolicy.kt — deterministic gesture classification thresholds.
- app/src/main/AndroidManifest.xml — Bluetooth permissions and application declaration.
- app/build.gradle.kts — Android configuration and unit-test dependency.
- app/src/test/ — HID report and gesture policy unit tests.
- docs/PRODUCT_HARDENING_ROADMAP.md — researched engineering roadmap and hardware acceptance matrix.
- .github/workflows/build.yml — CI verification and APK artifact workflow.
