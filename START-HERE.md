# Snap-to-Print — Start Here

## What you can install right now

- **Windows:** `dist/SnapToPrint-Windows-x64.exe` is a standalone Windows x64 executable. It does not need Go or a separate runtime. It is a console app, so leave its window open during the event.
- **Android:** The Kotlin source is included, but there is **not yet an APK in this package**. An Android SDK/Gradle build is needed first; this build workspace cannot reach Google's SDK download hosts.

## Fastest route to get the APK

1. Create a new repository on GitHub.
2. Upload the *contents* of this project folder to the repository root. Keep the hidden `.github/workflows/build.yml` file. Ensure the repository is not empty and the workflow file is present under `.github/workflows/`.
3. Open **Actions**, choose **Build Snap-to-Print installers**, and run it (or push to `main`/`master`).
4. After the workflow succeeds, open that run and download the **SnapToPrint-Installers** artifact. It contains `SnapToPrint-Android-debug.apk`, `SnapToPrint-Windows-x64.exe`, and `SHA256SUMS.txt`.
5. On Android, install the APK (Android may ask you to allow installation from the app used to open it). On Windows, copy the EXE where convenient and double-click it.

The GitHub workflow builds the APK and EXE; no Android Studio installation is required on your own computer. Do not use the application for a paid event until you complete the real-device checks in `docs/REAL_DEVICE_TESTS.md`.

## First local test

1. Enable the Android phone's hotspot.
2. Connect the Windows laptop to the phone's hotspot, with internet/mobile data turned off if desired.
3. Open Snap-to-Print on Android, grant access to all images, name a test event and tap **START EVENT**.
4. Launch `SnapToPrint-Windows-x64.exe`. On the first run, enter the six-digit pairing code displayed by the phone.
5. Take a new photo with the normal Camera app. Check that a verified copy appears in the Windows event folder under `%USERPROFILE%\Pictures\SnapToPrint\`.
6. Test with the laptop temporarily disconnected from Wi-Fi, then reconnect and verify that queued photos arrive.

## Important limits

This is an early MVP, not production-certified software. The Windows receiver and its transfer path passed local automated/integration checks. The Android APK has not yet been built in this workspace, and hotspot reachability, background behavior, and timing still require real-device validation. The receiver currently uses HTTP on the private phone hotspot; never expose it to a shared or untrusted network.
