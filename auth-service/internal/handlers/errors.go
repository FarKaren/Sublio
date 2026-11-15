package handlers

import (
	"context"
	"encoding/json"
	"errors"
	"log/slog"
	"net/http"

	"auth-service/internal/api"
	"auth-service/internal/service"

	"go.opentelemetry.io/otel/trace"
)

func traceID(ctx context.Context) *string {
	sc := trace.SpanFromContext(ctx).SpanContext()
	if !sc.HasTraceID() {
		return nil
	}
	id := sc.TraceID().String()
	return &id
}

func writeJSON(w http.ResponseWriter, r *http.Request, log *slog.Logger, status int, payload any) {
	body, err := json.Marshal(payload)
	if err != nil {
		log.ErrorContext(r.Context(), "marshal response", "error", err)
		w.WriteHeader(http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	if _, err := w.Write(append(body, '\n')); err != nil {
		log.ErrorContext(r.Context(), "write response", "error", err)
	}
}

func writeError(w http.ResponseWriter, r *http.Request, log *slog.Logger, status int, code, message string) {
	writeJSON(w, r, log, status, api.ErrorResponse{
		Code:    code,
		Message: message,
		TraceId: traceID(r.Context()),
	})
}

func mapServiceError(err error) (status int, code, message string) {
	switch {
	case errors.Is(err, service.ErrInvalidCredentials):
		return http.StatusUnauthorized, "INVALID_CREDENTIALS", "Invalid email or password"
	case errors.Is(err, service.ErrUserAlreadyExists):
		return http.StatusConflict, "EMAIL_TAKEN", "User already exists"
	case errors.Is(err, service.ErrSessionExpired):
		return http.StatusUnauthorized, "SESSION_EXPIRED", "Session has expired"
	case errors.Is(err, service.ErrUnauthorized):
		return http.StatusUnauthorized, "NO_SESSION", "Authentication required"
	case errors.Is(err, service.ErrInvalidInput):
		return http.StatusBadRequest, "INVALID_REQUEST", "Invalid request"
	case errors.Is(err, service.ErrUnavailable):
		return http.StatusServiceUnavailable, "AUTH_UNAVAILABLE", "Authentication temporarily unavailable"
	default:
		return http.StatusInternalServerError, "INTERNAL_ERROR", "Internal server error"
	}
}
