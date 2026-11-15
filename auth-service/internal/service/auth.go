package service

import (
	"context"
	"errors"
	"fmt"

	"auth-service/internal/keycloak"

	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/codes"
	"go.opentelemetry.io/otel/trace"
)

type Tokens struct {
	AccessToken      string
	RefreshToken     string
	RefreshExpiresIn int
}

type AuthService struct {
	keycloak keycloak.Client
}

var _ interface {
	Register(context.Context, string, string) (Tokens, error)
	Login(context.Context, string, string) (Tokens, error)
	Logout(context.Context, string) error
	Refresh(context.Context, string) (Tokens, error)
} = (*AuthService)(nil)

func NewAuthService(client keycloak.Client) *AuthService {
	return &AuthService{keycloak: client}
}

func (s *AuthService) Register(ctx context.Context, email, password string) (Tokens, error) {
	ctx, span := otel.Tracer("auth-service/service").Start(ctx, "AuthService.Register")
	defer span.End()

	if err := s.keycloak.Register(ctx, email, password); err != nil {
		mapped := mapKeycloakError(err)
		recordUnexpected(span, mapped)
		return Tokens{}, fmt.Errorf("register: %w", mapped)
	}
	// The OpenAPI contract requires auto-login after registration.
	return s.login(ctx, email, password, span)
}

func (s *AuthService) Login(ctx context.Context, email, password string) (Tokens, error) {
	ctx, span := otel.Tracer("auth-service/service").Start(ctx, "AuthService.Login")
	defer span.End()
	return s.login(ctx, email, password, span)
}

func (s *AuthService) login(ctx context.Context, email, password string, span trace.Span) (Tokens, error) {
	tokens, err := s.keycloak.Login(ctx, email, password)
	if err != nil {
		mapped := mapKeycloakError(err)
		recordUnexpected(span, mapped)
		return Tokens{}, fmt.Errorf("login: %w", mapped)
	}
	return mapTokens(tokens), nil
}

func (s *AuthService) Refresh(ctx context.Context, token string) (Tokens, error) {
	ctx, span := otel.Tracer("auth-service/service").Start(ctx, "AuthService.Refresh")
	defer span.End()

	tokens, err := s.keycloak.Refresh(ctx, token)
	if err != nil {
		mapped := mapKeycloakError(err)
		recordUnexpected(span, mapped)
		return Tokens{}, fmt.Errorf("refresh: %w", mapped)
	}
	return mapTokens(tokens), nil
}

func (s *AuthService) Logout(ctx context.Context, token string) error {
	ctx, span := otel.Tracer("auth-service/service").Start(ctx, "AuthService.Logout")
	defer span.End()

	if err := s.keycloak.Logout(ctx, token); err != nil {
		mapped := mapKeycloakError(err)
		recordUnexpected(span, mapped)
		return fmt.Errorf("logout: %w", mapped)
	}
	return nil
}

func mapTokens(t keycloak.Tokens) Tokens {
	return Tokens{
		AccessToken:      t.AccessToken,
		RefreshToken:     t.RefreshToken,
		RefreshExpiresIn: t.RefreshExpiresIn,
	}
}

func mapKeycloakError(err error) error {
	switch {
	case errors.Is(err, keycloak.ErrInvalidCredentials):
		return ErrInvalidCredentials
	case errors.Is(err, keycloak.ErrUserAlreadyExists):
		return ErrUserAlreadyExists
	case errors.Is(err, keycloak.ErrSessionExpired):
		return ErrSessionExpired
	case errors.Is(err, keycloak.ErrInvalidInput):
		return ErrInvalidInput
	case errors.Is(err, keycloak.ErrUnavailable):
		return fmt.Errorf("identity provider: %w: %v", ErrUnavailable, err)
	default:
		return fmt.Errorf("identity provider: %w: %v", ErrUnavailable, err)
	}
}

func recordUnexpected(span trace.Span, err error) {
	if errors.Is(err, ErrUnavailable) {
		span.RecordError(err)
		span.SetStatus(codes.Error, "authentication provider unavailable")
	}
}
