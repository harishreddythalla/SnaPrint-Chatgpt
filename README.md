# Snap-to-Print — offline Android to Windows photo receiver

SnapToPrint is a local-only MVP for automatically copying photos taken during an active event from an Android phone to a Windows laptop. The phone keeps the original images. The Windows receiver writes validated files into a per-event folder.

## Current build status

- **Windows x64 executable:** portable Go receiver with a local browser dashboard. Initial pairing still uses the console prompt; after pairing, the dashboard opens at `http://127.0.0.1:8766`. No separate Go runtime is required.
- **Android APK:** Android source is included, with a GitHub Actions workflow to compile it. This workspace has no Android SDK or Gradle installation and no network access to obtain them, so an APK has not been produced here. Do not treat the Android source as an installable APK.
- **Real-device networking:** not yet verified on a physical Android phone and Windows laptop. Hotspot gateway discovery and phone-hosted service reachability must be tested on the intended phone model before relying on the tool at a paid event.

## First use (after APK build)

1. Install `app-debug.apk` on Android. On the first launch, grant access to **all images**. Limited/selected-photo access is insufficient for automatic Camera-folder monitoring.
2. Enable the phone's mobile hotspot. Connect the Windows laptop to that hotspot. Mobile data and internet access can remain off.
3. Launch `SnapToPrint-Windows-x64.exe`. Keep its console window open.
4. In Snap-to-Print on Android, name the event and tap **START EVENT**. This snapshots existing camera image IDs, saves the new event, and starts the local receiver service.
5. On the first Windows run, the receiver tries to find the phone service via the Windows default gateway. Enter the six-digit pairing code shown in the Android app when asked. The receiver saves the token under the Windows user's configuration directory.
6. Take photos with the normal Camera app. New images from `DCIM/Camera/` should be staged and transferred automatically. Windows saves complete verified images under `%USERPROFILE%\Pictures\SnapToPrint\` in an event-specific folder.
7. At event end, return to the Android app and tap **FINISH EVENT**. Pending photos are retained and retried; the queue is never discarded merely because the session ends.

The APK needs to be sideloaded, so Android may show an “install unknown apps” prompt for the app used to open it. Only install builds from a source you trust.

## Building the Android APK and Windows EXE with minimum setup

A GitHub Actions workflow is included at `.github/workflows/build.yml`. It builds and tests the Android project and builds the standalone Windows x64 executable.

1. Create a new GitHub repository and upload this project folder's contents (keep `.github/workflows/build.yml`). This is a one-time setup because this environment cannot upload to a new repository or access the Android SDK itself.
2. Open the repository's **Actions** tab and run **Build Snap-to-Print installers** (or push the source to `main`).
3. Open the successful run and download the `SnapToPrint-Installers` artifact. It contains the APK, the Windows EXE, and SHA-256 checksums.
4. Install the APK on Android and copy the EXE to the Windows laptop.

To build Android locally, install JDK 17, Android SDK Platform 35 and Build Tools 35.0.0, Gradle 8.9, then run from `android/`:

```bash
gradle --no-daemon :app:assembleDebug :app:testDebugUnitTest
```

To rebuild the Windows executable from any machine with Go 1.23+:

```bash
cd windows
go test ./...
GOOS=windows GOARCH=amd64 CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o ../dist/SnapToPrint-Windows-x64.exe .
```

## Useful receiver options

Double-click the EXE for the normal workflow. Optional options are available from Command Prompt:

```powershell
SnapToPrint-Windows-x64.exe -check-network
SnapToPrint-Windows-x64.exe -phone 192.168.43.1
SnapToPrint-Windows-x64.exe -dest "D:\\MagnetPhotos"
SnapToPrint-Windows-x64.exe -reset-pairing
```

The receiver defaults to `%USERPROFILE%\Pictures\SnapToPrint`. It writes a config file under the user's application-config directory. Pairing is stored locally so the six-digit code normally needs to be entered only once.

If pairing must be reset, first tap **Reset pairing (change laptop)** in the Android app, close the Windows receiver, then launch the EXE with `-reset-pairing` from Command Prompt and pair again.

## Important MVP limitations

- The first release expects the camera to save images in `DCIM/Camera/`. Other OEM camera paths need to be added after testing.
- The initial transport uses authenticated HTTP over the phone's WPA-protected hotspot. Use the phone's private hotspot, not a shared venue LAN. TLS is a future hardening item.
- The Windows receiver opens a local dashboard after pairing. It remains a portable executable rather than a traditional installer; keep its process running during the event. Event start/finish remain on Android.
- Only Android source code and the Windows executable are present when built in this restricted environment; the APK is generated by the supplied workflow.
- Confirm hotspot connectivity, screen-lock behavior, OEM battery management, and the 1–3 second latency target on real devices before production use.

## Dashboard and UI refresh

The Windows dashboard is served only on `127.0.0.1:8766`; it is not exposed to the phone hotspot and does not handle image bytes. It shows recent phone connectivity, last error, current event information, destination folder, and prior event folders/photo counts. Its polling is independent of the image transfer loop. Android shows active/inactive event state, event history, received/pending counts, pairing, and a quick troubleshooting guide. The transfer protocol and checksum/acknowledgement path are intentionally unchanged.

## Development layout

- `android/` — native Kotlin Android application.
- `windows/` — standalone Go Windows receiver.
- `protocol/` — protocol v1 and delivery semantics.
- `docs/` — operator checklist, architecture, and test guide.
- `.github/workflows/build.yml` — cloud build for both deliverables.
