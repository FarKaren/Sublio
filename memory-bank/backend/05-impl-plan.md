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

## Phase 0.5 — Tooling: Nexus, OpenAPI contracts, Observability skeleton

```
Goal: the cross-cutting infra exists BEFORE the first service is written, so
every service from Phase 1 onward is built spec-first and instrumented from
day one instead of retrofitted later.

Why here, not later: retrofitting OpenAPI contracts or OTEL onto 6 already-written
services is exactly the kind of tedious, error-prone rework a techlead avoids by
sequencing the work correctly.

Tasks:
  ├── [ ] infra/nexus/ — sonatype/nexus3 container (Compose profile "tooling")
  │         configure Docker registry repo + Go module proxy + Gradle/PyPI proxy
  ├── [ ] api/ — create the 4 OpenAPI 3.1 spec files (empty paths OK for now,
  │         filled in per-service in later phases) — see backend/07-api-contracts.md
  ├── [ ] infra/observability/ — alloy + tempo + prometheus + grafana
  │         (Compose profile "observability"), empty pipeline, nothing sending yet
  └── [ ] Verification:
            docker-compose --profile observability up
            → Grafana reachable at :3000, Prometheus + Tempo datasources provisioned
            docker-compose --profile tooling up
            → Nexus UI reachable at :8081

Phase dependencies: Phase 0 (Docker network + volumes exist)
```

---

## Phase 1 — transcription-worker (Python)

```
Goal: worker picks task from Redis, runs Whisper, writes kanji.srt

Why first: the longest part of the pipeline, needs to be debugged separately

Tasks:
  ├── [ ] OTEL SDK init (opentelemetry-sdk, OTLP exporter to alloy:4317) +
  │         pybreaker around Redis calls — see backend/06-observability.md, 08-scalability.md
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
  ├── [ ] api/subtitle-service.yaml — write the OpenAPI spec FIRST
  │         (POST /process, GET/DELETE /subtitles/:id) — see backend/07-api-contracts.md
  ├── [ ] Create Spring Boot project (Kotlin) + openapi-generator plugin wired
  │         to api/subtitle-service.yaml
  ├── [ ] Actuator + micrometer-tracing-bridge-otel + resilience4j deps
  │         (06-observability.md, 08-scalability.md)
  ├── [ ] SrtParser.kt          ← parse .srt into List<SrtEntry>
  ├── [ ] KuromojService.kt     ← Kuromoji tokenizer → hiragana
  ├── [ ] SubtitleService.kt    ← orchestration
  ├── [ ] SubtitleController.kt ← implements generated SubtitleServiceApi interface
  ├── [ ] SubtitleRepository.kt ← JPA entities + Spring Data
  ├── [ ] application.yaml      ← DB, port 8084, OTLP endpoint
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
  ├── [ ] api/auth-service.yaml — write the OpenAPI spec first, run oapi-codegen
  │         — see backend/07-api-contracts.md
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
  ├── [ ] api/media-service.yaml — write the OpenAPI spec first, run oapi-codegen
  │         (idempotency key on POST /upload — see backend/08-scalability.md)
  ├── [ ] Rewrite main.go — chi router, middleware, OTEL init (otelchi + otelhttp)
  ├── [ ] handler/upload.go   ← implements generated interface; read multipart,
  │         UUID folder, save, idempotency-key dedupe
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

Most complex service in terms of logic. Also the FIRST service to get full
observability wired end-to-end (see 06-observability.md rollout order) — it's
the hub, so it's the highest-value place to prove the OTEL pipeline works
before copying the pattern everywhere else.

Tasks:
  ├── [ ] api/job-service.yaml — write the OpenAPI spec first (idempotency key
  │         on POST /jobs — see backend/08-scalability.md), run oapi-codegen
  ├── [ ] OTEL init + otelchi middleware + gobreaker around subtitle-service
  │         calls and Postgres/Redis — see 06-observability.md, 08-scalability.md
  ├── [ ] handler/job.go        ← implements generated interface; POST /jobs
  │         (idempotency key check), GET /jobs/:id
  ├── [ ] handler/sse.go        ← GET /jobs/:id/progress (SSE goroutines,
  │         sublio_sse_connections_active gauge)
  ├── [ ] queue/redis_sub.go    ← Subscribe to job:id:progress
  ├── [ ] orchestrator/orchestrator.go ← listens to Redis, calls subtitle-service
  │         (span link across the async Redis boundary — see 06-observability.md)
  ├── [ ] repository/job_repo.go ← pgx, pgxpool with bounded size
  ├── [ ] Verify in Grafana: a `POST /jobs` → SSE → DONE round trip produces
  │         one connected trace in Tempo and shows up in the RED dashboard
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
  ├── [ ] proxy/router.go        ← httputil.ReverseProxy for each service,
  │         propagates traceparent header (trace continues through the proxy hop)
  ├── [ ] handler/docs.go        ← serves api/*.yaml as JSON + Swagger UI at
  │         /api/docs — see backend/07-api-contracts.md
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

## Phase 7 — Frontend (React/TypeScript) — DONE

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

## Phase 8 — Load testing & scalability verification

```
Goal: turn the patterns from backend/08-scalability.md from "designed" into
"verified" — this is the phase that actually justifies the high-load framing.

Tasks:
  ├── [ ] infra/loadtest/upload_flow.js (k6) — full user journey:
  │         login → create job → upload → SSE to DONE
  ├── [ ] infra/loadtest/smoke.js (k6) — fast CI sanity check
  ├── [ ] docker-compose: run job-service with 2 replicas + gateway load
  │         balancing between them
  ├── [ ] Run upload_flow.js against 2 replicas, watch Grafana RED dashboards
  │         (per-instance split visible via OTEL resource attributes)
  ├── [ ] Kill one job-service replica mid-run → confirm in-flight SSE clients
  │         reconnect and no job is lost or duplicated (idempotency key holds)
  └── [ ] Write up findings: p95/p99 under load, pgxpool saturation point,
            Redis queue depth behavior — this writeup IS the artifact that
            demonstrates the high-load work, since real traffic never reaches
            these numbers otherwise

Phase dependencies: Phase 6 (full system running end-to-end), Phase 0.5
(observability stack, to actually see the results)
```

---

## Phase dependency diagram

```
Phase 0 (infra)
   │
   ▼
Phase 0.5 (Nexus + OpenAPI contracts + observability skeleton)
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
              ┌──┴───┐
              ▼      ▼
         Phase 7   Phase 8
        (frontend, (load test /
          DONE)     scale verify)
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
