package keycloak

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"

	"auth-service/internal/config"

	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

var (
	ErrInvalidCredentials = errors.New("keycloak: invalid credentials")
	ErrUserAlreadyExists  = errors.New("keycloak: user already exists")
	ErrSessionExpired     = errors.New("keycloak: session expired")
	ErrUnavailable        = errors.New("keycloak: unavailable")
	ErrInvalidInput       = errors.New("keycloak: invalid input")
)

type Tokens struct {
	AccessToken      string `json:"access_token"`
	RefreshToken     string `json:"refresh_token"`
	RefreshExpiresIn int    `json:"refresh_expires_in"`
}

type Client interface {
	Register(ctx context.Context, email, password string) error
	Login(ctx context.Context, email, password string) (Tokens, error)
	Logout(ctx context.Context, refreshToken string) error
	Refresh(ctx context.Context, refreshToken string) (Tokens, error)
}

type HTTPClient struct {
	baseURL      string
	realm        string
	clientID     string
	clientSecret string
	httpClient   *http.Client
}

var _ Client = (*HTTPClient)(nil)

func NewClient(cfg config.KeycloakConfig) *HTTPClient {
	return &HTTPClient{
		baseURL:      strings.TrimRight(cfg.URL, "/"),
		realm:        url.PathEscape(cfg.Realm),
		clientID:     cfg.ClientID,
		clientSecret: cfg.ClientSecret,
		httpClient: &http.Client{
			Timeout:   cfg.RequestTimeout,
			Transport: otelhttp.NewTransport(http.DefaultTransport),
		},
	}
}

func (c *HTTPClient) tokenURL() string {
	return c.baseURL + "/realms/" + c.realm + "/protocol/openid-connect/token"
}

func (c *HTTPClient) logoutURL() string {
	return c.baseURL + "/realms/" + c.realm + "/protocol/openid-connect/logout"
}

func (c *HTTPClient) userAdminURL() string {
	return c.baseURL + "/admin/realms/" + c.realm + "/users"
}

func (c *HTTPClient) Login(ctx context.Context, email, password string) (Tokens, error) {
	form := url.Values{
		"grant_type": {"password"},
		"client_id":  {c.clientID},
		"username":   {email},
		"password":   {password},
	}
	if c.clientSecret != "" {
		form.Set("client_secret", c.clientSecret)
	}
	return c.exchange(ctx, form, ErrInvalidCredentials)
}

func (c *HTTPClient) Refresh(ctx context.Context, refreshToken string) (Tokens, error) {
	if refreshToken == "" {
		return Tokens{}, ErrSessionExpired
	}
	form := url.Values{
		"grant_type":    {"refresh_token"},
		"client_id":     {c.clientID},
		"refresh_token": {refreshToken},
	}
	if c.clientSecret != "" {
		form.Set("client_secret", c.clientSecret)
	}
	return c.exchange(ctx, form, ErrSessionExpired)
}

func (c *HTTPClient) exchange(ctx context.Context, form url.Values, invalidGrant error) (Tokens, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURL(), strings.NewReader(form.Encode()))
	if err != nil {
		return Tokens{}, fmt.Errorf("create token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return Tokens{}, fmt.Errorf("keycloak token endpoint: %w: %v", ErrUnavailable, err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		if resp.StatusCode == http.StatusBadRequest || resp.StatusCode == http.StatusUnauthorized {
			var payload struct {
				Error string `json:"error"`
			}
			_ = json.NewDecoder(io.LimitReader(resp.Body, 64<<10)).Decode(&payload)
			if payload.Error == "invalid_grant" {
				return Tokens{}, invalidGrant
			}
		}
		return Tokens{}, fmt.Errorf("keycloak token endpoint HTTP %d: %w", resp.StatusCode, ErrUnavailable)
	}

	var tokens Tokens
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&tokens); err != nil {
		return Tokens{}, fmt.Errorf("decode keycloak token response: %w", ErrUnavailable)
	}
	if tokens.AccessToken == "" || tokens.RefreshToken == "" {
		return Tokens{}, fmt.Errorf("missing keycloak tokens: %w", ErrUnavailable)
	}
	return tokens, nil
}

func (c *HTTPClient) adminAccessToken(ctx context.Context) (string, error) {
	form := url.Values{
		"grant_type":    {"client_credentials"},
		"client_id":     {c.clientID},
		"client_secret": {c.clientSecret},
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.tokenURL(), strings.NewReader(form.Encode()))
	if err != nil {
		return "", fmt.Errorf("create admin token request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("keycloak admin token request: %w: %v", ErrUnavailable, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("keycloak admin token HTTP %d: %w", resp.StatusCode, ErrUnavailable)
	}
	var body struct {
		AccessToken string `json:"access_token"`
	}
	if err := json.NewDecoder(io.LimitReader(resp.Body, 1<<20)).Decode(&body); err != nil || body.AccessToken == "" {
		return "", fmt.Errorf("decode keycloak admin token: %w", ErrUnavailable)
	}
	return body.AccessToken, nil
}

func (c *HTTPClient) Register(ctx context.Context, email, password string) error {
	accessToken, err := c.adminAccessToken(ctx)
	if err != nil {
		return err
	}

	// Keycloak UserRepresentation supports credentials on user creation.
	type credential struct {
		Type      string `json:"type"`
		Value     string `json:"value"`
		Temporary bool   `json:"temporary"`
	}
	user := struct {
		Username    string       `json:"username"`
		Email       string       `json:"email"`
		Enabled     bool         `json:"enabled"`
		Credentials []credential `json:"credentials"`
	}{
		Username:    email,
		Email:       email,
		Enabled:     true,
		Credentials: []credential{{Type: "password", Value: password, Temporary: false}},
	}

	body, err := json.Marshal(user)
	if err != nil {
		return fmt.Errorf("marshal Keycloak user: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.userAdminURL(), bytes.NewReader(body))
	if err != nil {
		return fmt.Errorf("create Keycloak user request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer "+accessToken)

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("keycloak create user: %w: %v", ErrUnavailable, err)
	}
	defer resp.Body.Close()

	switch resp.StatusCode {
	case http.StatusCreated:
		return nil
	case http.StatusConflict:
		return ErrUserAlreadyExists
	case http.StatusBadRequest:
		return ErrInvalidInput
	default:
		return fmt.Errorf("keycloak create user HTTP %d: %w", resp.StatusCode, ErrUnavailable)
	}
}

func (c *HTTPClient) Logout(ctx context.Context, refreshToken string) error {
	if refreshToken == "" {
		return ErrSessionExpired
	}
	form := url.Values{
		"client_id":     {c.clientID},
		"refresh_token": {refreshToken},
	}
	if c.clientSecret != "" {
		form.Set("client_secret", c.clientSecret)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.logoutURL(), strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("create keycloak logout request: %w", err)
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	resp, err := c.httpClient.Do(req)
	if err != nil {
		return fmt.Errorf("keycloak logout: %w: %v", ErrUnavailable, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode == http.StatusNoContent || resp.StatusCode == http.StatusOK {
		return nil
	}
	if resp.StatusCode == http.StatusBadRequest {
		return ErrSessionExpired
	}
	return fmt.Errorf("keycloak logout HTTP %d: %w", resp.StatusCode, ErrUnavailable)
}
