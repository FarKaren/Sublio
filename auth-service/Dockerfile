FROM golang:1.26 AS build
WORKDIR /src/auth-service
COPY auth-service/go.mod auth-service/go.sum ./
RUN go mod download
COPY auth-service/ ./
RUN CGO_ENABLED=0 go build -trimpath -o /out/auth-service ./cmd/auth

FROM build AS test
CMD ["sh", "-ec", "go test -race ./... -timeout 90s && go vet ./..."]

FROM alpine:3.23 AS runtime
RUN apk add --no-cache ca-certificates
WORKDIR /app
COPY --from=build /out/auth-service /app/auth-service
COPY auth-service/config/conofig.yaml /app/config.yaml
ENV CONFIG_PATH=/app/config.yaml HTTP_ADDRESS=0.0.0.0:8081
EXPOSE 8081
HEALTHCHECK --interval=5s --timeout=3s --start-period=10s --retries=12 \
  CMD wget -qO- http://127.0.0.1:8081/actuator/health || exit 1
ENTRYPOINT ["/bin/sh", "-ec", "export VAULT_ROLE_ID=\"$(cat /vault/config/role-id)\"; exec /app/auth-service"]
