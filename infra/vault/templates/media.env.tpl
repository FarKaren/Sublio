{{ with secret "secret/data/media" }}
redis_pass={{ .Data.data.redis_pass }}
{{ end }}