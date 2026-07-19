# 10 — Identity Provider (Keycloak + JWKS)

## Why Keycloak instead of hand-rolled auth

The original plan had auth-service doing its own bcrypt hashing, its own RS256
signing (later moved to Vault transit), and its own refresh-token table. Keycloak
replaces all of that with a battle-tested OIDC provider: password storage,
brute-force detection, token issuance/rotation, and key rotation are Keycloak's
job, not code this project has to get right itself. This is the standard
"don't build your own IdP" lesson — worth learning by actually wiring one in,
not just by being told not to build one.

**Division of responsibility with Vault (see [[09-secrets-management]]):**
```
Vault     → infra secrets: Postgres dynamic creds, Redis password, Keycloak's
             OWN bootstrap secrets (its DB credential, its admin password, the
             OIDC client secret auth-service uses to call Keycloak)
Keycloak  → identity secrets: user passwords, JWT signing keys, refresh tokens
             — none of this ever touches Vault, because it's not this
             project's secret to manage; it's delegated to Keycloak entirely
```

---

## Realm & client setup

```
Realm: "sublio"
  ├── Brute-force detection: enabled (e.g. 5 failed attempts → temporary lockout)
  ├── Access token lifespan: 15 minutes
  ├── Refresh token lifespan: 30 days (SSO session max)
  └── Client: "auth-service-bff"
        type: confidential (has a client_secret, used server-to-server only —
              never shipped to the browser)
        service account roles: manage-users (so it can call the Admin API to
              register new users on the frontend's behalf)
        direct access grants: enabled (required for the password/ROPC grant)
```

`infra/keycloak/realm-export.json` captures this realm config and is imported
automatically on first boot — the realm is provisioned as code, not clicked
together by hand each time the stack comes up.

---

## auth-service: the BFF flows

**Critical constraint, checked against the actual frontend code (sublio-web/src/services/
authService.ts, api.ts), not just the original design draft:** the refresh token
must NEVER appear in a JSON response body. The frontend calls every auth endpoint
with `withCredentials: true` and expects the refresh token to travel only as an
HttpOnly cookie it never touches via JS. `/register` also auto-logs-in (returns
`{user, accessToken}`, same as `/login`) rather than just creating an account.
There is also a real `/logout` endpoint the frontend calls — not just a client-side
state clear.

**Register** (`POST /register` → create user, then immediately log them in):
```go
func (s *Server) PostRegister(w http.ResponseWriter, r *http.Request) {
    adminToken := s.keycloak.AdminToken(ctx)  // service account client_credentials grant
    _, err := s.keycloak.CreateUser(ctx, adminToken, CreateUserRequest{
        Email:    req.Email,
        Enabled:  true,
        Credentials: []Credential{{Type: "password", Value: req.Password, Temporary: false}},
    })
    if isConflict(err) { respondError(w, 409, "EMAIL_TAKEN"); return }

    // auto-login: same ROPC flow as /login, immediately after creating the account
    tokens, _ := s.keycloak.TokenRequest(ctx, passwordGrant(req.Email, req.Password))
    setRefreshCookie(w, tokens.RefreshToken)
    respondJSON(w, 201, RegisterResponse{User: toUser(tokens), AccessToken: tokens.AccessToken})
}
```

**Login** (`POST /login` → Keycloak token endpoint, ROPC grant):
```go
func (s *Server) PostLogin(w http.ResponseWriter, r *http.Request) {
    tokens, err := s.keycloak.TokenRequest(ctx, passwordGrant(req.Email, req.Password))
    if isUnauthorized(err) { respondError(w, 401, "INVALID_CREDENTIALS"); return }

    setRefreshCookie(w, tokens.RefreshToken)   // HttpOnly, Secure, SameSite=Strict
    respondJSON(w, 200, LoginResponse{User: toUser(tokens), AccessToken: tokens.AccessToken})
    // refresh_token is in the cookie ONLY — never in this JSON body
}

func setRefreshCookie(w http.ResponseWriter, refreshToken string) {
    http.SetCookie(w, &http.Cookie{
        Name: "refreshToken", Value: refreshToken, Path: "/api/auth",
        HttpOnly: true, Secure: true, SameSite: http.SameSiteStrictMode,
    })
}
```

**Refresh** (`POST /refresh` → reads the cookie, not a JSON field):
```go
func (s *Server) PostRefresh(w http.ResponseWriter, r *http.Request) {
    cookie, err := r.Cookie("refreshToken")
    if err != nil { respondError(w, 401, "NO_SESSION"); return }

    tokens, err := s.keycloak.TokenRequest(ctx, refreshGrant(cookie.Value))
    if err != nil { respondError(w, 401, "SESSION_EXPIRED"); return }

    // Keycloak rotates the refresh token on every use — the OLD cookie value
    // becomes invalid the moment this call succeeds. Re-setting the cookie
    // here is not optional; skipping it breaks the NEXT refresh silently.
    setRefreshCookie(w, tokens.RefreshToken)
    respondJSON(w, 200, LoginResponse{User: toUser(tokens), AccessToken: tokens.AccessToken})
}
```

