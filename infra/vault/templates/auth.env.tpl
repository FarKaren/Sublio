{{ with secret "secret/data/auth" }}
client_id={{ .Data.data.client_id }}
secret_id={{ .Data.data.secret_id }}
{{ end }}