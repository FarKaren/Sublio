# 03 — Data Flows and Interactions

## Main flow: video upload and processing

```
CLIENT             GATEWAY          AUTH        MEDIA        JOB         SUBTITLE      PYTHON       REDIS       POSTGRES
   │                  │              │            │            │            │            │              │            │
   │                  │              │            │            │            │            │              │            │
   │── POST /login ──►│              │            │            │            │            │              │            │
   │                  │── forward ──►│            │            │            │            │              │            │
   │                  │              │── grant_type=password ──────────────────────────────────────────────────────►│ KEYCLOAK
   │                  │              │            (Keycloak verifies password + issues JWT — see backend/10-identity-provider.md;
   │                  │              │◄── {access_token, refresh_token} ───────────────────────────────────────────│
   │                  │              │            │            │            │            │              │            │
   │                  │              │            │            │            │            │              │            │
   │◄─ {user, accessToken} + Set-Cookie: refreshToken (HttpOnly, Secure) ────│            │            │              │            │
   │   (refresh token NEVER appears in the JSON body — see backend/10-identity-provider.md)
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEPS 2-3: Create job                                                                                       ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │── POST /jobs ────►│ (JWT OK)                  │            │            │            │              │            │
   │                  │──────────────────────────────────────►│            │            │              │            │
   │                  │                           │            │── INSERT ──────────────────────────────────────►│
   │                  │                           │            │◄── jobId ──────────────────────────────────────-│
   │◄─ { jobId } ─────────────────────────────────────────────│            │            │              │            │
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEP 4: Subscribe to SSE progress (open early to avoid missing events)                                      ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │── GET /jobs/:id/progress (SSE) ──────────────────────────────────────►│            │              │            │
   │                  │ (JWT OK)                  │            │            │            │              │            │
   │◄════ SSE connection open ═══════════════════════════════════════════ │            │              │            │
   │  (kept open)                  │            │            │            │              │            │
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEP 5: Upload video                                                                                        ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │── POST /upload?jobId=xxx ────►│ (JWT OK)     │            │            │            │              │            │
   │   multipart video             │──────────────────────────►│            │            │              │            │
   │                  │            │              │            │            │            │              │            │
   │                  │            │              │── validate magic bytes  │            │              │            │
   │                  │            │              │── save to /data/{uuid}/│            │              │            │
   │                  │            │              │                        │            │              │            │
   │                  │            │              │── LPUSH job:queue ───────────────────────────────►│            │
   │                  │            │              │   {jobId, filePath}    │            │              │            │
   │◄─ 202 Accepted ─────────────────────────────│            │            │            │              │            │
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEP 6: Transcription (async, Python Worker)                                                                ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │            │            │◄─ BLPOP ───-─│            │
   │                  │                           │            │            │            │   {jobId..}  │            │
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │            │            │── PUBLISH ──►│ job:id:progress
   │                  │                           │            │            │            │  TRANSCRIBING│            │
   │                  │                           │            │            │            │              │            │
   │◄══ SSE: {status: TRANSCRIBING} ═══════════════════════════════════════│◄─ SUB ─────│            │
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │            │            │── Faster-Whisper         │
   │                  │                           │            │            │            │   (several minutes)      │
   │                  │                           │            │            │            │── writes kanji.srt       │
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │            │            │── PUBLISH ──►│ job:id:progress
   │                  │                           │            │            │            │  TRANSCRIBED │            │
   │                  │                           │            │            │            │  srtPath     │            │
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEP 7: Job Service orchestrates subtitle processing                                                        ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │◄─ SUB TRANSCRIBED ──────────────────-─│            │
   │                  │                           │            │            │            │              │            │
   │                  │                           │            │── POST /process ────────►│            │            │
   │                  │                           │            │   {jobId, srtPath}       │            │            │
   │                  │                           │            │                          │── Kuromoji │            │
   │                  │                           │            │                          │── INSERT ──────────────►│
   │                  │                           │            │◄─ { subtitleId } ────────│            │            │
   │                  │                           │            │── UPDATE job=DONE ──────────────────────────────►│
   │                  │                           │            │── PUBLISH ────────────────────────────►│           │
   │                  │                           │            │   DONE, subtitleId       │            │            │
   │◄══ SSE: {status: DONE, subtitleId} ══════════════════════│            │            │              │            │
   │   SSE connection closes                     │            │            │            │              │            │
   │                  │                           │            │            │            │              │            │
   ╔══════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
   ║  STEP 8: User watches video with subtitles                                                                   ║
   ╚══════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
   │                  │                           │            │            │            │              │            │
   │── GET /video/:folderId/:file ─────────────────────────►│            │            │              │            │
   │◄════ HTTP 206 video stream ════════════════════════════ │            │            │              │            │
   │                  │                           │            │            │            │              │            │
   │── GET /subtitles/:id ────────────────────────────────────────────────────────────►│            │            │
   │◄─ [{start,end,kanji,hiragana}] ──────────────────────────────────────────────────│            │            │
```

