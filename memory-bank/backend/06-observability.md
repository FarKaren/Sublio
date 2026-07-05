# 06 — Observability (OTEL + Alloy + Tempo + Prometheus + Grafana)

## Why

Once there are 6 services talking to each other over HTTP/Redis, "it's slow" or
"it failed" is not answerable by reading one service's logs. Observability is
what lets you answer, from one place: *which* service, *which* call, *how long*,
*how often*. This is table-stakes for a techlead-level backend, not a nice-to-have.

## The pipeline

```
┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────┐
│ every service │→│ each service │→│grafana-alloy │→ ┌│  tempo   │  │ prometheus│
│ (Go/Kotlin/Py)│  │  OTEL SDK    │  │ (collector)  │  │(traces)  │  │ (metrics) │
└──────────────┘  └──────────────┘  └──────┬───────┘  └──────────┘  └─────┬─────┘
                                            │ OTLP grpc :4317               │
                                            └───────────────┬───────────────┘
                                                             ▼
                                                       ┌──────────┐
                                                       │ grafana  │  ← dashboards,
                                                       │  :3000   │    trace↔metric
                                                       └──────────┘    correlation
```

Every service exports **traces + metrics** over OTLP to Alloy. Alloy is Grafana's
collector distribution — it replaces running a separate `otel-collector` +
`grafana-agent`. Alloy routes traces to Tempo and metrics to Prometheus (via
`prometheus.remote_write`). Grafana reads both and can jump from a slow trace
span straight to the metric that spiked, and vice versa.

**Logs are NOT centralized yet** (no Loki). Every service still logs structured
JSON to stdout (`slog` for Go, JSON logback for Kotlin, `structlog`/stdlib
`logging` with a JSON formatter for Python) — but every log line must include
`trace_id` and `span_id` when inside a request, so that if Loki is added later,
correlation is a config change, not a re-instrumentation project.

---

## What gets traced

```
Span per hop, propagated via W3C traceparent header:

  gateway (receives request, starts/continues trace)
    └─ auth-service (JWT validate — usually in-gateway, no network hop)
    └─ media-service /upload
         └─ redis LPUSH sublio:job:queue         (span: redis.lpush)
    └─ job-service /jobs
         └─ postgres INSERT jobs                  (span: db.query)
         └─ redis SUBSCRIBE job:{id}:progress     (span: redis.subscribe)
              └─ [async] transcription-worker BLPOP → transcribe → PUBLISH
                   (linked, not parented — this hop is async via Redis,
                    use a span Link, not a parent-child span, so the trace
                    doesn't stay "open" for the whole transcription duration)
              └─ orchestrator → POST subtitle-service:8084/process
                   └─ subtitle-service: SrtParser + Kuromoji + postgres INSERT
```

**Async boundary rule:** don't force parent-child spans across BLPOP/pub-sub —
transcription can take minutes, and a span held open that long pollutes trace
duration and retention. Use OTEL **span links** to connect "job created" to
"job transcribed" as related-but-separate traces instead.

## What gets measured (Prometheus metrics, via OTEL metrics API)

```
RED method per HTTP endpoint (every Go/Kotlin service, via otelhttp/micrometer):
  http_server_requests_total{service, route, method, status}
  http_server_request_duration_seconds{service, route, method}   ← histogram
  http_server_requests_in_flight{service, route}

Business metrics (custom):
  sublio_jobs_total{status}                 ← counter, incremented on each transition
  sublio_job_duration_seconds{stage}        ← histogram: TRANSCRIBING, SUBTITLING, total
  sublio_upload_bytes_total                 ← counter
  sublio_redis_queue_depth                  ← gauge, sampled every N seconds by job-service
  sublio_sse_connections_active             ← gauge, per job-service instance

Infra:
  process_cpu_seconds_total, process_resident_memory_bytes  (standard OTEL runtime metrics)
  pgxpool_acquired_conns, pgxpool_idle_conns                 (connection pool saturation —
                                                               the first thing that tells you
                                                               "you need more replicas or a bigger pool")
```

## Grafana dashboards (one per service + one overview)

```
┌ Overview ────────────────────────────────────────────────┐
│ request rate / error rate / p50-p95-p99 latency per      │
│ service, job funnel (CREATED→QUEUED→...→DONE counts)     │
└────────────────────────────────────────────────────────-─┘
┌ Per-service ───────────────────────────────────────────-─┐
│ RED metrics, DB pool saturation, GC/heap (JVM for         │
│ subtitle-service), circuit breaker state (open/closed)    │
└────────────────────────────────────────────────────────-─┘
```

## Alloy config sketch (`infra/observability/alloy/config.alloy`)

```alloy
otelcol.receiver.otlp "default" {
  grpc { endpoint = "0.0.0.0:4317" }
  http { endpoint = "0.0.0.0:4318" }
  output {
    traces  = [otelcol.exporter.otlp.tempo.input]
    metrics = [otelcol.exporter.prometheus.default.input]
  }
}

otelcol.exporter.otlp "tempo" {
  client { endpoint = "tempo:4317"; tls { insecure = true } }
}

otelcol.exporter.prometheus "default" {
  forward_to = [prometheus.remote_write.default.receiver]
}

prometheus.remote_write "default" {
  endpoint { url = "http://prometheus:9090/api/v1/write" }
}
```

## Rollout order

Don't wire OTEL into all 6 services on day one — it's cross-cutting and easy
to get wrong once. Recommended order:

```
1. Stand up alloy + tempo + prometheus + grafana (empty, nothing sending yet)
2. Instrument ONE service end-to-end (job-service — it's the hub, most valuable)
3. Verify: trace shows up in Tempo, RED metrics show up in Grafana
4. Copy the pattern to the remaining Go services, then Kotlin, then Python
5. Add the business metrics (job funnel, queue depth) last — they need the
   basic pipeline working first
```

See [08-scalability.md](08-scalability.md) for how these metrics feed the
load-testing and scaling-verification phase.
