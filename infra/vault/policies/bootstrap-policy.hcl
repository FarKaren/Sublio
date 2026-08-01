# Permissions bootstrap containers need to issue role_id/secret_id to
# the service vault-agents. Does NOT grant direct access to KV secrets -
# only to the AppRole login mechanics. Even if the bootstrap token leaks,
# it can't read the redis/postgres password directly - it can only
# obtain the ability to log in as that role (and that's already
# restricted by the role's own policy).

path "auth/approle/role/+/role-id" {
  capabilities = ["read"]
}

path "auth/approle/role/+/secret-id" {
  capabilities = ["create", "update"]
}
