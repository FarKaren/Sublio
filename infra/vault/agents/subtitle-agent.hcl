pid_file = "/vault/config/pidfile"

vault {
  address = "http://vault:8200"
}

auto_auth {
  method "approle" {
    mount_path = "auth/approle"
    config = {
      role_id_file_path                   = "/vault/config/role-id"
      secret_id_file_path                 = "/vault/config/secret-id"
      remove_secret_id_file_after_reading = true
    }
  }

  sink "file" {
    config = {
      path = "/vault/config/token"
    }
  }
}

template {
  source      = "/vault/templates/subtitle.env.tpl"
  destination = "/vault/rendered/subtitle.env"
  perms = "0644"
}