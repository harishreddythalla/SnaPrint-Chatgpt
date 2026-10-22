# Real-device acceptance checklist

Perform these tests before using Snap-to-Print for a paid event.

## Setup and offline connectivity

- [ ] Install the APK and grant access to all images.
- [ ] Enable the phone hotspot; connect Windows; disable mobile data and any internet route.
- [ ] Start a test event and leave the Android app notification visible.
- [ ] Launch Windows receiver; confirm it detects the phone, pairs successfully, and persists the pairing.
- [ ] Restart the receiver and confirm it reconnects without asking for the code again.
- [ ] Run `SnapToPrint-Windows-x64.exe -check-network` if discovery fails.

## Event cutoff

- [ ] Place at least 50 existing images in the camera folder before Start Event.
- [ ] Start a new event; none of those existing images should be copied.
- [ ] Take 20 new photos using the normal Camera app; all 20 should arrive exactly once.
- [ ] Capture a photo immediately before the event start; verify it is excluded when capture metadata proves it predates the event.
- [ ] Capture a photo immediately after event start; verify it is included.
- [ ] Confirm screenshots and downloaded images are not imported.

## Interruptions

- [ ] Take 10 photos while turning Wi-Fi off on the Windows laptop, then reconnect. All 10 should arrive automatically.
- [ ] Disconnect during the transfer of a large photo. Verify the final folder contains no `.part` or corrupted final file and the photo is retried.
- [ ] Close and reopen the Windows receiver while several photos are queued.
- [ ] Lock the Android screen and switch to Camera. Take several photos, then unlock and verify delivery.
- [ ] Return to Android after the app process has been removed by the OS, reopen the app, and confirm event/queue state is retained.
- [ ] Finish an event while items are pending. Confirm the queue remains and the next event's folder does not receive those items.
- [ ] Test low storage or a destination folder that is read-only; the apps must show errors and must not ACK unsaved files.

## Performance and correctness

- [ ] Transfer 100 images with internet disabled.
- [ ] Record capture-save-to-file-visible latency for at least 30 images; inspect p50 and p95.
- [ ] Compare the phone's eligible image list with the Windows event folder.
- [ ] Confirm every completed file opens and has the expected size.
- [ ] Confirm no final filenames are overwritten and the original phone photos remain unchanged.
- [ ] Repeat on the exact Android phone/model/OS version and Windows laptop that will be used at events.
