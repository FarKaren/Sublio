# 09 — Secrets Management (Vault)

## Why

`.env` files and docker-compose secrets are fine for a toy project, but they don't
teach the thing that actually matters at techlead level: secrets should be short-lived,
scoped to exactly what a service needs, and never sit on disk as a long-lived plaintext
value. Vault is what lets this project practice that for real instead of just reading
about it.

**Rule going forward: no secret value ever lives in `.env`, a compose file, or a
committed config file.** Only `VAULT_ADDR` and a service's AppRole `role_id` are
non-secret bootstrapping info that can live in plain config.

---

## Deployment (dev/single-node — see "what's different in prod" at the bottom)

```
vault server mode, file storage backend (not -dev mode — dev mode auto-unseals
with an in-memory backend and throws everything away on restart, which hides the
exact mechanics worth learning here).

Boot sequence (once, via a setup script — infra/vault/init.sh):
  1. vault operator init          → 5 unseal key shares + 1 root token (printed once)
  2. vault operator unseal ×3     → Shamir's Secret Sharing, needs 3-of-5 shares
  3. vault login <root token>     → used ONLY to bootstrap engines/policies below
  4. Enable engines + create policies/roles (see below)
  5. Root token revoked after setup — nothing after this point uses it
```

**Why 3-of-5 Shamir shares for a single-dev project?** Overkill for the actual
threat model here, but it's the mechanic that matters — in a real team, the 5
shares go to 5 different people so no single person can unseal Vault alone. Worth
having the muscle memory even solo.

---

## Secrets engines enabled

```
1. KV v2  (path: secret/)
   Static secrets: Redis password, Nexus admin password, Grafana admin password,
   Keycloak admin password, and the OIDC client_secret auth-service uses to call
   Keycloak (see backend/10-identity-provider.md). Simple read/write, versioned
   (old values recoverable), but NOT auto-rotated.

2. Database secrets engine  (path: database/)
   Connected to Postgres as a management user. Issues DYNAMIC, short-lived
   credentials per service role:
     job_role       → SELECT/INSERT/UPDATE on jobs
     subtitle_role  → SELECT/INSERT on subtitles, subtitle_entries
     keycloak_role  → full access to the separate `keycloak` database
   Each credential has a lease TTL (~1h) — Vault creates a real Postgres role
   with a random password when a service asks, and drops that role when the
   lease expires or is revoked. No static Postgres password exists anywhere.

Note: no transit engine. JWT signing is entirely Keycloak's responsibility —
see backend/10-identity-provider.md. auth-service itself has no Postgres role
at all (no local user table) and only ever reads ONE Vault secret: its
Keycloak client_secret from KV.
```

---

## AppRole — how services authenticate to Vault

```
Each service gets its own AppRole (least privilege, mirrors the Postgres
per-service-user pattern already used elsewhere in this project):

  role_id    → baked into the service's config (not secret — identifies WHICH role)
  secret_id  → delivered as a one-time-use wrapped token at container start
               (e.g. injected via a short-lived file mounted by the orchestrator,
               consumed once, never persisted) — this IS secret

Login flow at service boot:
  1. POST /v1/auth/approle/login {role_id, secret_id} → Vault client token
  2. Use that token to:
     - read KV secrets the role's policy allows (e.g. auth-service reads its
       Keycloak client_secret; media-service/worker read the Redis password)
     - request a database/creds/{role} lease (job-service, subtitle-service,
       Keycloak itself)
  3. Token itself is short-lived too — renewed in the background alongside
     the database lease
```

**Policy example (`infra/vault/policies/job-service.hcl`):**
```hcl
path "database/creds/job_role" {
  capabilities = ["read"]
}
# job-service gets NOTHING else — no KV access at all.
# It doesn't need the Redis password or anything Keycloak-related.
```

---

## Dynamic Postgres credentials — the lease renewal loop

This is the part that's genuinely different from a static password and worth
implementing carefully:

```go
lease := vaultClient.ReadDBCreds("job_role")   // {username, password, leaseId, leaseDuration}
pool := pgxpool.New(ctx, buildDSN(lease))

go func() {
    ticker := time.NewTicker(lease.leaseDuration * 3 / 4)  // renew at 75% of TTL
    for range ticker.C {
        if err := vaultClient.RenewLease(lease.leaseId); err != nil {
            // renewal failed (Vault down, or lease hit its max TTL) —
            // request a BRAND NEW credential and swap the pool's DSN,
            // rather than letting the pool silently start failing auth
            lease = vaultClient.ReadDBCreds("job_role")
            pool.Reset() // or rebuild the pool with the new credential
        }
    }
}()
```

**Why this matters for the [[08-scalability]] statelessness story:** a service
replica no longer needs a pre-shared static password baked into its environment
to come up — it authenticates to Vault on its own via AppRole and gets its own
credential. A new replica joining a horizontally-scaled job-service doesn't need
any secret to be manually distributed to it; it just needs its `role_id` and a
freshly wrapped `secret_id`, which fits the "any replica can serve any request"
model directly.

---

## Rollout order

Same lesson as the observability rollout in [[06-observability]]: don't wire
Vault into all services at once.

```
1. Stand up Vault + enable both engines + author all policies (Phase 0.5)
2. keycloak_role dynamic credential for Keycloak's own database (Phase 0.5,
   part of standing Keycloak up — see backend/10-identity-provider.md)
3. Copy the dynamic-DB-credential pattern to subtitle-service (Phase 2) and
   job-service (Phase 5)
4. Copy the KV-secret-fetch pattern (simpler — just a password, no dynamic
   lease) to media-service and transcription-worker (Redis password), and to
   auth-service (its Keycloak client_secret)
5. api-gateway does NOT fetch anything from Vault — JWT verification uses
   Keycloak's JWKS endpoint directly (see backend/10-identity-provider.md)
```

---

## What's different in a real production Vault deployment

Documented, not built (same spirit as [[08-scalability]]'s "documented, not
built" section — naming the gap deliberately rather than leaving it implicit):

```
├── Auto-unseal via a cloud KMS (AWS/GCP/Azure) instead of manual Shamir unseal
│     on every restart
├── Integrated storage (Raft) with 3-5 node HA cluster, not a single file backend
├── Audit logging enabled (every secret access logged) — genuinely worth adding
│     even here if time allows, since it's low effort and high learning value
└── Short-lived AppRole secret_ids delivered via a proper secrets-injection
      sidecar (Vault Agent, or the Kubernetes equivalent) instead of a manual
      wrapped-token file
```
