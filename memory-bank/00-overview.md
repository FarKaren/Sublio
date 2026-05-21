# 00 — Sublio General Architecture

## Principle

Microservice architecture. Each service is a separate process, a separate container.
The only entry point is `api-gateway`. Internal services are not accessible from outside.

---

## Monorepo tree

```
~/Desktop/Sublio/
│
├── CLAUDE.md                          ← entry point for Claude
├── memory-bank/                       ← architecture documentation
│
├── api-gateway/                       [Go]
│   ├── cmd/gateway/main.go
│   ├── internal/
│   │   ├── middleware/
│   │   │   ├── auth.go                ← JWT RS256 validation
│   │   │   ├── ratelimit.go           ← rate limiting per IP
│   │   │   └── cors.go                ← CORS whitelist
│   │   └── proxy/
│   │       └── router.go              ← reverse proxy to services
│   ├── config/config.yaml
│   └── go.mod
│
├── auth-service/                      [Go]
│   ├── cmd/auth/main.go
│   ├── internal/
│   │   ├── handler/
│   │   │   ├── register.go
│   │   │   └── login.go
│   │   ├── jwt/
│   │   │   └── token.go               ← JWT issuance/validation
│   │   └── repository/
│   │       └── user_repo.go           ← PostgreSQL
│   └── go.mod
│
├── sublio-media-service/              [Go]  ← already created
│   ├── cmd/sublio/main.go             ← skeleton (rewrite)
│   ├── internal/
│   │   ├── handler/
│   │   │   ├── upload.go              ← chunked multipart upload
│   │   │   └── stream.go              ← HTTP 206 Range requests
│   │   ├── storage/
│   │   │   └── local.go               ← files with UUID names
│   │   ├── queue/
│   │   │   └── redis.go               ← publish tasks to Redis
│   │   └── security/
│   │       └── validator.go           ← magic bytes + size
│   └── go.mod
│
├── job-service/                       [Go]
│   ├── cmd/job/main.go
│   ├── internal/
│   │   ├── handler/
│   │   │   ├── job.go                 ← POST /jobs, GET /jobs/:id
│   │   │   └── sse.go                 ← GET /jobs/:id/progress (SSE)
│   │   ├── orchestrator/
│   │   │   └── orchestrator.go        ← listens to Redis, calls subtitle-service
│   │   ├── queue/
│   │   │   └── redis_sub.go           ← Redis pub/sub subscription
│   │   └── repository/
│   │       └── job_repo.go            ← PostgreSQL
│   └── go.mod
│
├── subtitle-service/                  [Kotlin / Spring Boot]
│   └── src/main/kotlin/sublio/
│       ├── controller/
│       │   └── SubtitleController.kt  ← POST /process, GET /subtitles/:id
│       ├── service/
│       │   ├── KuromojService.kt      ← kanji → hiragana (Kuromoji)
│       │   ├── SrtParser.kt           ← .srt file parsing
│       │   └── SubtitleService.kt     ← orchestration
│       └── repository/
│           └── SubtitleRepository.kt  ← PostgreSQL (JPA)
│
├── transcription-worker/              [Python]
│   ├── worker.py                      ← BLPOP from Redis, run transcription
│   ├── transcribe.py                  ← Faster-Whisper inference
│   ├── Dockerfile
│   └── requirements.txt
│
├── sublio-web/                        [React / TypeScript + shadcn/ui]
│   └── see memory-bank/frontend/ for detailed architecture
│
└── infra/
    ├── docker-compose.yml             ← entire stack with one command
    ├── postgres/
    │   └── init.sql                   ← schemas: users, jobs, subtitles
    ├── redis/
    │   └── redis.conf                 ← AUTH + TLS
    └── certs/
        └── ...                        ← TLS certificates for gateway
```

---

## Service diagram and interactions

```
                        ┌─────────────────────────────────────────────┐
                        │              EXTERNAL NETWORK                │
                        └──────────────────┬──────────────────────────┘
                                           │ HTTPS :443
                                           ▼
                        ┌─────────────────────────────────────────────┐
                        │             api-gateway  [Go]               │
                        │   TLS · JWT validate · Rate limit · CORS    │
                        └──┬────────────┬───────────┬─────────────────┘
                           │            │            │
              ┌────────────┘   ┌────────┘   ┌───────┘
              │                │            │
              ▼                ▼            ▼
    ┌──────────────┐  ┌──────────────┐  ┌──────────────┐
    │ auth-service │  │ media-service│  │  job-service │
    │    [Go]      │  │    [Go]      │  │    [Go]      │
    │              │  │              │  │              │
    │ /register    │  │ /upload      │  │ /jobs        │
    │ /login       │  │ /video/:id   │  │ /jobs/:id    │
    │              │  │   streaming  │  │ /jobs/:id/   │
    │ → PostgreSQL │  │ → storage    │  │   progress   │
    │ → JWT issue  │  │ → Redis PUSH │  │ → SSE stream │
    └──────────────┘  └──────────────┘  └──────┬───────┘
                                                │
                        ┌───────────────────────┘
                        │ HTTP (internal)
                        ▼
              ┌──────────────────────┐
              │   subtitle-service   │
              │  [Kotlin/Spring Boot]│
              │                      │
              │ POST /process        │
              │ GET  /subtitles/:id  │
              │                      │
              │ Kuromoji kanji→hira  │
              │ → PostgreSQL         │
              └──────────────────────┘

                   INFRASTRUCTURE (internal Docker network)
    ┌──────────────────────────────────────────────────────────────┐
    │                                                              │
    │   ┌─────────────┐   ┌─────────────┐   ┌──────────────────┐ │
    │   │ PostgreSQL  │   │    Redis    │   │  Shared Volume   │ │
    │   │             │   │             │   │  (video + SRT)   │ │
    │   │ ·users      │   │ ·job queue  │   │                  │ │
    │   │ ·jobs       │   │ ·pub/sub    │   │  /data/          │ │
    │   │ ·subtitles  │   │  progress   │   │  ├── videos/     │ │
    │   └─────────────┘   └──────┬──────┘   │  └── processed/  │ │
    │                            │          └──────────────────┘ │
    └────────────────────────────┼──────────────────────────────-┘
                                 │ BLPOP (blocking)
                                 ▼
                    ┌─────────────────────────┐
                    │  transcription-worker   │
                    │       [Python]          │
                    │                         │
                    │  Faster-Whisper         │
                    │  large-v3 model         │
                    │  → kanji.srt            │
                    │  → Redis PUBLISH        │
                    └─────────────────────────┘
```

---

## Network zones

```
┌─────────────────────────────────────────────────────┐
│  PUBLIC ZONE (accessible from outside)              │
│                                                     │
│  api-gateway :443 (HTTPS)                           │
└─────────────────────────┬───────────────────────────┘
                          │ docker internal network
┌─────────────────────────▼───────────────────────────┐
│  PRIVATE ZONE (only inside Docker network)          │
│                                                     │
│  auth-service      :8081                            │
│  media-service     :8082                            │
│  job-service       :8083                            │
│  subtitle-service  :8084                            │
│  transcription-worker  (no HTTP port)               │
│                                                     │
│  PostgreSQL        :5432                            │
│  Redis             :6379                            │
└─────────────────────────────────────────────────────┘
```

---

## Gateway routing

```
/api/auth/*          →  auth-service:8081
/api/upload          →  media-service:8082
/api/video/*         →  media-service:8082
/api/jobs/*          →  job-service:8083
/api/subtitles/*     →  subtitle-service:8084
/*                   →  sublio-web (static files)
```
