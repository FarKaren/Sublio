package secrets

import (
	"context"
	"errors"
	"fmt"
	"strings"

	"auth-service/internal/config"

	"github.com/hashicorp/vault/api"
	"github.com/hashicorp/vault/api/auth/approle"
)

// LoadKeycloakClientSecret authenticates once and reads the BFF's only secret.
// No renewal loop is needed: this static KV value is kept in memory and Vault
// is never called on the request path. Restart after rotating the client secret,
// provisioning a fresh single-use SecretID for each process start.
func LoadKeycloakClientSecret(ctx context.Context, cfg config.VaultConfig) (string, error) {
	if err := cfg.Validate(); err != nil {
		return "", err
	}
	ctx, cancel := context.WithTimeout(ctx, cfg.RequestTimeout)
	defer cancel()

	vc := api.DefaultConfig()
	vc.Address = cfg.Address
	vc.Timeout = cfg.RequestTimeout
	// A single-use SecretID must not be retried after an ambiguous login failure.
	vc.MaxRetries = 0
	client, err := api.NewClient(vc)
	if err != nil {
		return "", errors.New("initialize Vault client: check Vault address and TLS configuration")
	}
	// Never use an inherited admin/bootstrap VAULT_TOKEN for service access.
	client.ClearToken()
	auth, err := approle.NewAppRoleAuth(cfg.RoleID, &approle.SecretID{FromFile: cfg.SecretIDFile})
	if err != nil {
		return "", errors.New("initialize Vault AppRole authentication")
	}
	info, err := client.Auth().Login(ctx, auth)
	if err != nil {
		// Vault error bodies may contain sensitive values; keep them out of logs.
		return "", vaultError(ctx, "Vault AppRole login failed: check RoleID, SecretID file and Vault availability")
	}
	if info == nil || info.Auth == nil || info.Auth.ClientToken == "" {
		return "", errors.New("Vault AppRole login returned no client token")
	}

	secret, err := client.KVv2("secret").Get(ctx, "keycloak-client")
	if err != nil {
		return "", vaultError(ctx, "read Vault secret/data/keycloak-client failed: check auth-policy and secret provisioning")
	}
	value, ok := secret.Data["client_secret"].(string)
	if !ok || strings.TrimSpace(value) == "" {
		return "", errors.New("Vault secret/data/keycloak-client must contain a non-empty string client_secret")
	}
	return value, nil
}

func vaultError(ctx context.Context, message string) error {
	if err := ctx.Err(); err != nil {
		return fmt.Errorf("%s: %w", message, err)
	}
	return errors.New(message)
}