---

## Redis channels and keys schema

```
Redis
│
├── Task queue (LIST)
│   └── sublio:job:queue
│         ← LPUSH from media-service
│         → BLPOP from transcription-worker
│
├── Progress pub/sub (CHANNEL)
│   └── sublio:job:{jobId}:progress
│         ← PUBLISH from: transcription-worker, job-service
│         → SUBSCRIBE from: job-service (orchestrator)
│         → SSE → client
│
└── Status cache (STRING, TTL 24h)
    └── sublio:job:{jobId}:status
          SET by job-service on each update
          GET by GET /jobs/:id (fast polling without PostgreSQL)
```

---

## PostgreSQL tables schema

**No `users` or `refresh_tokens` tables.** Keycloak is the only source of
identity truth (see backend/10-identity-provider.md) — it has its own database
(`keycloak`, separate from `sublio`) that this project never queries directly.
`jobs.user_id` stores Keycloak's `sub` claim (a UUID Keycloak assigns), with no
local FK — the referenced entity lives in an external system.

```
┌─────────────────────────────────────────────────┐
│ jobs                                            │
├─────────────────────────────────────────────────┤
│ id            UUID PK                           │
│ user_id       UUID  ← Keycloak "sub" claim,     │
│                        NOT a local FK           │
│ status        VARCHAR(50)  ← enum              │
│ message       TEXT                             │
│ file_path     TEXT                             │
│ subtitle_id   UUID NULLABLE                    │
│ created_at    TIMESTAMP                         │
│ updated_at    TIMESTAMP                         │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ subtitles                                       │
├─────────────────────────────────────────────────┤
│ id            UUID PK                           │
│ job_id        UUID FK → jobs.id                │
│ created_at    TIMESTAMP                         │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│ subtitle_entries                                │
├─────────────────────────────────────────────────┤
│ id            BIGSERIAL PK                      │
│ subtitle_id   UUID FK → subtitles.id           │
│ seq_num       INT                               │
│ start_time    VARCHAR(20)  ← "00:01:23,456"    │
│ end_time      VARCHAR(20)                       │
│ kanji_text    TEXT                             │
│ hiragana_text TEXT                             │
└─────────────────────────────────────────────────┘
```

---

## Shared Volume structure

```
/data/                              ← Docker volume, mounted in:
│                                     media-service, transcription-worker,
│                                     subtitle-service (read-only)
│
└── processed/
    └── {uuid}/                     ← UUID generated by media-service
        ├── video.mp4               ← original video (renamed)
        └── subtitles/
            ├── kanji.srt           ← generated by transcription-worker
            └── hiragana.srt        ← generated by subtitle-service
                                       (for possible download)
```

---

## Frontend SSE lifecycle

```javascript
// useJobProgress.ts — simplified logic

1. Create job:          POST /api/jobs → { jobId }
2. Open SSE:            new EventSource(`/api/jobs/${jobId}/progress`)
3. Upload file:         POST /api/upload?jobId=xxx (FormData)

SSE events:
  "progress" → update UI (progress bar, message)
  "done"     → { subtitleId } → load subtitles, show player
  "error"    → show error message
  connection close → SSE closes automatically
```
