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
# (.env in the current infra/ directory) — pull them from there, so the two
# never silently drift apart
set -a && source .env && set +a
# The CLI runs on the host; the .env address is for containers.
export VAULT_ADDR=http://localhost:8200
vault secrets enable -path=secret -version=2 kv
vault kv put secret/redis password="$(openssl rand -base64 24)"
vault kv put secret/postgres username="${POSTGRES_USER}" password="$(openssl rand -base64 24)" database="${POSTGRES_DB}"
vault kv put secret/keycloak admin_user="admin" admin_password="$(openssl rand -base64 24)"

# secrets for the not-yet-built services — safe to set up now, each service
# picks its own up via Vault once it actually exists. job/media/worker just
# need the Redis password; subtitle-service needs no KV secret at all (its
# Postgres credential is dynamic, issued by apply-vault-config.sh below)
# auth-service's client secret is provisioned after Keycloak starts (step 4).
vault kv put secret/job redis_pass="$(vault kv get -field=password secret/redis)"
vault kv put secret/media redis_pass="$(vault kv get -field=password secret/redis)"
vault kv put secret/worker redis_pass="$(vault kv get -field=password secret/redis)"

# create a bootstrap token and put it in .env as BOOTSTRAP_TOKEN
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

### 4. Run auth-service with Vault AppRole

There are two different secrets involved:

| Value | Where it lives | What it does |
|---|---|---|
| Vault AppRole SecretID | A file, `/vault/config/secret-id` inside Docker | Together with the RoleID, authenticates auth-service to Vault |
| Keycloak BFF client secret | Vault KV entry `secret/keycloak-client`, field `client_secret` | Authenticates auth-service to Keycloak for login, refresh, logout and registration |

The SecretID file does **not** contain the Keycloak client secret. It is the same
AppRole credential mechanism used by subtitle-service.

**Store the Keycloak secret once.** After Keycloak imports the `sublio` realm,
open `http://localhost:8090`, select **sublio → Clients → auth-service-bff →
Credentials**, and copy the actual client secret. From `infra/`, with the
operator's `VAULT_ADDR` and `VAULT_TOKEN` set:

```bash
vault kv put secret/keycloak-client client_secret="<actual Keycloak BFF client secret>"
```

For illustration, the KV entry's data has this shape (the example value is fake;
use the actual secret generated by Keycloak):

```json
{"client_secret": "example-only-replace-with-the-Keycloak-client-secret"}
```

The client ID `auth-service-bff` and realm `sublio` stay in the service's YAML;
they are not secret. The Go call `client.KVv2("secret").Get(ctx, "keycloak-client")`
uses the **Vault** address and constructs `GET /v1/secret/data/keycloak-client`.
The Keycloak address is used only after the secret has been loaded.

The imported realm makes first and last names optional because `/register`
accepts only email and password. Keycloak imports this configuration on first
boot. For an existing realm, open **Realm settings → User profile**, edit
`firstName` and `lastName`, and turn **Required** off for both; otherwise automatic
login after registration fails with `Account is not fully set up`. Keep the
email validation and requirement enabled.

| Address | Local process | Docker network |
|---|---|---|
| Vault (`VAULT_ADDR`) | `http://localhost:8200` | `http://vault:8200` |
| Keycloak (`KEYCLOAK_URL`) | `http://localhost:8090` | `http://keycloak:8080` |

`auth-policy` grants read only on `secret/data/keycloak-client`. Re-run
`./vault/scripts/apply-vault-config.sh` to apply the policy if upgrading an existing
Vault installation.

**Start with Docker** (from `infra/`, after storing the actual Keycloak secret):

```bash
docker compose up -d --build auth-service
docker compose ps auth-service vault-bootstrap-auth
```

The optional `auth` profile keeps auth-service out of the initial infrastructure
startup, before the Keycloak secret is provisioned. Selecting `auth-service`
explicitly activates it and its bootstrap dependency:

```text
vault-bootstrap-auth → creates role-id + a fresh secret-id in vault-approle-auth
auth-service        → mounts that volume read-only at /vault/config
                    → logs in to Vault using those files
                    → fetches secret/keycloak-client's client_secret
                    → starts its HTTP listener on localhost:8081
```

The Dockerfile's inline entrypoint reads `/vault/config/role-id` into
`VAULT_ROLE_ID`, following the same pattern as subtitle-service, and
`VAULT_SECRET_ID_FILE=/vault/config/secret-id` tells the Go SDK where to read the
SecretID. auth-service logs in directly through the Go SDK and does not need a
Vault Agent or a rendered environment file. Agent/template files are kept only
for Redis, PostgreSQL and Keycloak, which use them in Compose. Job, media and
worker retain their provisioned policies and AppRoles; they have no Agent
containers configured.

The SecretID permits one use and expires unused after 10 minutes. To restart
auth-service, generate a fresh ID before recreating the process; plain
`docker compose restart auth-service` would reuse a consumed ID:

```bash
docker compose stop auth-service
docker compose up --no-deps --force-recreate vault-bootstrap-auth
docker compose up -d --no-deps --build --force-recreate auth-service
```

**Run locally instead:** from `infra/`, provision a fresh SecretID file outside
the repository on every start:

