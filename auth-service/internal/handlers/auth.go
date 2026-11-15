package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"mime"
	"net/http"
	"net/mail"
	"strings"
	"time"

	"auth-service/internal/api"
	"auth-service/internal/config"
	"auth-service/internal/service"
)

type AuthService interface {
	Register(ctx context.Context, email, password string) (service.Tokens, error)
	Login(ctx context.Context, email, password string) (service.Tokens, error)
	Logout(ctx context.Context, token string) error
	Refresh(ctx context.Context, token string) (service.Tokens, error)
}

type AuthHandler struct {
	service  AuthService
	log      *slog.Logger
	security config.SecurityConfig
}

var _ api.ServerInterface = (*AuthHandler)(nil)

func NewAuthHandler(service AuthService, log *slog.Logger, security config.SecurityConfig) *AuthHandler {
	return &AuthHandler{service: service, log: log, security: security}
}

func (h *AuthHandler) Login(w http.ResponseWriter, r *http.Request) {
	req, err := readCredentials(w, r)
	if err != nil {
		writeError(w, r, h.log, http.StatusBadRequest, "INVALID_REQUEST", "Invalid credentials JSON")
		return
	}
	tokens, err := h.service.Login(r.Context(), string(req.Email), req.Password)
	if err != nil {
		h.writeServiceError(w, r, "login", err)
		return
	}
	h.setRefreshCookie(w, tokens)
	h.writeAuthResponse(w, r, http.StatusOK, tokens)
}

func (h *AuthHandler) Register(w http.ResponseWriter, r *http.Request) {
	req, err := readCredentials(w, r)
	if err != nil {
		writeError(w, r, h.log, http.StatusBadRequest, "INVALID_REQUEST", "Invalid credentials JSON")
		return
	}
	tokens, err := h.service.Register(r.Context(), string(req.Email), req.Password)
	if err != nil {
		h.writeServiceError(w, r, "register", err)
		return
	}
	h.setRefreshCookie(w, tokens)
	h.writeAuthResponse(w, r, http.StatusCreated, tokens)
}

func (h *AuthHandler) Refresh(w http.ResponseWriter, r *http.Request) {
	if !h.trustedOrigin(r) {
		writeError(w, r, h.log, http.StatusForbidden, "CSRF_CHECK_FAILED", "Origin not allowed")
		return
	}
	cookie, err := r.Cookie(h.security.CookieName)
	if err != nil || cookie.Value == "" {
		writeError(w, r, h.log, http.StatusUnauthorized, "NO_SESSION", "Authentication required")
		return
	}
	tokens, err := h.service.Refresh(r.Context(), cookie.Value)
	if err != nil {
		if errors.Is(err, service.ErrSessionExpired) {
			h.clearRefreshCookie(w)
		}
		h.writeServiceError(w, r, "refresh", err)
		return
	}
	h.setRefreshCookie(w, tokens)
	h.writeAuthResponse(w, r, http.StatusOK, tokens)
}

func (h *AuthHandler) Logout(w http.ResponseWriter, r *http.Request) {
	if !h.trustedOrigin(r) {
		writeError(w, r, h.log, http.StatusForbidden, "CSRF_CHECK_FAILED", "Origin not allowed")
		return
	}
	cookie, err := r.Cookie(h.security.CookieName)
	if err != nil || cookie.Value == "" {
		// Logout is idempotent: no cookie means no browser session to clear.
		h.clearRefreshCookie(w)
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err := h.service.Logout(r.Context(), cookie.Value); err != nil {
		if !errors.Is(err, service.ErrSessionExpired) {
			h.writeServiceError(w, r, "logout", err)
			return
		}
	}
	h.clearRefreshCookie(w)
	w.WriteHeader(http.StatusNoContent)
}

func readCredentials(w http.ResponseWriter, r *http.Request) (api.Credentials, error) {
	var req api.Credentials
	mediaType, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
	if err != nil || mediaType != "application/json" {
		return req, errors.New("Content-Type must be application/json")
	}
	r.Body = http.MaxBytesReader(w, r.Body, 16<<10)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&req); err != nil {
		return req, err
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return req, errors.New("expected exactly one JSON object")
	}
	address, err := mail.ParseAddress(string(req.Email))
	if err != nil || address.Address != string(req.Email) || strings.TrimSpace(req.Password) == "" {
		return req, errors.New("email or password missing/invalid")
	}
	return req, nil
}

