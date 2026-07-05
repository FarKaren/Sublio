# 07 — API Contracts (OpenAPI, spec-first)

## Principle

The `.yaml` contract is written **before** the handler. The handler's job is to
satisfy a generated interface, not the other way around. This is the difference
between "the API is whatever the code happens to do" and "the API is a designed
contract that the code must honor" — the latter is what lets frontend, gateway,
and other services integrate against a service that doesn't exist yet, and what
catches breaking changes in CI instead of in production.

## Layout

```
Sublio/
└── api/
    ├── auth-service.yaml
    ├── media-service.yaml
    ├── job-service.yaml
    └── subtitle-service.yaml
```

One file per service, OpenAPI 3.1. Each service's `go.mod` / `build.gradle.kts`
points its codegen tool at its own file — a service never generates code from
another service's spec (that would create a build-time coupling between
services that should only ever talk over the network).

## Workflow

```
1. Design the endpoint in api/{service}.yaml — paths, request/response schemas,
   error responses, examples. This is a real design step: think about
   pagination, error shape consistency, idempotency keys (see 08-scalability.md)
   BEFORE they're baked into a handler signature.
2. Run codegen (make generate / go:generate directive) → produces:
     Go:     server interface (ServerInterface), request/response structs,
             a chi-router registration helper
     Kotlin: Spring MVC interface + DTOs (openapi-generator gradle plugin)
3. Implement the generated interface. If the implementation doesn't match the
   interface, it's a compile error — the contract is enforced by the type
   system, not by hoping someone remembers to update the docs.
4. CI step: regenerate from the spec and `git diff --exit-code` — fails the
   build if committed generated code drifted from the spec (someone edited
   generated code by hand, or forgot to regenerate).
```

## Go — oapi-codegen

```go
//go:generate go run github.com/oapi-codegen/oapi-codegen/v2/cmd/oapi-codegen \
//   -generate types,chi-server -package api -o internal/api/generated.go \
//   ../../api/media-service.yaml

// handler/upload.go
type Server struct{ /* deps: storage, redis, logger */ }

// Compile-time proof the handler satisfies the generated contract:
var _ api.ServerInterface = (*Server)(nil)

func (s *Server) PostUpload(w http.ResponseWriter, r *http.Request, params api.PostUploadParams) {
    // params.JobId is already typed + validated from the spec — no manual parsing
}
```

## Kotlin — openapi-generator (Spring)

```kotlin
// build.gradle.kts
plugins { id("org.openapi.generator") version "7.8.0" }

openApiGenerate {
    inputSpec.set("$rootDir/../api/subtitle-service.yaml")
    generatorName.set("kotlin-spring")
    apiPackage.set("sublio.subtitle.generated.api")
    modelPackage.set("sublio.subtitle.generated.model")
}

// SubtitleController.kt implements the generated SubtitleServiceApi interface
@RestController
class SubtitleController(private val service: SubtitleService) : SubtitleServiceApi {
    override fun process(request: ProcessRequest): ResponseEntity<ProcessResponse> { ... }
}
```

## Example spec skeleton (`api/job-service.yaml`)

```yaml
openapi: 3.1.0
info:
  title: job-service
  version: "1.0"
paths:
  /jobs:
    post:
      operationId: createJob
      requestBody:
        required: true
        content:
          application/json:
            schema: { $ref: '#/components/schemas/CreateJobRequest' }
      responses:
        '201':
          content:
            application/json:
              schema: { $ref: '#/components/schemas/JobResponse' }
        '429':
          description: Rate limited
          content:
            application/json:
              schema: { $ref: '#/components/schemas/ErrorResponse' }
components:
  schemas:
    ErrorResponse:            # ONE error shape, shared by every endpoint in every service
      type: object
      required: [code, message]
      properties:
        code: { type: string }        # machine-readable, e.g. "JOB_NOT_FOUND"
        message: { type: string }
        traceId: { type: string }     # ties the error to a Tempo trace (see 06-observability.md)
```

**Error shape is standardized across all 4 specs** — `{ code, message, traceId }`
everywhere. A client (frontend or another service) should never need to know
which backend service produced an error to parse it.

## Gateway: aggregated API portal

api-gateway serves `/api/openapi/{service}.json` (static, copied in at build
time from `api/*.yaml`, converted to JSON) plus a single Swagger UI / Redoc page
at `/api/docs` that lists all four — one browsable contract for the whole
system, without exposing internal service ports.

## What this buys you at techlead level

```
├── Frontend can be built against a mocked server (Prism/msw) generated
│   straight from the spec, before the backend endpoint exists
├── Breaking changes are visible in a spec diff during code review,
│   not discovered at runtime
├── New services/clients integrate by reading one YAML file, not by
│   reverse-engineering handler code
└── Codegen removes an entire class of bugs: hand-written request parsing,
    mismatched field names/types between client and server
```
