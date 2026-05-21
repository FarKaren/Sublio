# 02 — Microservice Descriptions

## 1. api-gateway  [Go]

**Role:** single entry point. No external request goes directly to the services.

```
Responsibilities:
  ├── TLS termination (HTTPS)
  ├── JWT validation (RS256) → if token is invalid — 401, request never reaches the service
  ├── Rate limiting (per IP, per userId)
  ├── CORS (only the frontend domain is allowed)
  └── Reverse proxy → routing by path prefix

JWT exceptions:
  ├── POST /api/auth/register    (open)
  └── POST /api/auth/login       (open)

External port: 443 (HTTPS)
Internal port: 8080
```

**Key decisions:**
- Gateway does NOT contain business logic
- Gateway does NOT access PostgreSQL or Redis directly
- JWT public key (RS256) is loaded from a file at startup — not from auth-service on each request

---

## 2. auth-service  [Go]

**Role:** user management and JWT token issuance.

```
API:
  POST /register          { email, password }  →  201 { userId }
  POST /login             { email, password }  →  200 { accessToken, refreshToken }
  POST /refresh           { refreshToken }     →  200 { accessToken }

Storage:
  PostgreSQL
    ├── table users: id, email, password_hash (bcrypt), created_at
    └── table refresh_tokens: id, user_id, token_hash, expires_at, revoked

JWT:
  ├── algorithm: RS256 (asymmetric)
  ├── accessToken TTL: 15 minutes
  ├── refreshToken TTL: 30 days
  └── payload: { sub: userId, email, iat, exp }
```

**Security:**
- Passwords stored as bcrypt hash (cost=12)
- RefreshToken stored as SHA-256 hash (not the token itself)
- On compromise: revoke via refresh_tokens table

---

## 3. sublio-media-service  [Go]  ← already created

**Role:** video upload from user, video streaming to browser.

```
API:
  POST /upload?jobId=xxx     multipart/form-data, field "video"
    → saves file → publishes task to Redis → 202 Accepted

  GET  /video/:folderId/:fileName
    → HTTP 206 Partial Content (Range requests for video player)

Storage:
  Shared Docker Volume /data/
    └── processed/
        └── {uuid}/
            ├── video.mp4           ← original video (UUID path, not original name)
            └── subtitles/          ← populated by transcription-worker

Publish to Redis:
  LPUSH sublio:job:queue  '{"jobId":"...", "filePath":"...", "userId":"..."}'
```

**Streaming:**
- Range request support (Accept-Ranges: bytes)
- HTTP 206 Partial Content — browser can seek video without full download

**File validation:**
```
magic bytes check:
  MP4:  starts with ftyp box (bytes 4-7: "ftyp")
  MKV:  0x1A 0x45 0xDF 0xA3
  AVI:  "RIFF"..."AVI "

Forbidden:
  ├── files > 2GB
  ├── executable formats (ELF, PE, Mach-O)
  └── original filename is NOT saved (path traversal protection)
```

---

## 4. job-service  [Go]

**Role:** orchestration of the entire processing pipeline. Tracks job status, streams progress via SSE.

```
API:
  POST /jobs                    → 201 { jobId }   ← create job
  GET  /jobs/:id                → { status, message, result? }
  GET  /jobs/:id/progress       → SSE stream (text/event-stream)
  DELETE /jobs/:id              ← cancel job

Job statuses:
  CREATED → QUEUED → TRANSCRIBING → PROCESSING_SUBTITLES → DONE
                                                          → ERROR

Storage:
  PostgreSQL
    └── table jobs:
          id, user_id, status, message, file_path,
          result_video_url, result_subtitle_id,
          created_at, updated_at

Redis:
  ├── SUBSCRIBE sublio:job:{jobId}:progress  ← listens for updates
  └── SET sublio:job:{jobId}:status  (cache for fast polling)
```

