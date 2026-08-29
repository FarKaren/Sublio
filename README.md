# Sublio

Sublio turns raw video into Japanese subtitles automatically. Upload a video
with Japanese audio, and Sublio transcribes the speech and generates dual
subtitles — kanji and hiragana — synced to a built-in video player. Built
for people learning Japanese by watching anime.

## How it works

1. Upload a video through the web app
2. The audio is transcribed to Japanese text (Faster-Whisper)
3. The transcript is converted into kanji + hiragana subtitles (Kuromoji)
4. Watch the video with both subtitle tracks, synced automatically

## Tech stack

- **Frontend** — React, TypeScript, Vite, shadcn/ui
- **Backend** — Go (API gateway, auth, media, job services), Kotlin
  (subtitle generation), Python (transcription)
- **Data** — PostgreSQL, Redis
- **Auth** — Keycloak
- **Secrets** — HashiCorp Vault
- **Migrations** — Liquibase

## Project status

- ✅ Frontend — fully implemented
- ✅ Infrastructure — Postgres, Redis, Vault, Keycloak, migrations, linting all running
- ✅ Vault access model — every planned backend service already has its own
  least-privilege Vault policy and credential path, ready for that service
  to consume once it's built
- 🚧 `media-service` — skeleton only, no handlers yet
- ⬜ Auth service, job service, subtitle service, transcription worker, API gateway — not started

---

## Quickstart

### Prerequisites

- Docker + Docker Compose
- Node.js 20 + [pnpm](https://pnpm.io) (frontend)
- Vault CLI (`brew install hashicorp/tap/vault`, or the [apt/yum equivalent](https://developer.hashicorp.com/vault/install))

### 1. Configure environment

```bash
cd infra
cp .env.exemple .env
```

### 2. Start Vault and load secrets (one-time)

Everything the stack needs — Redis password, DB passwords, Keycloak admin
password — lives in Vault, not in `.env` or the compose file.

```bash
# start only vault, and wait until it's actually up before doing anything else —
# it starts SEALED (locked), which is expected, but the container itself must
# be running and responding before init/unseal will work
docker compose up -d vault
docker compose logs -f vault   # wait for it to report it's listening, then Ctrl+C

# initialize and unseal — SAVE the printed unseal keys and root token
export VAULT_ADDR=http://localhost:8200
vault operator init
vault operator unseal <unseal-key-1>
vault operator unseal <unseal-key-2>
vault operator unseal <unseal-key-3>
export VAULT_TOKEN=<root-token>

# enable secret storage and set the base passwords
# POSTGRES_USER/POSTGRES_DB must match what Postgres gets at initdb time
# (infra/.env) — pull them from there instead of retyping, so the two
# never silently drift apart
set -a && source infra/.env && set +a
vault secrets enable -path=secret -version=2 kv
vault kv put secret/redis password="$(openssl rand -base64 24)"
vault kv put secret/postgres username="${POSTGRES_USER}" password="$(openssl rand -base64 24)" database="${POSTGRES_DB}"
vault kv put secret/keycloak admin_user="admin" admin_password="$(openssl rand -base64 24)"

# secrets for the not-yet-built services — safe to set up now, each service
# picks its own up via Vault once it actually exists. job/media/worker just
# need the Redis password; subtitle-service needs no KV secret at all (its
# Postgres credential is dynamic, issued by apply-vault-config.sh below)
vault kv put secret/auth client_id="from keycloak" secret_id="from keycloak"
vault kv put secret/job redis_pass="$(vault kv get -field=password secret/redis)"
vault kv put secret/media redis_pass="$(vault kv get -field=password secret/redis)"
vault kv put secret/worker redis_pass="$(vault kv get -field=password secret/redis)"

# create a bootstrap token and put it in infra/.env as BOOTSTRAP_TOKEN
vault policy write bootstrap-policy vault/policies/bootstrap-policy.hcl
vault token create -policy=bootstrap-policy -period=768h -field=token

# apply every service's Vault policy + AppRole, and wire up the dynamic
# database credentials (Keycloak, and the not-yet-built job/subtitle services)
./vault/scripts/apply-vault-config.sh
```

This part is only needed once per machine (or after wiping Vault's data).

### 3. Start the backend stack

```bash
docker compose up -d
```

This brings up Postgres, Redis, Keycloak, and runs the database migrations
automatically — nothing else to run by hand.

### 4. Start the frontend

```bash
cd sublio-web
pnpm install
pnpm dev
```

### You're up

| What | URL |
|---|---|
| Web app | http://localhost:5173 |
| Keycloak admin console | http://localhost:8090 |
| Vault UI | http://localhost:8200 |

---

## Project layout

```
sublio-web/             Frontend — React + TypeScript
api/                    OpenAPI 3.1 specs — one file per service, source of truth for generated code
auth-service/           Backend auth BFF — Go
media-service/          Backend video service — Go (in progress)
job-service/            Backend job orchestration — Go
infra/                  Docker Compose stack, Vault config, DB migrations
```

## API code generation

Each Go service's request/response types and server interface are generated
from its own spec in `api/` via [oapi-codegen](https://github.com/oapi-codegen/oapi-codegen) —
never written by hand, and never generated from another service's spec (no
build-time coupling between services). Output lands in each service's
`internal/api/generated.go` and is committed to git, so `go build` doesn't
require running codegen first.

```bash
# regenerate all 3 Go services at once
cd infra && task generate
```

Each service also carries its own `//go:generate` directive
(`internal/api/generate.go`), so `go generate ./...` works standalone inside
any one service directory too — `task generate` is just a convenience
wrapper that runs all three.

Before regenerating, specs can be linted:

```bash
npx @redocly/cli lint api/*.yaml
```

> **Important:** after editing any `api/*.yaml` spec (or regenerating Go/Kotlin
> code from one), api-gateway's embedded copy goes stale until you re-sync it:
> ```bash
> cd ~/Desktop/Sublio/infra
> task sync-specs
> ```
> CI enforces this — a PR that changes a spec without re-syncing/regenerating
> fails the `spec-drift-check` / `go-codegen-drift-check` / `kotlin-codegen-check`
> jobs (see `.github/workflows/ci.yml`).

### api-gateway docs portal

api-gateway serves all 4 specs and a browsable UI, so the whole system's
contract is inspectable in one place without touching internal service ports:

```bash
cd infra && task run-gateway   # always re-syncs specs first
```

| What | URL |
|---|---|
| Swagger UI (`/api/docs`) | http://localhost:8080/api/docs |
| Raw spec JSON | http://localhost:8080/api/openapi/{service}.json |

### Taskfile commands (`infra/Taskfile.yaml`)

| Command | What it does |
|---|---|
| `task generate` | Regenerates all 3 Go services' `internal/api/generated.go` from `api/*.yaml` |
| `task sync-specs` | Converts `api/*.yaml` → JSON and copies into `api-gateway/internal/openapi/specs/` (embedded at build time) |
| `task build-gateway` | `sync-specs`, then builds the api-gateway binary |
| `task run-gateway` | `sync-specs`, then runs api-gateway locally |
| `task ci-check-specs` | `sync-specs`, then fails if the embedded copies differ from what's committed — this is what CI runs |

## Everyday commands

```bash
# roll back the last migration
./infra/liquibase-rollback.sh --count 1

# lint / format the frontend
cd sublio-web && pnpm lint && pnpm format

# lint Go services (config in .golangci.yml)
golangci-lint run ./...

# regenerate Go API code from the OpenAPI specs
cd infra && task generate
```
