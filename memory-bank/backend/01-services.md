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
- JWT verification uses Keycloak's JWKS endpoint (fetched + cached at startup,
  refreshed on `kid` cache miss) — NOT a per-request call to Keycloak or
  auth-service. See backend/10-identity-provider.md.

---

## 2. auth-service  [Go]  — thin BFF over Keycloak

**Role:** adapts Keycloak's OIDC API to the simple `{email, password}` contract the
frontend already expects. See backend/10-identity-provider.md for the full design
and why this shape was chosen over redirecting the frontend to Keycloak directly.

```
API — matches the ACTUAL frontend implementation (sublio-web/src/services/authService.ts
and api.ts), not the earlier draft that assumed a JSON refreshToken:
  POST /register  { email, password }  →  201 { user: {id, email}, accessToken }
                                            + Set-Cookie: refreshToken=...; HttpOnly; Secure; SameSite=Strict
  POST /login     { email, password }  →  200 { user, accessToken } + same Set-Cookie
  POST /refresh   (empty body — refresh token comes from the HttpOnly cookie,
                    sent automatically by the browser via withCredentials)
                                        →  200 { user, accessToken } + REFRESHED Set-Cookie
  POST /logout    (empty body, cookie-authenticated)
                                        →  204, clears the cookie + revokes the
                                            session at Keycloak

The refresh token NEVER appears in a JSON response body — only as an HttpOnly
cookie the browser can't read via JS. This is more secure than the original
draft (JSON-exposed refresh tokens are readable by any XSS payload); the
frontend was already built this way, so the backend design was corrected to
match it, not the other way around.

Behind the API:
  POST /register  → Keycloak Admin REST API creates the user, THEN an
                     immediate ROPC token request logs them in — register
                     auto-logs-in, it does not just create an inert account
  POST /login      → Keycloak token endpoint: grant_type=password (ROPC)
  POST /refresh    → Keycloak token endpoint: grant_type=refresh_token — Keycloak
                     rotates the refresh token on use, so the cookie MUST be
                     re-set with the new value on every refresh, not just once
  POST /logout     → Keycloak's logout/revoke endpoint, invalidating the
                     session server-side, then clears the cookie

Storage: NONE — auth-service holds no database, no user table, no session.
  It is a pure protocol adapter; Keycloak is the only source of truth for identity.

JWT (issued and signed by Keycloak, not by this service):
  ├── algorithm: RS256, key managed and rotated by Keycloak internally
  ├── accessToken TTL: 15 minutes (realm setting)
  ├── refreshToken TTL: 30 days (realm setting)
  └── payload: { sub: keycloakUserId, email, iat, exp, ... } — verified via JWKS
        at api-gateway, see backend/10-identity-provider.md
```

**Security:**
- Password storage, hashing, and brute-force detection are entirely Keycloak's
  responsibility (Keycloak has built-in brute-force protection per realm) —
  auth-service never sees a password hash, only the plaintext password for the
  single call it proxies to Keycloak's token endpoint over the internal network.
- auth-service authenticates to Keycloak's Admin API using a confidential client
  (client_id + client_secret, the secret pulled from Vault — see backend/09-secrets-management.md).
- **Trade-off, stated plainly:** using the Resource Owner Password Credentials
  (ROPC) grant here keeps the frontend's existing `/api/auth/login` contract
  intact (email+password in one call), but ROPC is a discouraged OAuth2 grant
  (removed in OAuth 2.1) because the client handles the raw password. The more
  correct long-term shape is Authorization Code + PKCE with the frontend
  redirecting to Keycloak's login page — documented here as the recommended
  upgrade, not implemented now, since it requires frontend changes outside the
  current scope (see memory-bank/frontend docs — frontend is already built
  against the simpler contract).

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
