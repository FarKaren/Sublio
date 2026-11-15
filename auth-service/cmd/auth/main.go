package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"auth-service/internal/api"
	"auth-service/internal/config"
	"auth-service/internal/handlers"
	"auth-service/internal/keycloak"
	"auth-service/internal/secrets"
	"auth-service/internal/service"
	"auth-service/internal/telemetry"

	"github.com/go-chi/chi/v5"
	"github.com/go-chi/chi/v5/middleware"
	"go.opentelemetry.io/contrib/instrumentation/net/http/otelhttp"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, "auth-service:", err)
		os.Exit(1)
	}
}

func run() error {
	cfg := config.MustLoad()
	log := setupLogger(cfg.Env)
	clientSecret, err := secrets.LoadKeycloakClientSecret(context.Background(), cfg.Vault)
	if err != nil {
		return fmt.Errorf("load startup secrets: %w", err)
	}
	cfg.Keycloak.ClientSecret = clientSecret

	otelRuntime, err := telemetry.Init(context.Background(), cfg.Telemetry, cfg.Management.PrometheusEnabled)
	if err != nil {
		return fmt.Errorf("init telemetry: %w", err)
	}
	defer func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := otelRuntime.Shutdown(ctx); err != nil {
			log.Error("shutdown telemetry", "error", err)
		}
	}()

	kc := keycloak.NewClient(cfg.Keycloak)
	authService := service.NewAuthService(kc)
	authHandler := handlers.NewAuthHandler(authService, log, cfg.Security)

	r := chi.NewRouter()
	r.Use(middleware.Recoverer)
	r.Use(handlers.CORSMiddleware(cfg.Security.AllowedOrigins))

	// Register health/info/metrics on the same listener (as in the Spring example).
	r.Get(cfg.Management.HealthPath, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"status":"UP"}`))
	})
	r.Get(cfg.Management.InfoPath, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_, _ = fmt.Fprintf(w, `{"service":%q,"env":%q}`+"\n", cfg.Telemetry.ServiceName, cfg.Env)
	})
	if cfg.Management.PrometheusEnabled {
		r.Handle(cfg.Management.PrometheusPath, otelRuntime.PrometheusHandler)
		r.Handle(cfg.Management.MetricsPath, otelRuntime.MetricsHandler)
	}

	// api.HandlerFromMux creates ServerInterfaceWrapper internally and registers four POST routes.
	api.HandlerFromMux(authHandler, r)

	// Traces + HTTP metrics. Do not instrument management endpoints to avoid scrape noise.
	httpHandler := otelhttp.NewHandler(r, "http.server", otelhttp.WithFilter(func(req *http.Request) bool {
		p := req.URL.Path
		return p != cfg.Management.HealthPath && p != cfg.Management.InfoPath && p != cfg.Management.MetricsPath && p != cfg.Management.PrometheusPath
	}))

	server := &http.Server{
		Addr:              cfg.HTTPServer.Address,
		Handler:           httpHandler,
		ReadTimeout:       cfg.HTTPServer.ReadTimeout,
		ReadHeaderTimeout: cfg.HTTPServer.ReadHeaderTimeout,
		WriteTimeout:      cfg.HTTPServer.WriteTimeout,
		IdleTimeout:       cfg.HTTPServer.IdleTimeout,
	}

	serverErrors := make(chan error, 1)
	go func() {
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErrors <- err
		}
	}()

	log.Info("auth-service started", "address", cfg.HTTPServer.Address, "env", cfg.Env)
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	select {
	case <-ctx.Done():
		log.Info("shutdown signal received")
	case err := <-serverErrors:
		return fmt.Errorf("http server: %w", err)
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), cfg.HTTPServer.ShutdownTimeout)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		return fmt.Errorf("http server shutdown: %w", err)
	}
	return nil
}

func setupLogger(env string) *slog.Logger {
	level := slog.LevelInfo
	if env == "local" || env == "dev" {
		level = slog.LevelDebug
	}
	options := &slog.HandlerOptions{Level: level}
	var handler slog.Handler
	if env == "local" {
		handler = slog.NewTextHandler(os.Stdout, options)
	} else {
		handler = slog.NewJSONHandler(os.Stdout, options)
	}
	return slog.New(telemetry.NewTraceHandler(handler))
}
