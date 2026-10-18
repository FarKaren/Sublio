#!/bin/sh
# Configures Nexus for Docker: realm + docker-hosted repository.
# maven-releases / maven-snapshots repositories are created by Nexus by default.
set -eu

AUTH="admin:${NEXUS_ADMIN_PASSWORD}"
API="${NEXUS_URL}/service/rest/v1"
REPO="docker-hosted"
DOCKER_PORT=8083

echo "Waiting for Nexus to become writable..."
until curl -sf -u "$AUTH" "$API/status/writable" >/dev/null; do sleep 3; done

# 1. Docker Bearer Token Realm — `docker login` doesn't work without it
realms=$(curl -sf -u "$AUTH" "$API/security/realms/active")
case "$realms" in
  *DockerToken*)
    echo "DockerToken realm already active" ;;
  *)
    echo "Activating DockerToken realm..."
    new=$(echo "$realms" | sed 's/]$/,"DockerToken"]/')
    curl -sf -u "$AUTH" -X PUT "$API/security/realms/active" \
      -H "Content-Type: application/json" -d "$new" ;;
esac

# 2. docker-hosted repository with an HTTP connector on port 8083
code=$(curl -s -o /dev/null -w '%{http_code}' -u "$AUTH" "$API/repositories/docker/hosted/$REPO")
if [ "$code" = "200" ]; then
  echo "Repository $REPO already exists"
else
  echo "Creating repository $REPO on port $DOCKER_PORT..."
  curl -sf -u "$AUTH" -X POST "$API/repositories/docker/hosted" \
    -H "Content-Type: application/json" \
    -d "{
      \"name\": \"$REPO\",
      \"online\": true,
      \"storage\": {
        \"blobStoreName\": \"default\",
        \"strictContentTypeValidation\": true,
        \"writePolicy\": \"allow\"
      },
      \"docker\": {
        \"v1Enabled\": false,
        \"forceBasicAuth\": true,
        \"httpPort\": $DOCKER_PORT
      }
    }"
fi

echo "Nexus init done."