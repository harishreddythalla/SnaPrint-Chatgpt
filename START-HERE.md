# SnapToPrint — Start Here

## What changed in this MVP refresh

- The Android app now uses clearer event labels, disables starting a second event while one is active, confirms before finishing an event, shows recent event history with received/pending counts, and includes a more useful quick setup/troubleshooting guide.
- The Android launcher now has a SnapToPrint camera icon.
- The Windows receiver opens a local dashboard after the initial pairing step. It shows current connection state, last response/error, event information, save path, photo counts across event folders, previous event folders, and an Open Photos Folder action. The dashboard refreshes locally every 2.5 seconds and does not participate in image transfer.
- The transfer protocol, authenticated photo endpoints, queue, checksum verification, atomic file writes, and acknowledgements are unchanged.

## Install/build latest version

1. Push this project to the connected GitHub repository, keeping `.github/workflows/build.yml`.
2. Open **Actions** and download the `SnapToPrint-Installers` artifact from the newest successful run.
3. Extract the ZIP. Install `SnapToPrint-Android-debug.apk` on the phone and replace the old Windows EXE with `SnapToPrint-Windows-x64.exe`.
4. Start the phone hotspot and connect the laptop to it. Start an event on Android.
5. Launch the Windows EXE. On first run, complete the six-digit pairing prompt in the console. After pairing, the dashboard opens in your default browser at `http://127.0.0.1:8766`.
6. Keep the receiver process running during the event. Use the dashboard to check connection status, browse event folders, and open the photos folder.

## Troubleshooting

- **Phone not found:** connect the laptop to the phone's hotspot, start an event in Android, and retry. Some phone hotspot models isolate clients or use a different gateway; use `-check-network` for discovery diagnostics.
- **Disconnected:** the receiver retries automatically. Leave the Android event active; pending photos remain queued on the phone.
- **Photos pending:** keep both apps running and wait for the connection to recover. Never delete the phone app's data while pending photos matter.
- **Pairing trouble:** close the receiver, use Reset pairing in Android, then run `SnapToPrint-Windows-x64.exe -reset-pairing` from Command Prompt and pair again.
- **Photo permission:** grant access to all images, not selected-only access. Keep the app's foreground notification visible during the event.
- **Dashboard doesn't open:** while the receiver is running, open `http://127.0.0.1:8766` manually. It is served only on the Windows laptop.

## Important notes

The dashboard connection indicator reflects recent successful responses from the phone; it does not claim to measure Wi-Fi RSSI/signal strength because that is not available through the existing transfer protocol. The dashboard is bound to localhost and is not exposed to the hotspot. Initial pairing still uses the existing console prompt. The dashboard is read-only except for opening the save folder; start/finish event controls remain on Android to keep event state unambiguous.

Test hotspot reconnects, screen-lock behavior, and camera-folder behavior on your actual devices before using this at an important event. The app remains an MVP, not a certified product.
