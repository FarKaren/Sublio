{{ with secret "database/creds/keycloak_role" }}
KC_DB_USERNAME={{ .Data.username }}
KC_DB_PASSWORD={{ .Data.password }}
{{ end }}
{{ with secret "secret/data/keycloak" }}
KC_BOOTSTRAP_ADMIN_USERNAME={{ .Data.data.admin_user }}
KC_BOOTSTRAP_ADMIN_PASSWORD={{ .Data.data.admin_password }}
{{ end }}