# Saathi Android App

## Project

Native Android voice receptionist app for Indian restaurants. Intercepts incoming calls via Android's `ConnectionService` API, streams audio directly to Gemini Live (client-direct, bypassing Phoenix server), and executes tool calls (reservations, menu queries) through the AEGIS backend REST API.

**GitHub:** https://github.com/lightforgedev/saathi-app-android  
**Backend:** https://github.com/lightforgedev/aegis-api-phoenix (Phoenix/Elixir)  
**Architecture:** ADR-070 at `aegis-api-phoenix/docs/decisions/ADR-070-saathi-android-app-architecture.md`  
**API Contract:** `aegis-api-phoenix/docs/api/SAATHI_DEVICE_API.md`

## Tech Stack

| Layer | Library |
|---|---|
| Language | Kotlin 2.1+ |
| UI | Jetpack Compose + Material 3 |
| DI | Hilt 2.53.1 |
| Local DB | Room 2.6.1 |
| Networking | Retrofit 2.11 + OkHttp 4.12 |
| Serialization | Kotlin Serialization (JSON) |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 |

## Package Structure

```
dev.lightforge.saathi
├── telecom/          # Call interception (ConnectionService, Connection, PhoneAccountManager)
├── voice/            # Gemini Live client, AudioPipeline, ToolCallRelay
├── network/          # Retrofit API client, persistent WebSocket, auth interceptor
├── auth/             # Device pairing (OTP flow), TokenManager (Android Keystore)
├── data/             # Room DB — entities, DAOs, sync manager
│   ├── entity/       # MenuItemEntity, ReservationEntity, RestaurantConfigEntity, RecentCallerEntity
│   ├── dao/          # DAOs for each entity
│   └── sync/         # DataSyncManager (full + delta sync)
├── service/          # SaathiForegroundService, BootReceiver
├── ui/               # Compose screens
│   ├── home/         # HomeScreen — big toggle + today's stats
│   ├── setup/        # SetupScreen — first-launch OTP pairing
│   ├── calllog/      # CallLogScreen — today's calls
│   ├── settings/     # SettingsScreen — call mode, hours, language
│   └── theme/        # SaathiTheme (orange/amber Material 3)
└── di/               # Hilt AppModule
```

## Architecture

### Call Flow (Inbound)

```
Customer calls restaurant number
    → Android rings
    → SaathiConnectionService.onCreateIncomingConnection()
    → SaathiConnection created (RINGING state)
    → owner.answer() or auto-answer
    → SaathiConnection.onAnswer()
        → POST /api/v1/saathi/session  (get ephemeral Gemini token)
        → GeminiLiveClient.connect(gemini_ws_url)
        → AudioPipeline.start()
            → AudioRecord (16kHz PCM) → base64 → Gemini WS
            → Gemini WS → base64 decode → AudioTrack (24kHz) → call audio
        → ToolCallRelay listens to GeminiLiveClient.toolCallFlow
            → POST /api/v1/saathi/session/:id/tool
            → result → Gemini WS toolResponse
    → Call ends
        → POST /api/v1/saathi/session/:id/end
        → Backend sends WhatsApp summary to owner
```

### Client-Direct Gemini (NOT through Phoenix)

Audio flows: Phone mic → Gemini Live → Phone speaker  
AEGIS backend only handles: token generation, tool execution, telemetry  
This eliminates ~80KB/s of audio relay per call through Phoenix.

### Local Cache Strategy

Room DB stores a subset of the restaurant KG for low-latency reads during calls:
- Full sync on pairing (`GET /config`)
- Delta sync via persistent WebSocket (`config.sync` messages)
- Tool calls that WRITE (reservations) always go to backend — no local writes

## API Contract

Base URL: `https://api.saathi.help/api/v1/saathi`  
Auth: `Authorization: Bearer <device_token>` on all endpoints except pair/verify  
Full spec: `aegis-api-phoenix/docs/api/SAATHI_DEVICE_API.md`

### Key Endpoints

