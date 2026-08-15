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
  ├── [ ] infra/postgres/init.sql  — CREATE DATABASE keycloak (separate from
  │         sublio); CREATE TABLE jobs, subtitles, subtitle_entries in sublio
  │         (no users/refresh_tokens tables — Keycloak owns identity, see
  │         backend/10-identity-provider.md)
  ├── [ ] infra/redis/redis.conf   — requirepass, bind
  ├── [ ] Shared volume sublio_data
  └── [ ] .env.example — variables without values (in repo), .env — real values (in .gitignore)

Verification:
  docker-compose up postgres redis
  psql -h localhost -U sublio → \dt (tables visible)
  redis-cli AUTH password PING → PONG
```

---

## Phase 0.5 — Tooling: Nexus, OpenAPI contracts, Observability skeleton, Vault, Keycloak

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
  ├── [ ] infra/vault/ — Vault server mode + file storage backend, NOT behind a
  │         profile (every later phase needs it to boot) — see backend/09-secrets-management.md
  │           ├── init + unseal (3-of-5 Shamir shares)
  │           ├── enable KV v2 + database secrets engine (no transit — Keycloak
  │           │     owns JWT signing, see backend/10-identity-provider.md)
  │           ├── write one least-privilege policy per service
  │           └── create one AppRole per service, revoke the root token after setup
  ├── [ ] infra/keycloak/ — Keycloak container + its own Postgres database
  │         (keycloak_role dynamic credential from Vault), NOT behind a profile
  │         (auth-service needs it to boot) — see backend/10-identity-provider.md
  │           ├── import realm-export.json ("sublio" realm, brute-force policy)
  │           └── create the "auth-service-bff" confidential client, put its
  │                 client_secret in Vault KV
  └── [ ] Verification:
            docker-compose --profile observability up
            → Grafana reachable at :3000, Prometheus + Tempo datasources provisioned
            docker-compose --profile tooling up
            → Nexus UI reachable at :8081
            docker-compose up vault && ./infra/vault/init.sh
            → vault status shows Sealed: false, vault secrets list shows both engines
            docker-compose up keycloak
            → realm "sublio" visible in the Keycloak admin console, JWKS endpoint
              (/realms/sublio/protocol/openid-connect/certs) returns a key set

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
  ├── [ ] Vault AppRole login (hvac) → fetch Redis password from KV v2 at boot
  │         — see backend/09-secrets-management.md
  ├── [ ] transcription-worker/worker.py
  │         Redis BLPOP → parse job → transcribe → PUBLISH result
  ├── [ ] transcription-worker/transcribe.py
  │         Port from jimakutsukeru + improve
  ├── [ ] transcription-worker/Dockerfile
  ├── [ ] transcription-worker/requirements.txt
  └── [ ] Manual test:
            redis-cli LPUSH sublio:job:queue '{"jobId":"test","filePath":"/data/test.mp4"}'
            → wait for kanji.srt in /data/processed/test/subtitles/

Phase dependencies: Phase 0 (Redis running), Phase 0.5 (Vault up + AppRole configured)
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
  ├── [ ] spring-vault-core → AppRole login → dynamic Postgres credential from
  │         Vault's database secrets engine (subtitle_role) — see backend/09-secrets-management.md
  ├── [ ] SrtParser.kt          ← parse .srt into List<SrtEntry>
  ├── [ ] KuromojService.kt     ← Kuromoji tokenizer → hiragana
  ├── [ ] SubtitleService.kt    ← orchestration
  ├── [ ] SubtitleController.kt ← implements generated SubtitleServiceApi interface
  ├── [ ] SubtitleRepository.kt ← JPA entities + Spring Data
  ├── [ ] application.yaml      ← DB, port 8084, OTLP endpoint
  ├── [ ] Dockerfile (JDK build stage + JRE runtime) + push to Nexus
  │         docker build -t localhost:8081/sublio/subtitle-service:dev .
  │         docker push localhost:8081/sublio/subtitle-service:dev
  └── [ ] Manual test:
            curl -X POST localhost:8084/process \
              -d '{"jobId":"test","srtPath":"/data/.../kanji.srt"}'
            → {"subtitleId":"uuid"}

Phase dependencies: Phase 0 (PostgreSQL), Phase 0.5 (Vault database secrets engine
configured for subtitle_role), Phase 1 (kanji.srt file for test)
```

---

## Phase 3 — auth-service (Go) — thin BFF over Keycloak

