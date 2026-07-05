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
  ├── github.com/golang-jwt/jwt/v5     ← JWT RS256 validation
  └── golang.org/x/net/http/httputil   ← reverse proxy (stdlib)

auth-service:
  ├── github.com/go-chi/chi/v5
  ├── github.com/golang-jwt/jwt/v5     ← JWT issuance
  ├── golang.org/x/crypto/bcrypt       ← password hashing
  ├── github.com/jackc/pgx/v5          ← PostgreSQL driver
  └── github.com/google/uuid           ← UUID generation

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
├── One private place to publish the 5 in-house service images
├── Pull-through cache → CI doesn't re-download the same base images/deps every run
├── Survives upstream registry rate limits (Docker Hub anonymous pull limits)
└── Standard piece of real company infra — worth knowing how to run it
```

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
    ├── tempo_data         ← trace storage
    ├── prometheus_data    ← metrics storage (short retention — this is a dev box, not a TSDB cluster)
    └── grafana_data       ← dashboards/datasources
```

**Compose profiles:** Nexus and the observability stack are heavy for a laptop
running everything at once. Put them behind Compose profiles (`--profile tooling`,
`--profile observability`) so day-to-day `docker-compose up` stays fast, and you
opt in when you actually want registry caching or dashboards running.

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
