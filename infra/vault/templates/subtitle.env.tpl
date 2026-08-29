{{ with secret "database/creds/subtitle_role" }}
DB_USERNAME={{ .Data.username }}
DB_PASSWORD={{ .Data.password }}
{{ end }}
DB_NAME={{ env "POSTGRES_DB" }}