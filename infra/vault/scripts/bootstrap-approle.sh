#!/usr/bin/env sh
# infra/vault/scripts/bootstrap-approle.sh
#
# Puts the role_id (static) and a FRESH one-time secret_id into a volume,
# which vault-agent then reads on startup. Runs as a separate short-lived
# container BEFORE vault-agent-*, on EVERY stack startup.
#
# Arguments: $1 = role name (e.g. redis-role)
#            $2 = directory to write role-id / secret-id into

set -eu

ROLE_NAME="$1"
OUTPUT_DIR="$2"

echo "[$ROLE_NAME] Waiting for Vault to become available..."
until vault status > /dev/null 2>&1; do
  sleep 1
done

echo "[$ROLE_NAME] Vault is available, fetching role_id and secret_id..."

vault read -field=role_id "auth/approle/role/${ROLE_NAME}/role-id" \
  > "${OUTPUT_DIR}/role-id"

vault write -f -field=secret_id "auth/approle/role/${ROLE_NAME}/secret-id" \
  > "${OUTPUT_DIR}/secret-id"

echo "[$ROLE_NAME] role-id and secret-id written to ${OUTPUT_DIR}"