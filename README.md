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
- 🚧 `sublio-media-service` — skeleton only, no handlers yet
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
vault secrets enable -path=secret -version=2 kv
vault kv put secret/redis password="$(openssl rand -base64 24)"
vault kv put secret/postgres username="sublio" password="$(openssl rand -base64 24)" database="sublio"
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
sublio-media-service/   Backend video service — Go (in progress)
infra/                  Docker Compose stack, Vault config, DB migrations
```

## Everyday commands

```bash
# roll back the last migration
./infra/liquibase-rollback.sh --count 1

# lint / format the frontend
cd sublio-web && pnpm lint && pnpm format

# lint Go services (config in .golangci.yml)
golangci-lint run ./...
```
