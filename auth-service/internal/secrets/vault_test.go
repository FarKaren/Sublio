package secrets

import (
	"context"
	"errors"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"auth-service/internal/config"
)

func secretIDFile(t *testing.T) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), "secret-id")
	if err := os.WriteFile(path, []byte("one-use-secret-id\n"), 0600); err != nil {
		t.Fatal(err)
	}
	return path
}

func TestLoadKeycloakClientSecret(t *testing.T) {
	t.Setenv("VAULT_TOKEN", "inherited-admin-token")
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/v1/auth/approle/login":
			if r.Header.Get("X-Vault-Token") != "" {
				t.Error("AppRole login must not use the inherited Vault token")
			}
			_, _ = w.Write([]byte(`{"auth":{"client_token":"service-token"}}`))
		case "/v1/secret/data/keycloak-client":
			if r.Header.Get("X-Vault-Token") != "service-token" {
				t.Error("KV read must use the AppRole token")
			}
			_, _ = w.Write([]byte(`{"data":{"data":{"client_secret":"vault-client-secret"},"metadata":{"version":1}}}`))
		default:
			t.Errorf("unexpected Vault path: %s", r.URL.Path)
			http.NotFound(w, r)
		}
	}))
	defer server.Close()
	value, err := LoadKeycloakClientSecret(context.Background(), config.VaultConfig{
		Address: server.URL, RoleID: "auth-role-id", SecretIDFile: secretIDFile(t), RequestTimeout: time.Second,
	})
	if err != nil || value != "vault-client-secret" {
		t.Fatalf("expected Vault client secret; got error %v", err)
	}
}

func TestLoadKeycloakClientSecretFailures(t *testing.T) {
	for _, tc := range []struct {
		name        string
		login       string
		read        string
		loginCode   int
		readCode    int
		missingFile bool
	}{
		{name: "unreadable SecretID file", missingFile: true},
		{name: "login denied", loginCode: 403, login: `{"errors":["sensitive-response-value"]}`},
		{name: "login unavailable", loginCode: 503},
		{name: "read denied", readCode: 403, read: `{"errors":["sensitive-response-value"]}`},
		{name: "secret missing", readCode: 404},
		{name: "missing client_secret", read: `{"data":{"data":{}}}`},
		{name: "wrong type", read: `{"data":{"data":{"client_secret":42}}}`},
		{name: "blank secret", read: `{"data":{"data":{"client_secret":"  "}}}`},
	} {
		t.Run(tc.name, func(t *testing.T) {
			calls := 0
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				w.Header().Set("Content-Type", "application/json")
				if r.URL.Path == "/v1/auth/approle/login" {
					code, body := tc.loginCode, tc.login
					if code == 0 {
						code = 200
					}
					if body == "" {
						body = `{"auth":{"client_token":"service-token"}}`
					}
					w.WriteHeader(code)
					_, _ = w.Write([]byte(body))
					return
				}
				code := tc.readCode
				if code == 0 {
					code = 200
				}
				w.WriteHeader(code)
				_, _ = w.Write([]byte(tc.read))
			}))
			defer server.Close()
			path := secretIDFile(t)
			if tc.missingFile {
				path += ".missing"
			}
			value, err := LoadKeycloakClientSecret(context.Background(), config.VaultConfig{
				Address: server.URL, RoleID: "auth-role-id", SecretIDFile: path, RequestTimeout: time.Second,
			})
			if err == nil || value != "" {
				t.Fatal("expected startup failure without returning a secret")
			}
			if strings.Contains(err.Error(), "sensitive-response-value") {
				t.Fatal("Vault response leaked in error")
			}
			if tc.loginCode != 0 && calls != 1 {
				t.Fatalf("login was retried: %d calls", calls)
			}
		})
	}
}

func TestVaultStartupDeadline(t *testing.T) {
	for _, stage := range []string{"login", "read"} {
		t.Run(stage, func(t *testing.T) {
			server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if stage == "read" && r.URL.Path == "/v1/auth/approle/login" {
					_, _ = w.Write([]byte(`{"auth":{"client_token":"service-token"}}`))
					return
				}
				select {
				case <-r.Context().Done():
				case <-time.After(200 * time.Millisecond):
				}
			}))
			defer server.Close()
			_, err := LoadKeycloakClientSecret(context.Background(), config.VaultConfig{
				Address: server.URL, RoleID: "auth-role-id", SecretIDFile: secretIDFile(t), RequestTimeout: 50 * time.Millisecond,
			})
			if !errors.Is(err, context.DeadlineExceeded) {
				t.Fatalf("expected deadline error; got %v", err)
			}
		})
	}
}
