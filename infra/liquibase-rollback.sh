#!/usr/bin/env bash
set -euo pipefail

# Rolls back Liquibase changesets on the sublio Postgres database.
# Usage:
#   ./infra/liquibase-rollback.sh --count 1
#   ./infra/liquibase-rollback.sh --date 2026-07-18T00:00:00
#   ./infra/liquibase-rollback.sh --tag before-subtitles

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
ENV_FILE="$ROOT_DIR/.env"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yaml"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "Missing $ENV_FILE" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$ENV_FILE"
set +a

: "${POSTGRES_USER:?POSTGRES_USER not set in .env}"
: "${POSTGRES_DB:?POSTGRES_DB not set in .env}"

MODE=""
VALUE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --count)
      MODE="rollback-count"
      VALUE="$2"
      shift 2
      ;;
    --date)
      MODE="rollback-to-date"
      VALUE="$2"
      shift 2
      ;;
    --tag)
      MODE="rollback"
      VALUE="$2"
      shift 2
      ;;
    *)
      echo "Unknown argument: $1" >&2
      echo "Usage: $0 --count N | --date YYYY-MM-DDThh:mm:ss | --tag TAG" >&2
      exit 1
      ;;
  esac
done

if [[ -z "$MODE" ]]; then
  echo "Usage: $0 --count N | --date YYYY-MM-DDThh:mm:ss | --tag TAG" >&2
  exit 1
fi

case "$MODE" in
  rollback-count) PARAM="--count=$VALUE" ;;
  rollback-to-date) PARAM="--date=$VALUE" ;;
  rollback) PARAM="--tag=$VALUE" ;;
esac

echo "Running liquibase $MODE $PARAM against ${POSTGRES_DB}..."

# Password is read from the Vault-rendered file INSIDE the container at
# runtime (same source the "liquibase" up-migration service already uses
# via postgres-config-rendered) - never a static value from .env.
docker compose --env-file "$ENV_FILE" -f "$COMPOSE_FILE" run --rm --entrypoint /bin/bash liquibase -c "
  liquibase \
    --changelog-file=changelog-master.yaml \
    --url=jdbc:postgresql://postgres:5432/${POSTGRES_DB} \
    --username=${POSTGRES_USER} \
    --password=\"\$(cat /run/secrets/postgres_password.txt)\" \
    $MODE $PARAM
"
