#!/bin/sh
# All containers, credentials and volumes belong to this disposable test project.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
compose() {
  docker compose -p sublio-far98-test -f "$SCRIPT_DIR/compose.yaml" "$@"
}
cleanup() {
  result=$?
  trap - EXIT
  compose logs --tail 80 prepare bootstrap unit-tests verify || true
  compose down --volumes || true
  exit "$result"
}

# Reusing a previous fixture would reuse consumed SecretIDs and Keycloak users.
compose down --volumes
trap cleanup EXIT
compose up -d --build
verification_id=$(compose ps --all --quiet verify)
test -n "$verification_id"
status=$(docker wait "$verification_id")
test "$status" = 0