func (h *AuthHandler) writeAuthResponse(w http.ResponseWriter, r *http.Request, status int, tokens service.Tokens) {
	w.Header().Set("Cache-Control", "no-store")
	writeJSON(w, r, h.log, status, api.AuthResponse{
		AccessToken: &tokens.AccessToken,
		// User is optional in the supplied OpenAPI contract; not populated here.
	})
}

func (h *AuthHandler) writeServiceError(w http.ResponseWriter, r *http.Request, operation string, err error) {
	status, code, message := mapServiceError(err)
	if status >= 500 {
		h.log.ErrorContext(r.Context(), fmt.Sprintf("%s failed", operation), "error", err, "code", code)
	}
	writeError(w, r, h.log, status, code, message)
}

func (h *AuthHandler) cookieTemplate() *http.Cookie {
	c := &http.Cookie{
		Name:     h.security.CookieName,
		Path:     "/",
		HttpOnly: true,
		Secure:   h.security.CookieSecure,
	}
	switch h.security.CookieSameSite {
	case "strict":
		c.SameSite = http.SameSiteStrictMode
	case "none":
		c.SameSite = http.SameSiteNoneMode
	default:
		c.SameSite = http.SameSiteLaxMode
	}
	return c
}

func (h *AuthHandler) setRefreshCookie(w http.ResponseWriter, tokens service.Tokens) {
	c := h.cookieTemplate()
	c.Value = tokens.RefreshToken
	if tokens.RefreshExpiresIn > 0 {
		c.MaxAge = tokens.RefreshExpiresIn
		c.Expires = time.Now().Add(time.Duration(tokens.RefreshExpiresIn) * time.Second)
	}
	http.SetCookie(w, c)
}

func (h *AuthHandler) clearRefreshCookie(w http.ResponseWriter) {
	c := h.cookieTemplate()
	c.Value = ""
	c.MaxAge = -1
	c.Expires = time.Unix(1, 0)
	http.SetCookie(w, c)
}

// Cookie-authenticated state changes require an explicitly permitted browser Origin.
// Non-browser clients must also send an Origin header for these endpoints.
func (h *AuthHandler) trustedOrigin(r *http.Request) bool {
	origin := r.Header.Get("Origin")
	if origin == "" {
		return false
	}
	for _, allowed := range h.security.AllowedOrigins {
		if origin == allowed {
			return true
		}
	}
	return false
}

// Middleware handles cross-origin browser requests using an explicit origin allowlist.
func CORSMiddleware(allowedOrigins []string) func(http.Handler) http.Handler {
	allowed := make(map[string]bool, len(allowedOrigins))
	for _, origin := range allowedOrigins {
		allowed[origin] = true
	}
	return func(next http.Handler) http.Handler {
		return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			origin := r.Header.Get("Origin")
			if origin != "" {
				w.Header().Add("Vary", "Origin")
				if allowed[origin] {
					w.Header().Set("Access-Control-Allow-Origin", origin)
					w.Header().Set("Access-Control-Allow-Credentials", "true")
				}
			}
			if r.Method == http.MethodOptions && r.Header.Get("Access-Control-Request-Method") != "" {
				if origin == "" || !allowed[origin] {
					w.WriteHeader(http.StatusForbidden)
					return
				}
				w.Header().Set("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
				w.Header().Set("Access-Control-Allow-Headers", "Content-Type")
				w.Header().Set("Vary", "Origin, Access-Control-Request-Method, Access-Control-Request-Headers")
				w.WriteHeader(http.StatusNoContent)
				return
			}
			next.ServeHTTP(w, r)
		})
	}
}
