# 06 — Implementation Plan

## Principle

Build bottom-up: infrastructure first → then services → then integration.
Each phase ends in a working state that can be launched and verified.

---

## Phase 0 — Infrastructure (start here)

```
Goal: docker-compose brings up PostgreSQL + Redis, services can connect to them

Tasks:
  ├── [ ] infra/docker-compose.yml — PostgreSQL 16, Redis 7
  ├── [ ] infra/postgres/init.sql  — CREATE TABLE users, jobs, subtitles, subtitle_entries
  ├── [ ] infra/redis/redis.conf   — requirepass, bind
  ├── [ ] Shared volume sublio_data
  └── [ ] .env.example — variables without values (in repo), .env — real values (in .gitignore)

Verification:
  docker-compose up postgres redis
  psql -h localhost -U sublio → \dt (tables visible)
  redis-cli AUTH password PING → PONG
```

---

## Phase 1 — transcription-worker (Python)

```
Goal: worker picks task from Redis, runs Whisper, writes kanji.srt

Why first: the longest part of the pipeline, needs to be debugged separately

Tasks:
  ├── [ ] transcription-worker/worker.py
  │         Redis BLPOP → parse job → transcribe → PUBLISH result
  ├── [ ] transcription-worker/transcribe.py
  │         Port from jimakutsukeru + improve
  ├── [ ] transcription-worker/Dockerfile
  ├── [ ] transcription-worker/requirements.txt
  └── [ ] Manual test:
            redis-cli LPUSH sublio:job:queue '{"jobId":"test","filePath":"/data/test.mp4"}'
            → wait for kanji.srt in /data/processed/test/subtitles/

Phase dependencies: Phase 0 (Redis running)
```

---

## Phase 2 — subtitle-service (Kotlin)

```
Goal: accepts path to kanji.srt, returns [{start,end,kanji,hiragana}]

Why second: independent of other services (only PostgreSQL)

Tasks:
  ├── [ ] Create Spring Boot project (Kotlin)
  ├── [ ] SrtParser.kt          ← parse .srt into List<SrtEntry>
  ├── [ ] KuromojService.kt     ← Kuromoji tokenizer → hiragana
  ├── [ ] SubtitleService.kt    ← orchestration
  ├── [ ] SubtitleController.kt ← POST /process, GET /subtitles/:id
  ├── [ ] SubtitleRepository.kt ← JPA entities + Spring Data
  ├── [ ] application.yaml      ← DB, port 8084
  └── [ ] Manual test:
            curl -X POST localhost:8084/process \
              -d '{"jobId":"test","srtPath":"/data/.../kanji.srt"}'
            → {"subtitleId":"uuid"}

Phase dependencies: Phase 0 (PostgreSQL), Phase 1 (kanji.srt file for test)
```

---

## Phase 3 — auth-service (Go)

```
Goal: /register + /login → JWT

Tasks:
  ├── [ ] Generate RSA key pair (private.pem, public.pem)
  │         openssl genrsa -out private.pem 2048
  │         openssl rsa -in private.pem -pubout -out public.pem
  ├── [ ] auth-service/cmd/auth/main.go
  ├── [ ] handler/register.go  ← bcrypt hash, INSERT users
  ├── [ ] handler/login.go     ← verify hash, issue JWT + refresh
  ├── [ ] jwt/token.go         ← Sign RS256, Validate
  ├── [ ] repository/user_repo.go ← pgx queries
  ├── [ ] go.mod
  └── [ ] Manual test:
            curl -X POST localhost:8081/register -d '{"email":"test@t.com","password":"pass"}'
            curl -X POST localhost:8081/login -d '{"email":"test@t.com","password":"pass"}'
            → {"accessToken":"eyJ..."}

Phase dependencies: Phase 0 (PostgreSQL)
```

---

## Phase 4 — sublio-media-service (Go)

