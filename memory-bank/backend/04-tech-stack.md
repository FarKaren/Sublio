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
}
```

---

## Python — dependencies (requirements.txt)

```
# transcription-worker
faster-whisper==1.1.1
redis==5.0.1
```

---

## Frontend — dependencies

```json
{
  "dependencies": {
    "react": "^18",
    "react-dom": "^18",
    "axios": "^1.6"
  },
  "devDependencies": {
    "typescript": "^5",
    "vite": "^5",
    "@vitejs/plugin-react": "^4"
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
(optional)
MinIO           minio/minio              S3-compatible storage
                                         (replaces shared volume in prod)
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
│   ├── sublio_public      ← gateway + frontend
│   └── sublio_internal    ← all other services
│
└── volumes:
    ├── sublio_data        ← shared volume (video + SRT)
    ├── postgres_data      ← PostgreSQL data
    └── redis_data         ← Redis data
```

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