```bash
export VAULT_ROLE_ID=$(vault read -field=role_id auth/approle/role/auth-role/role-id)
export VAULT_SECRET_ID_FILE=$(mktemp /tmp/sublio-auth-secret-id.XXXXXX)
chmod 600 "$VAULT_SECRET_ID_FILE"
vault write -f -field=secret_id auth/approle/role/auth-role/secret-id > "$VAULT_SECRET_ID_FILE"
cd ../auth-service
env -u VAULT_TOKEN -u KEYCLOAK_CLIENT_SECRET -u KEYCLOAK_ADMIN_CLIENT_SECRET \
  CONFIG_PATH=config/conofig.yaml go run ./cmd/auth
# Once the process exits, remove the consumed credential file:
rm -f "$VAULT_SECRET_ID_FILE"
```

The default SecretID path is `/run/secrets/vault_secret_id`; the local example
overrides it with a temporary file and Docker overrides it with the shared-volume
path. Secrets are never read from YAML or Keycloak environment variables.
Startup fails before opening the HTTP listener if Vault login or the
KV read fails, or if `client_secret` is missing, blank or not a string. Vault TLS
settings such as `VAULT_CACERT` are supported by the SDK.

The static secret stays in memory, with no Vault calls or token renewals during
requests. After rotating the secret in Keycloak and Vault, restart auth-service
with a fresh SecretID.

Run auth-service's focused Vault tests with `cd auth-service && go test -race ./...`
from the repository root. These cover failure handling, startup deadlines and
using the AppRole token instead of an inherited admin token. No Vault executable
is needed for these tests.

For the full Docker test, run from the repository root:

```bash
sh infra/tests/auth-vault/run.sh
```

This uses disposable Vault and Keycloak instances, copies Keycloak's actual
generated client secret into test Vault, runs the real bootstrap script, runs
Go tests with the race detector and `go vet`, and verifies the fetched secret
works for registration, login, refresh and logout, plus Vault access denials.
Startup and policy checks live in this single Docker test. It publishes no host
ports and does not access the main stack's data. The runner reports test output,
returns a failing exit code if a check fails, and removes only the isolated
test project's containers and volume when it finishes.

### 5. Start the frontend

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
| `task registry:login` | Logs Docker into the Nexus `docker-hosted` registry |
| `task image:build SERVICE=<name> [TAG=<tag>]` | Builds a service image locally (default tag `dev`) |
| `task image:push SERVICE=<name> [TAG=<tag>]` | Logs in, builds and pushes a service image to Nexus |
| `task image:push-all [TAG=<tag>]` | Builds and pushes images for all services |
| `task jar:publish PROJECT=<name> [MODULE=<name>] [VERSION=<x.y.z>]` | Publishes a Gradle module's jar to Nexus (SNAPSHOT by default, release if `VERSION` is set) |

Run `task --list` to see all commands with descriptions.

### Publishing images and client jars to Nexus

#### Prerequisites

- [Task](https://taskfile.dev) **v3.29+** (`task --version`)
- Nexus is running with the `docker-hosted` repository:
```bash
  docker compose up -d nexus nexus-init
  docker logs nexus-init   # should end with "Nexus init done."
```
- `infra/.env` contains Nexus credentials:
```dotenv
  NEXUS_USERNAME=admin
  NEXUS_PASSWORD=<admin password>
```

| Endpoint | Purpose |
|---|---|
| `http://localhost:18081` | Nexus UI, Maven repositories |
| `localhost:18083` | Docker registry (`docker-hosted`) |

#### Docker images

All commands are run from `infra/`:

```bash
cd infra

# one service, default tag "dev"
task image:push SERVICE=subtitle-service

# one service, release tag
task image:push SERVICE=subtitle-service TAG=0.1.0

# all services
task image:push-all TAG=0.1.0
```

Available services: `auth-service`, `job-service`, `media-service`, `subtitle-service`.

Every image gets two tags:

- `<TAG>` — the one you passed (or `dev`)
- `sha-<commit>` — the short git commit hash, so you can always tell which code an image was built from

Example result: `localhost:18083/sublio/subtitle-service:0.1.0` and `localhost:18083/sublio/subtitle-service:sha-a1b2c3d`.

Pull an image:

```bash
docker pull localhost:18083/sublio/subtitle-service:0.1.0
```

> `subtitle-service` is built with the **repository root** as Docker context (it needs `api/` for code generation). Other services use their own folder.

#### Jars (clients, schemas, shared libraries)

```bash
cd infra

# every module with maven-publish in the given Gradle build
task jar:publish PROJECT=subtitle-service

# one specific module
task jar:publish PROJECT=subtitle-service MODULE=subtitle-service-client

# release version (maven-releases, immutable)
task jar:publish PROJECT=subtitle-service VERSION=0.1.0
```

- `PROJECT` — folder that contains `gradlew`
- `MODULE` — Gradle submodule name (optional)
- Without `VERSION` the jar is published as `X.Y.Z-SNAPSHOT` to `maven-snapshots`

Only modules with the `maven-publish` plugin are published. The service itself (`subtitle-service`) has no `maven-publish`, so its jar never goes to Nexus — only its Docker image does.

Consumers add the dependency:

```kotlin
implementation("com.sublio:subtitle-service-client:0.1.0")
```

> The local Nexus is not reachable from GitHub Actions. Services inside this repository should use `includeBuild("../subtitle-service")` instead of pulling the client from Nexus, otherwise their CI build will fail.

#### Using a different registry

The registry address defaults to `localhost:18083`. Override it with an environment variable:

```bash
REGISTRY=nexus.example.com:8083 task image:push SERVICE=subtitle-service TAG=0.1.0
```

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