```
Goal: /register + /login → JWT, with Keycloak doing all the actual identity work.
See backend/10-identity-provider.md for the full design and the ROPC trade-off.

Tasks:
  ├── [ ] api/auth-service.yaml — write the OpenAPI spec first, run oapi-codegen
  │         — see backend/07-api-contracts.md (contract is UNCHANGED from the
  │         original design — this is what makes it a drop-in replacement for
  │         the frontend, which is already built against it)
  ├── [ ] Vault AppRole login → fetch the "auth-service-bff" client_secret
  │         from KV v2 at boot (the ONLY Vault secret this service needs —
  │         no database role, no transit) — see backend/09-secrets-management.md
  ├── [ ] auth-service/cmd/auth/main.go
  ├── [ ] keycloak/client.go   ← Admin API client (service-account token) +
  │         token endpoint client (ROPC + refresh grants) + logout call
  ├── [ ] handler/register.go  ← creates the user via Keycloak Admin API, THEN
  │         auto-logs-in (ROPC) — returns {user, accessToken}, matching /login
  ├── [ ] handler/login.go     ← calls Keycloak token endpoint (grant_type=password),
  │         sets refreshToken as an HttpOnly cookie (NEVER in the JSON body —
  │         see backend/10-identity-provider.md, this was corrected against the
  │         actual frontend code, not the original draft), returns {user, accessToken}
  ├── [ ] handler/refresh.go   ← reads refreshToken from the HttpOnly cookie
  │         (not a request body field), calls grant_type=refresh_token, and
  │         RE-SETS the cookie with Keycloak's rotated refresh token
  ├── [ ] handler/logout.go    ← reads the cookie, calls Keycloak's logout/revoke
  │         endpoint, clears the cookie (Max-Age=0) — endpoint the frontend
  │         actually calls, missing from the original design entirely
  ├── [ ] go.mod
  ├── [ ] Dockerfile + push to Nexus
  │         docker build -t localhost:8081/sublio/auth-service:dev .
  │         docker push localhost:8081/sublio/auth-service:dev
  └── [ ] Manual test:
            curl -c cookies.txt -X POST localhost:8081/register -d '{"email":"test@t.com","password":"pass"}'
            curl -b cookies.txt -X POST localhost:8081/refresh
            curl -b cookies.txt -X POST localhost:8081/logout
            → each returns {"user":{...},"accessToken":"eyJ..."} (register/refresh)
              or 204 (logout); refreshToken never appears in any response body,
              only in the Set-Cookie header

Phase dependencies: Phase 0.5 (Keycloak up with the "sublio" realm + client
imported, Vault KV holding the client_secret — see backend/10-identity-provider.md)
```

---

## Phase 4 — media-service (Go)

```
Goal: accepts video, saves it, publishes task to Redis

Current state: skeleton exists (cmd/sublio/main.go — empty)

Tasks:
  ├── [ ] api/media-service.yaml — write the OpenAPI spec first, run oapi-codegen
  │         (idempotency key on POST /upload — see backend/08-scalability.md)
  ├── [ ] Rewrite main.go — chi router, middleware, OTEL init (otelchi + otelhttp)
  ├── [ ] Vault AppRole login → fetch Redis password from KV v2 at boot
  │         — see backend/09-secrets-management.md
  ├── [ ] handler/upload.go   ← implements generated interface; read multipart,
  │         UUID folder, save, idempotency-key dedupe
  ├── [ ] handler/stream.go   ← HTTP 206 Range requests
  ├── [ ] storage/local.go    ← work with /data/ volume
  ├── [ ] queue/redis.go      ← LPUSH sublio:job:queue
  ├── [ ] security/validator.go ← magic bytes
  ├── [ ] Dockerfile + push to Nexus
  │         docker build -t localhost:8081/sublio/media-service:dev .
  │         docker push localhost:8081/sublio/media-service:dev
  └── [ ] Manual test:
            curl -X POST localhost:8082/upload?jobId=xxx -F video=@test.mp4
            redis-cli LLEN sublio:job:queue → 1

Phase dependencies: Phase 0 (Redis), Phase 0.5 (Vault KV secret for Redis
password), Phase 1 (test that worker picks it up)
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
  ├── [ ] Vault AppRole login → dynamic Postgres credential (job_role) +
  │         background lease-renewal goroutine — see backend/09-secrets-management.md
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
  ├── [ ] Dockerfile + push to Nexus
  │         docker build -t localhost:8081/sublio/job-service:dev .
  │         docker push localhost:8081/sublio/job-service:dev
  └── [ ] Integration test:
            1. POST /jobs → jobId
            2. curl SSE /jobs/:id/progress (in separate terminal)
            3. redis-cli LPUSH sublio:job:queue {...jobId...}
            4. Watch events in SSE stream

Phase dependencies: Phase 0, Phase 0.5 (Vault database secrets engine for
job_role), Phase 1, Phase 2 (subtitle-service)
```

---

## Phase 6 — api-gateway (Go)

```
Goal: single entry point, JWT validation, rate limiting

Tasks:
  ├── [ ] JWKS client (keyfunc) → fetch + cache Keycloak's public keys at boot
  │         from /realms/sublio/protocol/openid-connect/certs, resolved by `kid`,
  │         auto-refreshed on a cache miss — no Vault involved, no mounted
  │         public.pem file — see backend/10-identity-provider.md
  ├── [ ] middleware/auth.go      ← JWT RS256 verified against the JWKS cache
  ├── [ ] middleware/ratelimit.go ← chi rate limit
  ├── [ ] middleware/cors.go
  ├── [ ] proxy/router.go        ← httputil.ReverseProxy for each service,
  │         propagates traceparent header (trace continues through the proxy hop)
  ├── [ ] handler/docs.go        ← serves api/*.yaml as JSON + Swagger UI at
  │         /api/docs — see backend/07-api-contracts.md
  ├── [ ] config/config.yaml     ← upstream addresses, rate limits
  ├── [ ] Dockerfile + push to Nexus
  │         docker build -t localhost:8081/sublio/api-gateway:dev .
  │         docker push localhost:8081/sublio/api-gateway:dev
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
Phase 0.5 (Nexus + OpenAPI contracts + observability skeleton + Vault + Keycloak)
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
