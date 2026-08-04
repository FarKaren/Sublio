#!/usr/bin/env sh
#
# infra/postgres/scripts/db-init.sh
#
# Generic, idempotent bootstrap for a service's Postgres role (+ optionally
# its own database). Parameterized entirely through environment variables so
# the SAME script/image is reused for keycloak-db-init, job-db-init,
# subtitle-db-init, etc. — only the env vars differ per service.
#
# Required env vars:
#   POSTGRES_ADMIN_USER   - admin user used to run CREATE ROLE / CREATE DATABASE
#   POSTGRES_ADMIN_DB     - database to connect to as admin (usually the
#                            server's default db, e.g. "postgres" or "sublio")
#   OWNER_ROLE            - name of the NOLOGIN role to create/ensure
#                            (this is what {{name}} joins via IN ROLE in
#                            Vault's creation_statements)
#   TARGET_DB             - database that OWNER_ROLE should own / have
#                            privileges on
#
# Optional env vars:
#   CREATE_DB             - "true" (default) creates TARGET_DB with
#                            OWNER_ROLE as owner if it doesn't exist yet.
#                            Set to "false" when TARGET_DB is a shared,
#                            already-existing database (e.g. multiple
#                            services sharing "sublio") — the role then
#                            just gets schema privileges on the existing db.
#   POSTGRES_HOST          - default "postgres"
#   PGPASSWORD_FILE        - default "/run/secrets/postgres_password.txt"

set -eu

: "${POSTGRES_ADMIN_USER:?POSTGRES_ADMIN_USER is required}"
: "${POSTGRES_ADMIN_DB:?POSTGRES_ADMIN_DB is required}"
: "${OWNER_ROLE:?OWNER_ROLE is required}"
: "${TARGET_DB:?TARGET_DB is required}"

POSTGRES_HOST="${POSTGRES_HOST:-postgres}"
CREATE_DB="${CREATE_DB:-true}"
PGPASSWORD_FILE="${PGPASSWORD_FILE:-/run/secrets/postgres_password.txt}"

LOG_TAG="[db-init:${OWNER_ROLE}]"

export PGPASSWORD="$(cat "${PGPASSWORD_FILE}")"
PSQL="psql -h ${POSTGRES_HOST} -U ${POSTGRES_ADMIN_USER} -d ${POSTGRES_ADMIN_DB} -v ON_ERROR_STOP=1"

echo "${LOG_TAG} Waiting for postgres to accept connections..."
until ${PSQL} -tc "SELECT 1" > /dev/null 2>&1; do
  sleep 1
done

echo "${LOG_TAG} Ensuring role ${OWNER_ROLE} exists..."
${PSQL} -tc "SELECT 1 FROM pg_roles WHERE rolname = '${OWNER_ROLE}'" | grep -q 1 || \
  ${PSQL} -c "CREATE ROLE ${OWNER_ROLE} NOLOGIN"

if [ "${CREATE_DB}" = "true" ]; then
  echo "${LOG_TAG} Ensuring database ${TARGET_DB} exists (owner: ${OWNER_ROLE})..."
  ${PSQL} -tc "SELECT 1 FROM pg_database WHERE datname = '${TARGET_DB}'" | grep -q 1 || \
    ${PSQL} -c "CREATE DATABASE \"${TARGET_DB}\" OWNER ${OWNER_ROLE}"
else
  echo "${LOG_TAG} CREATE_DB=false — assuming ${TARGET_DB} already exists (shared database)."
fi

echo "${LOG_TAG} Ensuring schema privileges for ${OWNER_ROLE} on ${TARGET_DB}..."
PSQL_TARGET="psql -h ${POSTGRES_HOST} -U ${POSTGRES_ADMIN_USER} -d ${TARGET_DB} -v ON_ERROR_STOP=1"
${PSQL_TARGET} -c "GRANT ALL ON SCHEMA public TO ${OWNER_ROLE}"
${PSQL_TARGET} -c \
  "ALTER DEFAULT PRIVILEGES FOR ROLE ${OWNER_ROLE} IN SCHEMA public GRANT ALL ON TABLES TO ${OWNER_ROLE}"

echo "${LOG_TAG} Done."