| Method | Path | Purpose |
|---|---|---|
| POST | `/devices/pair` | Start OTP pairing (no auth) |
| POST | `/devices/verify` | Complete pairing, get device_token (no auth) |
| POST | `/devices/refresh` | Refresh expiring device_token |
| GET | `/config` | Full config sync (menu, hours, reservations) |
| POST | `/session` | Start voice session, get ephemeral Gemini token |
| POST | `/session/:id/tool` | Execute tool call during active session |
| POST | `/session/:id/end` | Report call end + telemetry |

### WebSocket

`wss://api.saathi.help/ws/device?token=<device_token>`  
Messages: heartbeat, call.inbound, call.end (client→server) | heartbeat_ack, config.sync, call.outbound, device.revoke (server→client)

## Audio Format

| Direction | Sample Rate | Format | Encoding for WS |
|---|---|---|---|
| Mic → Gemini | 16kHz | PCM_16BIT mono | Base64-encoded JSON |
| Gemini → Speaker | 24kHz | PCM_16BIT mono | Base64-decoded bytes |

**Critical:** Audio to Gemini is NOT raw binary frames. Must be base64-encoded and wrapped:
```json
{"realtimeInput":{"mediaChunks":[{"mimeType":"audio/pcm;rate=16000","data":"<base64>"}]}}
```

## Security

- Device token stored in `EncryptedSharedPreferences` backed by Android Keystore
- Token: JWT with `org_id`, `device_id`, `role: saathi_device`, 90-day expiry
- Session token (`X-Session-Token`): separate short-lived token per call (15 min)
- Gemini ephemeral token: single-use, 5-min expiry, never stored
- Certificate pinning: OkHttp `CertificatePinner` on `api.saathi.help`
- No API keys in source — base URL via `BuildConfig.AEGIS_API_BASE_URL`

## Multi-Tenancy

Every device is bound to exactly one `org_id` (extracted from device_token). The backend enforces all org-scoping — the app cannot access another restaurant's data. One device per org (V1).

## Background Survival

| Strategy | Implementation |
|---|---|
| Foreground service | `SaathiForegroundService` — persistent notification, type `phoneCall\|microphone` |
| Boot auto-start | `BootReceiver` — starts foreground service on `BOOT_COMPLETED` |
| Battery optimization | Guided per-OEM setup flow (Xiaomi/Samsung/Realme/Vivo) |
| FCM fallback | Backend sends FCM push if WebSocket heartbeat missed >60s |

## Development Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Install on connected device
./gradlew installDebug

# Check for lint issues
./gradlew lint

# Run all checks
./gradlew check
```

## Environment

Create `local.properties` (not committed):
```
AEGIS_API_BASE_URL=https://api.saathi.help
# For local dev against Phoenix on Mac:
# AEGIS_API_BASE_URL=http://10.0.2.2:4000   (emulator)
# AEGIS_API_BASE_URL=http://192.168.1.x:4000  (physical device, same WiFi)
```

## Key Conventions

- **Coroutines everywhere** — no RxJava, no callbacks where avoidable
- **Repository pattern** — UI never touches network or DB directly
- **StateFlow for UI state** — `MutableStateFlow` in ViewModel, collected in Compose
- **Hilt for DI** — no manual dependency passing
- **No hardcoded strings** — all user-visible text in `strings.xml` (Hindi + English)
- **Prices in paise** — server sends integers (28000 = ₹280.00), format on display

## Spike Branch

Current work: `spike/week1-call-interception`  
Goal: prove ConnectionService intercepts real call + bidirectional audio echo works

## Related Files (Backend)

| File | Purpose |
|---|---|
| `apps/aegis_platform/lib/aegis/voice/ephemeral_token.ex` | How Gemini tokens are generated |
| `apps/aegis_company/lib/aegis/meetings/hospitality_tool_executor.ex` | All tool implementations |
| `apps/aegis_company/lib/aegis/meetings/voice_agent_config.ex` | System instruction builder |
| `docs/decisions/ADR-070-saathi-android-app-architecture.md` | Architecture decisions |
| `docs/api/SAATHI_DEVICE_API.md` | Full API contract |
