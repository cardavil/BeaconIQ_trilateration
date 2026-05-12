# BeaconIQ Test Console — Apps Script Backend

## Overview

REST backend for the BeaconIQ Android BLE trilateration testing app.
Receives test session data via HTTP POST and writes it to Google Sheets.
Also serves a web console (doGet) to browse recorded sessions.

## Architecture

| File | Responsibility |
|------|---------------|
| `Código.js` | Entry point: doPost/doGet routing, web UI API |
| `auth.js` | Token validation |
| `config.js` | Cached properties, timestamps, headers, `_ensureSheet` |
| `sessions.js` | Session recording orchestrator, ID generation, lookup helpers |
| `readings.js` | Batch write iBeacon parsed readings |
| `rawscans.js` | Batch write raw BLE scan data |
| `appsscript.json` | Apps Script manifest (runtime, scopes, webapp config) |
| `index.html` | Web console template |
| `css_styles.html` | Console styles (included via `<?!= include() ?>`) |
| `components_sessions.html` | Sessions list component |
| `js_main.html` | Console JavaScript |

## Setup

1. Script Properties (configure in Apps Script editor):
   - `BEACONIQ_TOKEN` — shared secret for endpoint auth
   - `SESSIONS_SHEET_ID` — BeaconIQ_Sessions spreadsheet ID
   - `READINGS_SHEET_ID` — BeaconIQ_Readings spreadsheet ID
   - `RAWSCANS_SHEET_ID` — BeaconIQ_RawScans spreadsheet ID

2. Three Google Spreadsheets, each with one tab matching the name:
   - BeaconIQ_Sessions (26 columns)
   - BeaconIQ_Readings (17 columns)
   - BeaconIQ_RawScans (8 columns)

   Headers are auto-created on first write by `_ensureSheet()`.

## Deploy

```
clasp push
```

Then deploy via the Apps Script UI:
- Open https://script.google.com
- Deploy > New deployment > Web app
- Execute as: Me
- Who has access: Anyone
- Deploy and authorize

## Test

```
curl -L --post301 --post302 --post303 \
  -H "Content-Type: application/json" \
  -d '{"auth":"YOUR_TOKEN","action":"list_sessions"}' \
  "YOUR_DEPLOY_URL"
```

## Actions

### record_session

Writes session data to 3 sheets (sessions, readings, raw scans).

Request:
```json
{
  "auth": "YOUR_TOKEN",
  "action": "record_session",
  "session": {
    "timestamp_start": "2026-04-26 14:30:00",
    "timestamp_end": "2026-04-26 14:32:00",
    "analyst": "Carlos",
    "duration_sec": 120,
    "movement_mode": "standing",
    "room": "Living room",
    "phone_position": "hand chest height",
    "phone_model": "SM-S906B",
    "android_version": "16",
    "txpower": -59,
    "path_loss_n": 2.0,
    "rssi_threshold": -100,
    "beacons_detected": 3,
    "total_scan_results": 847,
    "ibeacon_hits": 312,
    "rejected_count": 535,
    "model_phase": "phase_1",
    "kalman_q": 0.05,
    "kalman_r": 0.25,
    "rssi_buffer_size": 20,
    "rssi_time_window_ms": 8000,
    "scale_factor": 5.0,
    "beacon_timeout_ms": 4000,
    "eval_interval_ms": 3000,
    "notes": "Metal shelf near beacon 2"
  },
  "readings": [
    {
      "timestamp_ms": 1745678432156,
      "beacon_id": "f7826da6-.../100/1",
      "uuid": "f7826da6-4fa2-4e98-8024-bc5b71e0893e",
      "major": 100, "minor": 1,
      "rssi_raw": -67, "rssi_filtered": -65.3,
      "distance_m": 2.41, "est_x": 1.83, "est_y": 2.47,
      "model_phase": "phase_1",
      "dist_no_kalman": 2.82,
      "radar_closest": "2,1",
      "scan_nearest_rssi": "2,1",
      "service_closest": "2,1",
      "dual_conflict": false
    }
  ],
  "raw_scans": [
    {
      "timestamp_ms": 1745678432156,
      "device_address": "D5:0A:A3:C8:62:86",
      "company_id": "0x004C", "rssi": -72,
      "was_ibeacon": false,
      "data_hex": "10061d1db9ea8348",
      "reject_reason": "apple_not_ibeacon"
    }
  ]
}
```

Response:
```json
{
  "error": false,
  "session_id": "BIQ-0001",
  "readings_count": 1,
  "raw_scans_count": 1
}
```

### list_sessions

Returns all recorded sessions (newest first).

Request:
```json
{ "auth": "YOUR_TOKEN", "action": "list_sessions" }
```

Response:
```json
{
  "sessions": [
    {
      "session_id": "BIQ-0001",
      "timestamp_start": "2026-04-26 14:30:00",
      "analyst": "Carlos",
      "duration_sec": 120,
      "room": "Living room",
      "beacons_detected": 3,
      "ibeacon_hits": 312
    }
  ]
}
```

## Notes

- This README is ignored by clasp (only .js, .html, and .json are pushed)
- All files use `var` (not const/let) to match DiversoLAB conventions
- Batch `setValues()` is used for readings and raw scans (not appendRow loops)
- Session IDs follow the `BIQ-NNNN` pattern with LockService mutex