**SSE mechanism (goroutines):**
```
GET /jobs/:id/progress
  ├── creates SseEmitter (Go channel)
  ├── starts goroutine: Subscribe to Redis channel job:id:progress
  ├── on message received → writes to channel → sends to client
  └── on disconnect/timeout → goroutine terminates (no leak)

Event format:
  event: progress
  data: {"status":"TRANSCRIBING","message":"Transcribing Japanese audio..."}

  event: done
  data: {"videoUrl":"/api/video/...","subtitleId":"uuid"}

  event: error
  data: {"message":"Transcription failed"}
```

**Orchestration (orchestrator.go):**
```
1. Receives "transcribed" event from Redis (from Python Worker)
2. Calls POST subtitle-service:8084/process { jobId, srtPath }
3. Subtitle service processes → returns subtitleId
4. Updates job in PostgreSQL (status=DONE, subtitleId)
5. Publishes to Redis: PUBLISH job:id:progress { status: DONE, ... }
```

---

## 5. subtitle-service  [Kotlin / Spring Boot]

**Role:** kanji → hiragana conversion via Kuromoji, subtitle storage.

```
API:
  POST /process         { jobId, srtPath }  →  { subtitleId }
  GET  /subtitles/:id   →  [ { start, end, kanji, hiragana } ]
  DELETE /subtitles/:id

Dependencies (build.gradle.kts):
  ├── spring-boot-starter-web
  ├── com.atilika.kuromoji:kuromoji-ipadic:0.9.0   ← kanji→hiragana
  ├── spring-data-jpa
  ├── postgresql driver
  └── jackson-module-kotlin

PostgreSQL storage:
  table subtitles:
    id, job_id, created_at

  table subtitle_entries:
    id, subtitle_id, seq_num,
    start_time, end_time,
    kanji_text, hiragana_text
```

**Processing pipeline (SubtitleService.kt):**
```
POST /process received
  │
  ├── reads kanji.srt from shared volume by srtPath
  ├── parses SRT → List<SrtEntry>(start, end, text)
  ├── for each entry:
  │     KuromojService.toHiragana(kanjiText)
  │       → Tokenizer.tokenize(text)
  │       → tokens.joinToString("") { it.reading }  ← hiragana
  ├── saves everything to PostgreSQL (subtitles + subtitle_entries)
  └── returns { subtitleId }
```

**Port:** 8084 (only inside Docker network)

---

## 6. transcription-worker  [Python]

**Role:** Faster-Whisper ML inference. Fully isolated — no outbound HTTP.

```
Startup:
  worker.py — infinite loop:
    ├── Redis BLPOP sublio:job:queue (blocks until task appears)
    ├── Parses { jobId, filePath, userId }
    ├── Redis PUBLISH job:jobId:progress { status: TRANSCRIBING }
    ├── transcribe.py → Faster-Whisper → kanji.srt
    ├── Redis PUBLISH job:jobId:progress { status: TRANSCRIBED, srtPath }
    └── Repeats loop

transcribe.py:
  └── Same logic as in jimakutsukeru (already debugged)
      ├── WhisperModel("large-v3", device="cpu", compute_type="int8")
      ├── vad_filter=True (removes silence)
      ├── condition_on_previous_text=False (anti-hallucination)
      └── no_speech_threshold=0.6
```

**Important:**
- Worker reads video from `/data/processed/{uuid}/video.mp4` (shared volume)
- Writes `kanji.srt` to `/data/processed/{uuid}/subtitles/`
- Has no HTTP port — only Redis connection
- Started with `--cpus=2` `--memory=4g` in docker-compose

---

## 7. sublio-web  [React / TypeScript]

**Role:** SPA frontend.

```
Components:
  ├── UploadForm.tsx         ← drag-and-drop, shows progress
  ├── VideoPlayer.tsx        ← HTML5 video with Range support
  ├── SubtitleOverlay.tsx    ← renders kanji + hiragana over video
  └── VideoLibrary.tsx       ← list of uploaded videos

Hooks:
  └── useJobProgress.ts      ← SSE: new EventSource('/api/jobs/:id/progress')

Services:
  └── api.ts                 ← axios, all requests with Bearer JWT token

Build:
  ├── Vite (replaces CRA)
  └── Nginx serves build/ in prod
```
