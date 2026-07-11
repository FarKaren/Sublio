# 05 — Technology Stack

## Languages and why

```
Service                  Language    Version   Reason for choice
─────────────────────────────────────────────────────────────────────────
api-gateway              Go          1.22+     Low overhead,
auth-service             Go          1.22+     goroutines for thousands
sublio-media-service     Go          1.22+     of connections, no GC pauses
job-service              Go          1.22+     in IO-bound code

subtitle-service         Kotlin      2.x       Kuromoji — Java library,
                         JVM 21                Spring ecosystem, coroutines

transcription-worker     Python      3.11+     Faster-Whisper is Python-only,
                                               no equivalent in Go/Kotlin

sublio-web               TypeScript  5.x       Type-safe frontend
```

---

## Go services — dependencies

```
api-gateway:
  ├── github.com/go-chi/chi/v5         ← HTTP router
  ├── github.com/go-chi/httprate       ← rate limiting
  ├── github.com/golang-jwt/jwt/v5     ← JWT parsing/validation
  ├── github.com/MicahParks/keyfunc/v3 ← JWKS client: fetches + caches Keycloak's
  │                                       public keys, resolves by `kid`, auto-refreshes
  └── golang.org/x/net/http/httputil   ← reverse proxy (stdlib)

auth-service:  (thin BFF — no JWT library, no bcrypt, no DB driver: Keycloak
                does all of that. See backend/10-identity-provider.md)
  ├── github.com/go-chi/chi/v5
  └── net/http                         ← calls Keycloak's Admin API + token endpoint (stdlib)

sublio-media-service:
  ├── github.com/go-chi/chi/v5
  ├── github.com/google/uuid
  ├── github.com/redis/go-redis/v9     ← Redis client
  └── github.com/jackc/pgx/v5

job-service:
  ├── github.com/go-chi/chi/v5
  ├── github.com/redis/go-redis/v9     ← pub/sub + BLPOP
  ├── github.com/jackc/pgx/v5
  └── github.com/google/uuid
```

**Cross-cutting Go dependencies (every Go service):**
```
Observability (see backend/06-observability.md):
  ├── go.opentelemetry.io/otel                      ← tracing/metrics API
  ├── go.opentelemetry.io/otel/sdk                  ← SDK
  ├── go.opentelemetry.io/otel/exporters/otlp/otlptracegrpc
  ├── go.opentelemetry.io/otel/exporters/otlp/otlpmetricgrpc
  ├── go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp  ← auto-instrument handlers
  ├── go.opentelemetry.io/contrib/instrumentation/.../otelchi        ← chi middleware
  └── github.com/prometheus/client_golang            ← /metrics endpoint

Resilience (see backend/08-scalability.md):
  ├── github.com/sony/gobreaker/v2      ← circuit breaker (subtitle-service calls, Redis, Postgres)
  ├── github.com/jackc/pgx/v5/pgxpool   ← connection pooling (already pulled in via pgx)
  └── golang.org/x/sync/singleflight     ← de-dupe concurrent identical requests (e.g. job status polling)

OpenAPI codegen (see backend/07-api-contracts.md):
  └── github.com/oapi-codegen/oapi-codegen/v2  ← generates chi-compatible server interfaces + DTOs from api/*.yaml

Secrets (see backend/09-secrets-management.md):
  ├── github.com/hashicorp/vault/api     ← Vault client
  └── github.com/hashicorp/vault/api/auth/approle  ← AppRole login helper
```

---

## Kotlin — dependencies (build.gradle.kts)

```kotlin
// subtitle-service
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Japanese NLP
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    // DB
    implementation("org.postgresql:postgresql")

    // JSON
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Observability (see backend/06-observability.md)
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Resilience (see backend/08-scalability.md)
    implementation("io.github.resilience4j:resilience4j-spring-boot3")

    // Secrets (see backend/09-secrets-management.md)
    implementation("org.springframework.vault:spring-vault-core:3.1.1")
}

// OpenAPI codegen (see backend/07-api-contracts.md) — plugin, not a dependency:
// plugins { id("org.openapi.generator") version "7.x" }
// Generates Spring interfaces + DTOs from api/subtitle-service.yaml at build time;
// SubtitleController implements the generated interface instead of hand-rolled routes.
```

---

## Python — dependencies (requirements.txt)

```
# transcription-worker
faster-whisper==1.1.1
redis==5.0.1

# Observability (see backend/06-observability.md)
opentelemetry-sdk==1.27.0
opentelemetry-exporter-otlp==1.27.0

# Resilience (see backend/08-scalability.md)
pybreaker==1.2.0        # circuit breaker around Redis calls

# Secrets (see backend/09-secrets-management.md)
hvac==2.3.0             # Vault client, AppRole login
```

---

## Frontend — dependencies

```json
{
  "dependencies": {
    "react": "^19",
    "react-dom": "^19",
    "sonner": "^2",
    "lucide-react": "^1",
    "next-themes": "^0.4",
    "radix-ui": "^1",
    "class-variance-authority": "^0.7",
    "clsx": "^2",
    "tailwind-merge": "^3"
  },
  "devDependencies": {
    "typescript": "~6",
    "vite": "^8",
    "@vitejs/plugin-react": "^6",
    "tailwindcss": "^4",
    "@tailwindcss/vite": "^4",
    "eslint": "^10",
    "typescript-eslint": "^8",
    "eslint-plugin-react-hooks": "^7",
    "eslint-plugin-react-refresh": "^0.5",
    "eslint-plugin-jsx-a11y": "^6",
    "eslint-config-prettier": "^10",
    "prettier": "^3"
  }
}
```

