{{ with secret "database/creds/job_role" }}
DB_USERNAME={{ .Data.username }}
DB_PASSWORD={{ .Data.password }}
{{ end }}
DB_NAME={{ env "POSTGRES_DB" }}
{{ with secret "secret/data/job" }}
redis_pass={{ .Data.data.redis_pass }}
{{ end }}