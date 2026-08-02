#!/usr/bin/env sh
# infra/vault/scripts/init-keycloak-db.sh
#
# Creates the `keycloak` database and the stable `keycloak_owner` group role
# on the SHARED postgres instance, if they don't already exist. Runs as a
# short-lived container (keycloak-db-init) BEFORE vault-agent-keycloak and
# keycloak, on EVERY stack startup. Idempotent - safe to run repeatedly.
#
# keycloak_owner is NOLOGIN and owns the database/schema/tables. Vault's
# keycloak_role dynamic credential creates a login role that only joins
# keycloak_owner (IN ROLE) rather than owning anything itself, so Postgres
# can always DROP that role cleanly once its lease expires.
#
# Requires: POSTGRES_USER, POSTGRES_DB, KEYCLOAK_POSTGRES_DB in environment,
# and /run/secrets/postgres_password.txt mounted (same file postgres/liquibase use).

set -eu

export PGPASSWORD="$(cat /run/secrets/postgres_password.txt)"
PSQL="psql -h postgres -U ${POSTGRES_USER} -d ${POSTGRES_DB} -v ON_ERROR_STOP=1"

echo "[keycloak-db-init] Waiting for postgres to accept connections..."
until ${PSQL} -tc "SELECT 1" > /dev/null 2>&1; do
  sleep 1
done

echo "[keycloak-db-init] Ensuring role keycloak_owner exists..."
${PSQL} -tc "SELECT 1 FROM pg_roles WHERE rolname = 'keycloak_owner'" | grep -q 1 || \
  ${PSQL} -c "CREATE ROLE keycloak_owner NOLOGIN"

echo "[keycloak-db-init] Ensuring database ${KEYCLOAK_POSTGRES_DB} exists..."
${PSQL} -tc "SELECT 1 FROM pg_database WHERE datname = '${KEYCLOAK_POSTGRES_DB}'" | grep -q 1 || \
  ${PSQL} -c "CREATE DATABASE \"${KEYCLOAK_POSTGRES_DB}\" OWNER keycloak_owner"

echo "[keycloak-db-init] Ensuring schema privileges for keycloak_owner..."
${PSQL} -d "${KEYCLOAK_POSTGRES_DB}" -c "GRANT ALL ON SCHEMA public TO keycloak_owner"
${PSQL} -d "${KEYCLOAK_POSTGRES_DB}" -c \
  "ALTER DEFAULT PRIVILEGES FOR ROLE keycloak_owner IN SCHEMA public GRANT ALL ON TABLES TO keycloak_owner"

echo "[keycloak-db-init] Done."