---

## Infrastructure

```
Service         Image                    Role
──────────────────────────────────────────────────────────────
PostgreSQL      postgres:16-alpine       Primary DB
Redis           redis:7-alpine           Queue + pub/sub
Nginx           nginx:alpine             Frontend static file serving
Nexus           sonatype/nexus3          Private Docker registry + Go/Gradle/PyPI
                                         pull-through cache (build time only)
Vault           hashicorp/vault          Secrets: KV v2, database secrets engine
                                         — see 09-secrets-management.md
Keycloak        quay.io/keycloak/keycloak Identity provider: OIDC, JWKS, password
                                         storage, brute-force protection —
                                         see 10-identity-provider.md
Grafana Alloy   grafana/alloy            OTLP collector (traces + metrics)
Tempo           grafana/tempo            Trace storage/query
Prometheus      prom/prometheus          Metrics storage/query
Grafana         grafana/grafana          Dashboards (Prometheus + Tempo datasources)
(optional)
MinIO           minio/minio              S3-compatible storage
                                         (replaces shared volume in prod)
```

**Why Nexus instead of pulling straight from Docker Hub / proxy.golang.org / Maven Central / PyPI:**
```
├── One private place to publish all 6 in-house service images
├── Pull-through cache → CI doesn't re-download the same base images/deps every run
├── Survives upstream registry rate limits (Docker Hub anonymous pull limits)
└── Standard piece of real company infra — worth knowing how to run it
```

**Every service has its own Dockerfile and its own image — no shared "monolith"
image, no service sharing another's Dockerfile.** Each is built and pushed to
Nexus's `docker-hosted` repo independently, so any one service can be rebuilt,
retagged, and redeployed without touching the others:

```
docker build -t localhost:8081/sublio/{service}:{tag} ./{service-dir}
docker push localhost:8081/sublio/{service}:{tag}

# one line per service, e.g.:
docker build -t localhost:8081/sublio/job-service:dev ./job-service
docker push localhost:8081/sublio/job-service:dev
```

```
Service                  Image name (Nexus docker-hosted)
──────────────────────────────────────────────────────────
api-gateway              sublio/api-gateway
auth-service             sublio/auth-service
sublio-media-service     sublio/media-service
job-service              sublio/job-service
subtitle-service         sublio/subtitle-service
transcription-worker     sublio/transcription-worker
```

`docker-compose.yml` references these images by tag (`image: localhost:8081/sublio/{service}:${TAG:-dev}`)
rather than building in place with `build:` once this is wired up — `build:`
is fine for day-to-day local dev, but pulling a tagged image from Nexus is what
actually exercises the "each service deploys independently" story (e.g. bumping
just `job-service`'s tag and restarting only that container, per the
[[08-scalability]] horizontal-scaling exercise).

---

## Ports (inside Docker network)

```
Service                  Internal port     External (gateway only)
────────────────────────────────────────────────────────────────────
api-gateway              8080              443 (HTTPS)
auth-service             8081              — (only through gateway)
sublio-media-service     8082              —
job-service              8083              —
subtitle-service         8084              —
transcription-worker     —                 — (no HTTP)
PostgreSQL               5432              —
Redis                    6379              —
Keycloak                 8080              localhost:8090 (admin console, dev only)
Vault                    8200              —
```

---

## Docker Compose network schema

```
docker-compose.yml
│
├── networks:
│   ├── sublio_public         ← gateway + frontend
│   ├── sublio_internal       ← all other services
│   └── sublio_observability  ← every service (OTLP egress only) + alloy/tempo/prometheus/grafana
│
└── volumes:
    ├── sublio_data        ← shared volume (video + SRT)
    ├── postgres_data      ← PostgreSQL data
    ├── redis_data         ← Redis data
    ├── nexus_data         ← Nexus blob store (build-time only, own compose profile)
    ├── vault_data         ← Vault file storage backend (KV, leases, policies)
    ├── tempo_data         ← trace storage
    ├── prometheus_data    ← metrics storage (short retention — this is a dev box, not a TSDB cluster)
    └── grafana_data       ← dashboards/datasources
```

**Compose profiles:** Nexus and the observability stack are heavy for a laptop
running everything at once. Put them behind Compose profiles (`--profile tooling`,
`--profile observability`) so day-to-day `docker-compose up` stays fast, and you
opt in when you actually want registry caching or dashboards running. Vault is
the one exception — it's NOT behind a profile, since every service needs it to
even boot (fetches its DB credentials/secrets at startup). It comes up as part
of the default `docker-compose up`, same as Postgres/Redis.

---

## Whisper models

```
For development (fast, less accurate):
  └── medium   (~1.5GB RAM, ~2-3 min per 24-min episode)

For production (more accurate):
  └── large-v3 (~3GB RAM, ~5-7 min per 24-min episode)

Startup parameters:
  device="cpu"        ← CPU (if no GPU)
  compute_type="int8" ← minimum memory
  vad_filter=True     ← removes silence
```
