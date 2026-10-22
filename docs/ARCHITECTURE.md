# Architecture and reliability notes

## Data flow

1. Operator taps Start Event while the Android app is foregrounded.
2. Android snapshots image IDs currently in `DCIM/Camera/`, then persists the event start timestamp and baseline in SQLite.
3. A foreground service hosts the local HTTP API on TCP port 8765 and observes MediaStore notifications while also reconciling at approximately one-second intervals.
4. Only post-baseline media records from the camera folder are considered. Capture time is checked against the session start. Ambiguous records are rechecked when staging.
5. Before transfer, the app copies the source image into app-private storage, verifies that its size is stable, calculates SHA-256, flushes the staged file, and changes the queue state to `QUEUED`.
6. Windows polls the queue, streams one image, validates byte count and SHA-256, flushes a `.part` file, then atomically renames it to a deterministic final filename.
7. Windows posts the matching SHA-256 and byte count as an acknowledgement. Only then may Android mark the queue item acknowledged and delete its temporary staged copy.

## Core invariants

- The phone gallery original is never modified or deleted.
- A new event stores its own session UUID and baseline; event IDs are never reused.
- Queue records are persisted before any staged transfer is advertised.
- A final Windows file is not visible until integrity checks pass and finalization succeeds.
- An ACK must match both the source SHA-256 and byte size.
- A retry uses the same photo ID and deterministic event/sequence filename.
- A lost ACK response is recoverable: Windows checks a pre-existing final file against expected metadata and repeats the ACK if it matches.
- Media notifications are an optimization; periodic MediaStore scanning is the recovery path.

## File-system durability

Windows flushes the `.part` file before rename. Android flushes the staged file before renaming it. SQLite transactions persist event and queue state. The first MVP does not claim protection from storage-controller failure, corrupted filesystems, force-stopping the Android app, or phone loss. It does retain unacknowledged staged files through ordinary process death and network interruptions.

## Network

Phone hosts on all interfaces at port 8765 because Android's hotspot interface and address vary by device. Windows automatically tries IPv4 default-route gateways, then permits an explicit phone address for unusual networks. The local `/health` route is unauthenticated for discovery, but all session and photo routes require the paired token. The pair route accepts a six-digit code and is rate-limited while the service process is alive.

Use a private phone hotspot protected by WPA2/WPA3. The current HTTP transport is appropriate only for an isolated private hotspot and must not be exposed to an untrusted network. TLS should be added before supporting shared networks.

## Platform assumptions needing validation

- The Android phone permits hotspot clients to access a service listening on TCP 8765.
- The hotspot gateway used by Windows matches the phone service address on that specific OEM and network configuration.
- The camera saves still photos in `DCIM/Camera/`.
- The target phone continues the connected-device foreground service while the Camera app is foregrounded and the screen is locked.
- MediaStore publishes reliable `DATE_TAKEN`, `DATE_ADDED`, and relative-path metadata for the target camera application.
