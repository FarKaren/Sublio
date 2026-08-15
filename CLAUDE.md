# Sublio — Claude Memory Hub

Sublio is a service for automatic generation of Japanese subtitles for anime and video.
Upload a video → get kanji + hiragana subtitles synchronized with the video player.

Predecessor project: `~/Desktop/jimakutsukeru` (Kotlin monolith).

---

## Memory-bank navigation

### General
| File | Contents |
|------|-----------|
| [00-overview.md](memory-bank/00-overview.md) | Monorepo tree, service diagram, networks, routing |

### Backend
| File | Contents |
|------|-----------|
| [backend/01-services.md](memory-bank/backend/01-services.md) | Detailed description of each of the 7 microservices |
| [backend/02-data-flow.md](memory-bank/backend/02-data-flow.md) | Sequence diagram, Redis channels, PostgreSQL schema |
| [backend/03-security.md](memory-bank/backend/03-security.md) | Security by layers, threat table |
| [backend/04-tech-stack.md](memory-bank/backend/04-tech-stack.md) | Languages, libraries, ports, Docker networks |
| [backend/05-impl-plan.md](memory-bank/backend/05-impl-plan.md) | Implementation phases with dependency diagram |
| [backend/06-observability.md](memory-bank/backend/06-observability.md) | OTEL + Alloy + Tempo + Prometheus + Grafana |
| [backend/07-api-contracts.md](memory-bank/backend/07-api-contracts.md) | OpenAPI spec-first workflow + codegen per language |
| [backend/08-scalability.md](memory-bank/backend/08-scalability.md) | High-load patterns, statelessness, resilience, load testing |
| [backend/09-secrets-management.md](memory-bank/backend/09-secrets-management.md) | Vault: engines, AppRole, dynamic DB creds |
| [backend/10-identity-provider.md](memory-bank/backend/10-identity-provider.md) | Keycloak: realm/client, auth-service BFF, JWKS verification |

### Frontend
| File | Contents |
|------|-----------|
| [frontend/01-architecture.md](memory-bank/frontend/01-architecture.md) | Project tree, shadcn stack, component diagram |
| [frontend/02-components.md](memory-bank/frontend/02-components.md) | All components: shadcn list, UI diagrams, props |
| [frontend/03-pages-routing.md](memory-bank/frontend/03-pages-routing.md) | Pages, routes, UX flows, state machines |
| [frontend/04-state-services.md](memory-bank/frontend/04-state-services.md) | Zustand, TanStack Query, hooks, SSE, axios, types |

### Session summaries
| File | Contents |
|------|-----------|
| [sessions/_index.md](memory-bank/sessions/_index.md) | Index of all session summaries |

---

## Roles & collaboration style

### Frontend (React/TypeScript)
- **My role:** mentor / team lead
- I create and manage tasks/issues in **Linear** (via the Linear MCP server)
- I do **code review** and give advice — I do not write frontend code on my own initiative
- If you are stuck, you can explicitly ask me to write the code

### Backend (Go / Kotlin / Python)
- **My role:** colleague / consultant
- You handle backend implementation; I advise when asked
- You can also ask me to write backend code at any time

---

## Current project state

```
sublio-web/             ← React/TS, DONE (see memory-bank/frontend/)
media-service/   ← Go, skeleton only (config loader + slog logger, no handlers yet)
```

Starting backend now. Goal: reach techlead/architect level — build it with real
high-load patterns and practices, not just the minimum to make it work. See
memory-bank/backend/06-08 for the added observability/contracts/scalability decisions.

The remaining 6 services (auth, media rewrite, job, subtitle, transcription-worker,
gateway) plus cross-cutting infra (Nexus, OpenAPI tooling, observability stack)
need to be built.

---

## Key decisions (do not change without reason)

**Backend:**
- **Go** — Gateway, Auth, Media, Job services (IO-bound, goroutines)
- **Kotlin** — Subtitle Service (Kuromoji — Java library, JVM required)
- **Python** — Transcription Worker (Faster-Whisper, no alternative)
- **Redis** — task queue (BLPOP) + pub/sub for SSE progress
- **PostgreSQL** — persistence (jobs, users, subtitles)
- Shared Docker Volume for file transfer (video → SRT)
- All external requests ONLY through api-gateway
- **Design goal:** architect for scale, run small — real high-load patterns
  (stateless services, caching, circuit breakers, idempotency, load-tested)
  but deployed via docker-compose at real (low) traffic. Learning project,
  not overprovisioned infra. See memory-bank/backend/08-scalability.md.
- **OpenAPI-first, spec-first with codegen** — write the `.yaml` contract in
  `api/` before writing handlers; generate server interfaces/DTOs from it
  (oapi-codegen for Go, openapi-generator for Kotlin). See backend/07-api-contracts.md.
- **Stateless services wherever possible** — no in-memory session/job state;
  SSE connections in job-service are stateless at the instance level (state
  lives in Redis/Postgres, any replica can serve any request). See backend/08-scalability.md.
- **Observability: OTEL SDK → Grafana Alloy → Tempo (traces) + Prometheus (metrics) → Grafana.**
  Logs stay structured stdout (slog/logback/structlog) with trace_id injected,
  not centralized yet. See backend/06-observability.md.
- **Nexus (Sonatype Nexus OSS)** — private Docker registry + pull-through cache
  for Go modules/Gradle/PyPI, used at build time only, not on the request path.
- **HashiCorp Vault for all secrets** — no secret values in `.env`/repo. KV v2 for
  static secrets (Redis password, Keycloak client secret), the **database secrets
  engine** for dynamic short-lived Postgres credentials per service, **AppRole**
  for service auth to Vault. See backend/03-security.md and backend/09-secrets-management.md.
- **Keycloak (OIDC) is the identity provider, JWT verification via JWKS** — Keycloak
  owns password storage, JWT issuance/signing, and refresh tokens. It replaces the
  custom bcrypt+JWT logic originally planned for auth-service. auth-service becomes
  a thin BFF calling Keycloak's Admin API (register) and token endpoint (login/
  refresh/logout) so the frontend's existing `/api/auth/*` contract doesn't change.
  api-gateway verifies tokens against Keycloak's JWKS endpoint (cached, rotates by
  `kid`) instead of a single static public key. See backend/01-services.md #2 and
  backend/10-identity-provider.md.
- **The refresh token is an HttpOnly cookie, never JSON — verified against the
  actual frontend code, not assumed.** `/register` auto-logs-in and there's a real
  `/logout` endpoint; both were missing from the original draft design. Get this
  from backend/01-services.md #2 before touching auth-service, not from memory.

**Frontend:**
- **shadcn/ui** — UI components (do not switch to another library)
- **Zustand** — only for auth state (accessToken)
- **TanStack Query** — server state (videos, subtitles)
- **SSE token** — short-lived token for EventSource (can't use JWT in header)
- **Noto Sans JP** — font for Japanese text
- Dark theme by default, accent — purple (#7c5cbf)
