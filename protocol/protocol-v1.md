# Snap-to-Print local protocol v1

All requests use the phone's local hotspot address and TCP port `8765`. No DNS, internet access, or cloud endpoint is used. The phone hosts the HTTP service; the Windows client polls the phone. Requests include `Connection: close` semantics in the first MVP, so each response can be safely retried.

## Health and pairing

### `GET /api/v1/health`

Public discovery endpoint. Response:

```json
{"ok":true,"service":"snap-to-print","version":1}
```

### `POST /api/v1/pair`

Public only before pairing. Body:

```json
{"code":"123456"}
```

On success, returns a cryptographically random 256-bit token:

```json
{"token":"<64 hex characters>","protocolVersion":1}
```

The Android phone stores this token; Windows stores it in its per-user application configuration. Subsequent pairing requests are denied until pairing is explicitly reset on the phone. All non-health/non-pairing endpoints require the `X-Snap-Token` header. Pairing attempts are rate-limited in the running service.

### `GET /api/v1/session/current`

Returns the most recently started session, or an idle session object when no event has started:

```json
{"sessionId":"<uuid>","name":"Guest magnets","startedAtMs":1792677600000,"active":true,"status":"ACTIVE"}
```

### `GET /api/v1/queue/next`

Returns the oldest staged, unacknowledged photo across all sessions:

```json
{"empty":false,"photo":{"photoId":"<uuid>","sessionId":"<uuid>","eventName":"Guest magnets","sessionStartedAtMs":1792677600000,"sequence":1,"fileName":"IMG_0001.jpg","mimeType":"image/jpeg","captureTimeMs":1792677601000,"size":1234567,"sha256":"<sha256>"}}
```

When nothing is ready, returns `{"empty":true}`. A not-yet-staged photo does not block a later ready photo.

### `GET /api/v1/photos/{photoId}/content`

Streams the original staged photo bytes. A successful response includes `Content-Length`, `X-Photo-Sha256`, and `X-Photo-Size`. The receiver must still calculate its own size and SHA-256 before finalizing the file.

### `POST /api/v1/photos/{photoId}/ack`

Body:

```json
{"sha256":"<sha256>","size":1234567}
```

The phone accepts an acknowledgement only if both values match its staged record. The operation is idempotent for an already acknowledged photo. Successful acknowledgement makes the queue item eligible for staged-file cleanup; the original MediaStore image is never removed.

### `POST /api/v1/photos/{photoId}/nack`

Asks the phone to retry staging a photo. This is a recovery hint, not proof of receipt.

## Delivery semantics

- A `photoId` is unique and an image is only eligible for the queue after it is copied to app-private staging storage and hashed.
- Windows writes into a same-volume `.part` file, flushes it, verifies size and hash, and renames it to its deterministic final name.
- Windows acknowledges only after the final file is verified and committed.
- The phone never deletes the original camera file.
- On a lost ACK response, Windows can redownload the same item or validate an already committed file, then repeat the ACK. It must never create a second completed file for the same item.

## Errors

- `400`: malformed request/body.
- `401`: missing or invalid pairing token.
- `403`: invalid pairing code or pairing already provisioned.
- `404`: unknown endpoint or photo is no longer queued.
- `409`: photo is not ready or ACK metadata does not match.
- `429`: too many pairing attempts.

## MVP limitations

- Transport is plain HTTP over the encrypted local Wi-Fi hotspot rather than TLS. It is authenticated with a random token, but this is not a substitute for TLS on an untrusted shared network. Use a private phone hotspot and do not bridge it to an untrusted venue LAN.
- The phone app initially monitors `DCIM/Camera/`. Device-specific camera folder conventions and hotspot reachability must be validated on the actual Android model.
- The Android implementation uses a date-added query plus a pre-event media-ID baseline and capture-time metadata check. It periodically reconciles in addition to registering a media observer. MediaStore provider behavior still needs real-device testing.
