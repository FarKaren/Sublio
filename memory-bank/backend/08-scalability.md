# 08 — Scalability & High-Load Patterns

## Framing

**Goal:** architect for scale, run small. Every pattern below is something a
real high-load backend needs — but the actual deployment target is
docker-compose on one machine with real (low) traffic. The point is to build
the patterns correctly and be able to explain *why*, not to provision
Kubernetes/multi-region infra for a project with a handful of users. Where a
pattern would only make sense at real scale (autoscaling groups, sharded
Postgres, multi-region), it's noted as "documented, not built."

---

## 1. Statelessness — the core constraint

**Rule:** no service keeps request-relevant state in local memory that another
replica of the same service couldn't also produce. If this holds, any replica
can serve any request and the LB needs no sticky sessions.

```
auth-service     → stateless already (JWT is self-contained, no server session)
media-service    → stateless already (writes straight to shared volume + Redis)
job-service      → THE interesting case (see below)
subtitle-service → stateless already (each /process call is independent)
```

### job-service SSE: stateless despite being a streaming connection

An open `GET /jobs/:id/progress` connection is pinned to *one* instance for its
lifetime (TCP connection has to terminate somewhere) — but that's a transport
fact, not stored state. The instance holding the connection:

```
1. Subscribes to Redis channel sublio:job:{id}:progress (state lives in Redis)
2. On message → writes to the SSE stream
3. Holds NOTHING that another replica doesn't also have access to
```

If that instance dies mid-stream:
```
├── Client's EventSource auto-reconnects → LB routes to ANY replica
├── New replica does NOT know what events were already sent — this is fine
│   because the client's first action on reconnect is GET /jobs/:id (reads
│   current status from Postgres/Redis cache), not "replay events since X"
└── New replica re-subscribes to the same Redis channel, gets subsequent events
```

**This is the proof-of-statelessness to actually run** (see §5): start two
job-service replicas, open an SSE connection, kill the replica holding it,
confirm the client recovers via reconnect + `GET /jobs/:id` without manual
intervention.

---

## 2. Resilience patterns

```
Pattern            Where                          Why
──────────────────────────────────────────────────────────────────────────
Circuit breaker    job-service → subtitle-service  Don't let a slow/down
                    (gobreaker)                    subtitle-service pile up
                                                    goroutines/threads in job-service
Circuit breaker    every service → Postgres/Redis  Fail fast instead of
                                                    hanging when DB/Redis is down
Timeout + context  every outbound call              Every HTTP client call and
                    cancellation                    DB query has a context deadline
                                                    — no unbounded waits
Retry w/ backoff   transcription-worker → Redis     Transient Redis blips shouldn't
                    BLPOP reconnect                 crash the worker loop
Idempotency key    POST /jobs, POST /upload         Client retry after a timeout
                                                     (network blip) must not create
                                                     a duplicate job/file. Key =
                                                     client-generated UUID, stored
                                                     with a unique constraint;
                                                     duplicate key → return the
                                                     existing resource, not 500/dup
Bulkhead           transcription-worker             Docker --cpus/--memory limits
                                                     already planned (03-security.md);
                                                     also cap concurrent Whisper jobs
                                                     per worker (e.g. semaphore of 1-2)
                                                     so one huge video can't starve
                                                     the process
```

---

## 3. Caching & connection pooling

```
pgxpool          every Go service          bounded connection pool (not one
                                            conn per goroutine); pool size tuned
                                            to Postgres max_connections / replica count
Redis GET cache  job-service GET /jobs/:id  sublio:job:{id}:status (already in
                                            02-data-flow.md) — read-through cache,
                                            avoids a Postgres round-trip on every
                                            SSE-less poll
HTTP keep-alive  all internal HTTP calls    reuse connections between job-service
                                            → subtitle-service instead of a new
                                            TCP+TLS handshake per request
```

---

## 4. Backpressure

```
Redis LIST as queue (sublio:job:queue) already gives natural backpressure:
if transcription-worker falls behind, the list grows instead of requests
piling up as open connections. Add:

  ├── sublio_redis_queue_depth gauge (06-observability.md) — alert threshold
  ├── media-service: if queue depth > N, still accept the upload (202) but
  │     surface "queued, position ~N" in the SSE progress message instead of
  │     silently making the user wait with no feedback
  └── job-service: reject new job creation with 503 + Retry-After if queue
        depth exceeds a hard ceiling — protects the worker from falling over
        rather than accepting unbounded work
```

---

## 5. Proving horizontal scalability (docker-compose, not k8s)

This is the concrete exercise that turns "designed to scale" into "verified to
scale," without needing real cluster infra:

```
docker-compose.yml: job-service running with `deploy.replicas: 2` (or two
named services behind a tiny nginx/gateway round-robin) + api-gateway load
balancing between them.

Test:
  1. k6 script opens N concurrent SSE connections + creates N jobs
  2. Watch Grafana: requests split ~evenly across both replica labels
     (service.instance.id in OTEL resource attributes)
  3. Kill one replica mid-run (docker stop) → confirm in-flight SSE clients
     reconnect to the survivor and jobs still complete
  4. No job is lost, no duplicate subtitle generation (idempotency key holds)
```

## 6. Load testing

```
Tool: k6 (scriptable, has a Prometheus/OTEL output — feeds straight into the
      same Grafana dashboards used for real traffic)

infra/loadtest/
  ├── upload_flow.js     ← login → create job → upload small test video →
  │                         watch SSE to DONE (the real user journey)
  └── smoke.js            ← fast sanity check for CI (few iterations)

What to look for (via the RED dashboards from 06-observability.md):
  ├── p95/p99 latency per endpoint under load
  ├── pgxpool saturation (acquired == max → need bigger pool or read replica)
  ├── Redis queue depth trend (draining vs. growing)
  └── error rate (should stay ~0; a k6 run that spikes 5xx found a real bug)
```

**Documented, not built** (real-scale-only, out of scope for a single-box
learning deployment): Kubernetes HPA, multi-region Postgres, Redis Cluster/
Sentinel, CDN in front of video streaming. Worth naming so the "why not" is a
deliberate decision, not an oversight.

---

## 7. Database scalability readiness (schema-level, no sharding yet)

```
├── All PKs are UUID (already the case) — no auto-increment contention,
│   and IDs can be generated client-side/before insert if ever needed
├── jobs.user_id, subtitle_entries.subtitle_id — indexed (FK + query pattern)
├── No cross-service joins — each service owns its tables (already the case
│   per 04-tech-stack.md min-privilege users) — this is what makes it
│   possible to eventually split databases per service without a rewrite
└── subtitle_entries is the one table that grows unbounded with usage —
    partitioning by subtitle_id range or by created_at is the documented
    future step if this ever needs it; not implemented now
```
