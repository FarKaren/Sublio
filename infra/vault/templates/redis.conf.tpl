{{ with secret "secret/data/redis" }}
requirepass {{ .Data.data.password }}
{{ end }}
