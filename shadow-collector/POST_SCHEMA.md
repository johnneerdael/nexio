# Shadow Autoplay POST Schema

`POST /api/v1/shadow-autoplay-events`

Authorization:

```http
Authorization: Bearer <write-token>
Content-Type: application/json
```

Body:

```json
{
  "sentAtMs": 1775520000000,
  "client": {
    "appVersion": "0.35",
    "buildType": "debug",
    "deviceModel": "Google TV Streamer",
    "sdkInt": 34,
    "androidId": "2f4c0d13d9ab77e1"
  },
  "payload": {
    "event_version": 1,
    "event_type": "shadow_autoplay_decision",
    "request": {},
    "benchmarksUsed": [],
    "winners": [],
    "rejected": [],
    "selected": null
  }
}
```

Notes:

- `payload` should contain the full existing Nexio shadow autoplay JSON event without lossy transformation.
- `client.androidId` is optional for backward compatibility; older clients may omit it.
- The API stores a summarized index plus the complete raw envelope/payload JSON.
- Indefinite retention is the default; clearing data is an explicit dashboard action.
