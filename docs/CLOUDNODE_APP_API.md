# CloudNode Android API contract

This contract is the integration target for the Android client. The Android app must not calculate or persist free-session deadlines, traffic totals, quotas, or renewal state. The server is authoritative.

## Public routing

- Android base URL: `https://cloudnodes.ru/api`
- Nginx routes `/api/` to the CloudNode FastAPI service on loopback port 8081, stripping the `/api` prefix.
- All other paths continue to be proxied to Marzban on port 8000.
- Marzban's administrative API must not be exposed publicly; the CloudNode API accesses it over loopback.

## Authentication

### POST /app/register

Unauthenticated first call from an installation.

Request:
```json
{"device_id":"ANDROID_ID","platform":"android","app_version":"1.0"}
```

Response:
```json
{"access_token":"opaque-random-device-token","is_paid":false}
```

The server binds the opaque random token to the device. Store only this credential on-device. Do not embed a shared API key or Marzban credentials in the APK. Token revocation and rotation are server-controlled.

All other /app routes require `Authorization: Bearer <access_token>`.

## Session rules

- Initial free session: 30 minutes and 250 MiB.
- Server creates/updates the corresponding Marzban user with matching expiry and data limit.
- Server owns all timestamps, traffic totals, remaining quota, renewal decisions and end reasons.
- No local timer, traffic counter, quota, or session deadline may determine access.
- The app polls status while connected (target interval: 5 seconds) for UI and prompt display only. Marzban/Xray/server enforcement must terminate access independently of the app.
- When traffic is exhausted before time expires, preserve remaining time. A rewarded ad adds 250 MiB to the same session; it does not reset its deadline.
- When time expires, a rewarded ad creates a fresh 30-minute / 250-MiB session.
- If the user declines renewal or manually stops, the server revokes/deletes the free Marzban user.
- For app closure/process death, status polling acts as a heartbeat. Server should revoke abandoned free access after a short configurable heartbeat timeout (recommended 90 seconds); foreground/background transitions must not be treated as a user stop.
- Paid users are never subjected to free-session limits or free-user deletion.
- Referral-tagged users follow the separately agreed protection rule: preserve access until their 5-hour entitlement or traffic exhaustion; the server must identify referral entitlement and apply it before ordinary free-session logic.
- Do not block torrents or classify traffic by protocol. Apply only total quota.

## Endpoints

### GET /app/paid

Response: `{"is_paid":true|false}`. Paid status is resolved server-side from the existing Telegram/Marzban account mapping.

### POST /app/session/start

Starts the initial free session if eligible, or returns the existing active session. Must not overwrite paid or protected referral users.

Response fields:
- `success`
- `status`: `active`, `blocked`, or `ended`
- `profile_link`: current VLESS link when a profile must be imported/refreshed
- `used_bytes`, `remaining_bytes`, `remaining_seconds`
- `end_reason`: null, `traffic`, `time`, `manual_stop`, `heartbeat_timeout`, or other stable reason
- `is_paid`

### GET /app/session/status

Same session fields as start. This endpoint also refreshes the server-side heartbeat. Status must be derived from Marzban/Xray usage and server timestamps, never from values sent by the phone.

### POST /app/session/reward

Request:
```json
{"reward_id":"unique-client-generated-id","reward_type":"traffic_extension","ad_network":"yandex","sdk_rewarded":true}
```

Allowed reward types:
- `traffic_extension`: add exactly 250 MiB while preserving the current session deadline.
- `new_session`: create exactly 30 minutes and 250 MiB after the previous session time has expired.

The server must enforce idempotency on `reward_id`, reject mismatched reward types/state, and never accept arbitrary amounts from the client. The Android client calls this only after both Yandex SDK `onRewarded` and `onAdDismissed`.

**Security requirement:** the SDK callback is client-side evidence, not cryptographic proof to the server. Before public production release, implement and validate a supported Yandex server-side reward verification/callback if available. Until then, rate-limit and log reward claims; do not describe client callback validation as tamper-proof.

### POST /app/session/stop

Revokes the current free entitlement and deletes/disables the corresponding Marzban user. Must be idempotent and must never delete a paid account.

### POST /app/bridge

Creates the short-lived bridge profile used by the app to open the Telegram bot. Device identity comes from the authenticated token, not a client-supplied username.

## Status response example

```json
{
  "success": true,
  "status": "active",
  "is_paid": false,
  "profile_link": "vless://...",
  "used_bytes": 52428800,
  "remaining_bytes": 209715200,
  "remaining_seconds": 1200,
  "end_reason": null
}
```

The app must not display the total initial quota (250 MiB) as a promotional amount. It may display used and remaining traffic and remaining time.

## Enforcement requirements

The backend must use the actual Marzban version and its supported API to set/update user limits and revoke access. Do not assume that changing only the database row or polling from Android is sufficient. On each quota extension, update/reset the Marzban traffic allowance correctly while preserving cumulative session accounting in the CloudNode database. Persist state in SQLite or another durable database, use transactions/unique constraints for reward idempotency, and reconcile state after service restart.
