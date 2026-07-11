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
│   ├── Dockerfile                      ← own image, pushed to Nexus docker-hosted
│   └── go.mod
│
├── auth-service/                      [Go]  ← thin BFF over Keycloak, see backend/10-identity-provider.md
│   ├── cmd/auth/main.go
│   ├── internal/
│   │   ├── handler/
│   │   │   ├── register.go            ← calls Keycloak Admin API
│   │   │   ├── login.go               ← calls Keycloak token endpoint
│   │   │   ├── refresh.go             ← reads HttpOnly cookie, not JSON
│   │   │   └── logout.go              ← calls Keycloak's logout/revoke endpoint
│   │   └── keycloak/
│   │       └── client.go              ← Admin API + token endpoint HTTP client
│   ├── Dockerfile                      ← own image, pushed to Nexus docker-hosted
│   └── go.mod                          (no PostgreSQL — stateless, no local user table)
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
│   ├── Dockerfile                      ← own image, pushed to Nexus docker-hosted
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
│   ├── Dockerfile                      ← own image, pushed to Nexus docker-hosted
│   └── go.mod
│
├── subtitle-service/                  [Kotlin / Spring Boot]
│   ├── src/main/kotlin/sublio/
│   │   ├── controller/
│   │   │   └── SubtitleController.kt  ← POST /process, GET /subtitles/:id
│   │   ├── service/
│   │   │   ├── KuromojService.kt      ← kanji → hiragana (Kuromoji)
│   │   │   ├── SrtParser.kt           ← .srt file parsing
│   │   │   └── SubtitleService.kt     ← orchestration
│   │   └── repository/
│   │       └── SubtitleRepository.kt  ← PostgreSQL (JPA)
│   ├── Dockerfile                      ← own image (JDK build stage + JRE runtime),
│   │                                      pushed to Nexus docker-hosted
│   └── build.gradle.kts
│
├── transcription-worker/              [Python]
│   ├── worker.py                      ← BLPOP from Redis, run transcription
│   ├── transcribe.py                  ← Faster-Whisper inference
│   ├── Dockerfile                      ← own image, pushed to Nexus docker-hosted
│   └── requirements.txt
│
├── sublio-web/                        [React / TypeScript + shadcn/ui]
│   └── see memory-bank/frontend/ for detailed architecture
│
├── api/                                ← OpenAPI contracts (spec-first, see backend/07-api-contracts.md)
│   ├── auth-service.yaml
│   ├── media-service.yaml
│   ├── job-service.yaml
│   └── subtitle-service.yaml
│
└── infra/
    ├── docker-compose.yml             ← entire stack with one command
    ├── postgres/
    │   └── init.sql                   ← CREATE DATABASE keycloak (separate);
    │                                      jobs, subtitles, subtitle_entries in sublio
    │                                      (no users/refresh_tokens — Keycloak owns identity)
    ├── redis/
    │   └── redis.conf                 ← AUTH + TLS
    ├── certs/
    │   └── ...                        ← TLS certificates for gateway
    ├── nexus/
    │   └── ...                        ← Sonatype Nexus data volume (Docker/Go/Gradle/PyPI proxy)
    ├── vault/
    │   ├── config.hcl                 ← file storage backend, listener config
    │   └── policies/                  ← one least-privilege policy per service
    ├── observability/
    │   ├── alloy/
    │   │   └── config.alloy           ← OTLP receiver → routes to Tempo + Prometheus
    │   ├── tempo/
    │   │   └── tempo.yaml             ← trace storage config
    │   ├── prometheus/
    │   │   └── prometheus.yml         ← scrape config (pulls from Alloy)
    │   └── grafana/
    │       └── dashboards/            ← per-service dashboards (provisioned)
    └── keycloak/
        └── realm-export.json          ← "sublio" realm, confidential client, brute-force policy
                                          (imported on first boot — see backend/10-identity-provider.md)
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
    │  [Go, BFF]   │  │    [Go]      │  │    [Go]      │
    │              │  │              │  │              │
    │ /register    │  │ /upload      │  │ /jobs        │
    │ /login       │  │ /video/:id   │  │ /jobs/:id    │
    │ /refresh     │  │   streaming  │  │ /jobs/:id/   │
    │ → Keycloak   │  │ → storage    │  │   progress   │
    │   Admin API +│  │ → Redis PUSH │  │ → SSE stream │
    │   token endpt│  │              │  │              │
    └──────┬───────┘  └──────────────┘  └──────┬───────┘
           │ OIDC
           ▼
    ┌──────────────┐
    │  keycloak    │  ← owns password storage, JWT signing, JWKS
    │  (own DB)    │
    └──────────────┘
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
│  keycloak          :8080 (internal only — admin     │
│                     console on localhost:8090 for    │
│                     dev convenience, not proxied)    │
│                                                     │
│  PostgreSQL        :5432  (databases: sublio,        │
│                     keycloak)                        │
│  Redis             :6379                            │
└─────────────────────┬───────────────────────────────┘
                      │ OTLP (grpc :4317 / http :4318)
                      │ every service pushes traces+metrics here
┌─────────────────────▼───────────────────────────────┐
│  OBSERVABILITY ZONE (internal, dev-exposed only)    │
│                                                     │
│  grafana-alloy     :4317/4318 (OTLP in)             │
│  tempo             :3200 (query)                    │
│  prometheus        :9090                            │
│  grafana           :3000 (localhost only, not gateway)│
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  BUILD / SUPPLY-CHAIN ZONE (used at build time, not  │
│  on the request path)                                │
│                                                     │
│  nexus  :8081 (UI/API) — private Docker registry +  │
│           pull-through cache for Go modules, Gradle, │
│           PyPI. CI and `docker build` point here     │
│           instead of hitting public registries        │
│           directly on every build.                   │
└─────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────┐
│  SECRETS ZONE (internal only, every service reads    │
│  from it once at boot — never on the request path)  │
│                                                     │
│  vault  :8200 — KV v2 (static secrets) + database    │
│           secrets engine (dynamic Postgres creds) +  │
│           transit engine (JWT signing for            │
│           auth-service). Services authenticate via   │
│           AppRole, not a shared token.                │
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

Never proxied by gateway (internal-only, scraped directly inside
the Docker network):
  /metrics            →  each service's Prometheus exporter
  /healthz, /readyz   →  liveness/readiness probes
```