```
Goal: accepts video, saves it, publishes task to Redis

Current state: skeleton exists (cmd/sublio/main.go — empty)

Tasks:
  ├── [ ] Rewrite main.go — chi router, middleware
  ├── [ ] handler/upload.go   ← read multipart, UUID folder, save
  ├── [ ] handler/stream.go   ← HTTP 206 Range requests
  ├── [ ] storage/local.go    ← work with /data/ volume
  ├── [ ] queue/redis.go      ← LPUSH sublio:job:queue
  ├── [ ] security/validator.go ← magic bytes
  └── [ ] Manual test:
            curl -X POST localhost:8082/upload?jobId=xxx -F video=@test.mp4
            redis-cli LLEN sublio:job:queue → 1

Phase dependencies: Phase 0 (Redis), Phase 1 (test that worker picks it up)
```

---

## Phase 5 — job-service (Go)

```
Goal: task creation, SSE progress, subtitle-service orchestration

Most complex service in terms of logic

Tasks:
  ├── [ ] handler/job.go        ← POST /jobs, GET /jobs/:id
  ├── [ ] handler/sse.go        ← GET /jobs/:id/progress (SSE goroutines)
  ├── [ ] queue/redis_sub.go    ← Subscribe to job:id:progress
  ├── [ ] orchestrator/orchestrator.go ← listens to Redis, calls subtitle-service
  ├── [ ] repository/job_repo.go ← pgx
  └── [ ] Integration test:
            1. POST /jobs → jobId
            2. curl SSE /jobs/:id/progress (in separate terminal)
            3. redis-cli LPUSH sublio:job:queue {...jobId...}
            4. Watch events in SSE stream

Phase dependencies: Phase 0, Phase 1, Phase 2 (subtitle-service)
```

---

## Phase 6 — api-gateway (Go)

```
Goal: single entry point, JWT validation, rate limiting

Tasks:
  ├── [ ] middleware/auth.go      ← JWT RS256 (public.pem from auth-service)
  ├── [ ] middleware/ratelimit.go ← chi rate limit
  ├── [ ] middleware/cors.go
  ├── [ ] proxy/router.go        ← httputil.ReverseProxy for each service
  ├── [ ] config/config.yaml     ← upstream addresses, rate limits
  └── [ ] TLS (for prod: Let's Encrypt or self-signed for dev)

Phase dependencies: all previous services running

E2E test through gateway:
  1. POST /api/auth/login       → JWT
  2. POST /api/jobs (with JWT)  → jobId
  3. GET  /api/jobs/:id/progress → SSE
  4. POST /api/upload?jobId=xxx → 202
  5. Wait for SSE "done"
  6. GET  /api/subtitles/:id    → subtitles
```

---

## Phase 7 — Frontend (React/TypeScript)

```
Goal: full-featured UI

Tasks:
  ├── [ ] Init: npm create vite@latest sublio-web -- --template react-ts
  ├── [ ] UploadForm.tsx   ← drag-and-drop, progress bar
  ├── [ ] VideoPlayer.tsx  ← HTML5 video + Range
  ├── [ ] SubtitleOverlay.tsx ← kanji + hiragana over video
  ├── [ ] useJobProgress.ts ← SSE hook
  ├── [ ] api.ts           ← axios + Bearer JWT
  └── [ ] Vite proxy setup for dev:
            "/api" → localhost:8080 (gateway)

Phase dependencies: Phase 6 (gateway running)
```

---

## Phase dependency diagram

```
Phase 0 (infra)
   │
   ├──────────────┬──────────────┐
   │              │              │
   ▼              ▼              ▼
Phase 1        Phase 2        Phase 3
(python        (subtitle-     (auth-
 worker)        service)       service)
   │              │              │
   └──────┬───────┘              │
          ▼                      │
       Phase 4                   │
       (media-                   │
        service)                 │
          │                      │
          └──────┬───────────────┘
                 ▼
              Phase 5
              (job-
               service)
                 │
                 ▼
              Phase 6
              (gateway)
                 │
                 ▼
              Phase 7
              (frontend)
```

---

## What to reuse from jimakutsukeru

```
File in jimakutsukeru                   → Where in Sublio
────────────────────────────────────────────────────────────────────────
python/faster_whisper_transcribe.py     → transcription-worker/transcribe.py
python/convert_to_hiragana.py           → REPLACED by KuromojService.kt
                                          (Kuromoji is more accurate than Python converter)
service/SubtitleService.kt (parseSrt)   → subtitle-service/SrtParser.kt (adapt)
dto/SubtitleDto.kt                      → subtitle-service (analogous)
```
