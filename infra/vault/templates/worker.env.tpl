{{ with secret "secret/data/worker" }}
redis_pass={{ .Data.data.redis_pass }}
{{ end }}