**Logout** (`POST /logout` — new endpoint, not in the original design at all):
```go
func (s *Server) PostLogout(w http.ResponseWriter, r *http.Request) {
    if cookie, err := r.Cookie("refreshToken"); err == nil {
        s.keycloak.Logout(ctx, cookie.Value)  // Keycloak's own /logout endpoint,
                                                // invalidates the session server-side
    }
    http.SetCookie(w, &http.Cookie{
        Name: "refreshToken", Value: "", Path: "/api/auth", MaxAge: -1, HttpOnly: true,
    })
    w.WriteHeader(204)
}
```

**Why ROPC, stated plainly (see also backend/01-services.md #2):** this keeps the
frontend's existing `/api/auth/login` contract — one call, email+password in,
tokens out — completely unchanged, since the frontend is already built against
it. ROPC is a discouraged OAuth2 grant (dropped in OAuth 2.1) because the client
handles the raw password, even though here it's only handled server-side and
forwarded once over the internal network, never logged or stored. The correct
long-term shape is **Authorization Code + PKCE**: the frontend redirects the
browser to Keycloak's own login page, Keycloak redirects back with a code, and
the frontend/BFF exchanges it for tokens — auth-service (or the frontend
directly) never sees the password at all. That's the recommended upgrade,
**documented, not built**, because it requires frontend changes (a redirect-based
login flow instead of a single POST) that are out of scope while the frontend is
otherwise complete.

**Gateway pass-through check:** `httputil.ReverseProxy` forwards `Set-Cookie`
response headers by default, so no special gateway code is needed for the cookie
to reach the browser — but it's worth a manual verification pass (see the gateway
Vault/JWKS issue's neighbors in Linear) since a misconfigured proxy that strips
unrecognized headers would silently break login without an obvious error.

---

## api-gateway: JWT verification via JWKS

```go
// startup: fetch + cache Keycloak's JSON Web Key Set
jwks, err := keyfunc.NewDefault([]string{keycloakURL + "/realms/sublio/protocol/openid-connect/certs"})

// per-request: verify using whichever key the token's header `kid` names
token, err := jwt.Parse(tokenString, jwks.Keyfunc)
if err != nil || !token.Valid {
    http.Error(w, "unauthorized", 401)
    return
}
```

**Key rotation is handled for you:** Keycloak periodically rotates its signing
key. Each JWT's header carries a `kid` (key ID) naming which key signed it. The
JWKS client resolves `kid` → the matching public key from the cached set, and
refetches the JWKS automatically on a cache miss (i.e., a `kid` it hasn't seen
yet) rather than on a fixed timer — so key rotation on Keycloak's side doesn't
require redeploying the gateway.

**Why this is still zero Vault calls and zero Keycloak calls on the hot path:**
the JWKS fetch happens at startup and on the rare `kid` cache miss, not per
request. Verification itself is pure local cryptography against the cached key
— exactly the same performance shape as the original "static public key from
Vault" design, just sourced from Keycloak's JWKS instead.

---

## Postgres: Keycloak's own database

Keycloak needs its own schema for realms/users/sessions — it gets a separate
`keycloak` database on the same Postgres instance (not shared with `sublio`'s
tables), with its own Vault-managed dynamic credential (`keycloak_role`, see
[[09-secrets-management]]) — consistent with the per-service dynamic-credential
pattern already used everywhere else, rather than inventing a different
mechanism just for Keycloak.

---

## Rollout order

```
1. Stand up Keycloak + its own Postgres database (Phase 0.5)
2. Import the "sublio" realm + auth-service-bff client (Phase 0.5)
3. auth-service: implement the four BFF endpoints against Keycloak — register,
   login, refresh, logout (Phase 3)
4. api-gateway: JWKS-based verification, replacing the old
   Vault-KV-public-key design (Phase 6)
```

## What's different in a real production setup

Documented, not built (same spirit as the other "documented, not built"
sections in this project):
```
├── Authorization Code + PKCE instead of ROPC (see the trade-off above) —
│     the single biggest upgrade, deliberately deferred
├── Keycloak clustering/HA (Infinispan cache replication across nodes)
├── MFA (TOTP/WebAuthn) enabled per realm — Keycloak supports this natively,
│     just not turned on here
└── Social login / external IdP federation — Keycloak can broker Google/GitHub
      login without any application code changes, worth knowing it's a realm
      config change away if ever needed
```
