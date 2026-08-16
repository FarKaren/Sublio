package main

import (
	"api-gateway/internal/config"
	"api-gateway/internal/openapi"
	"api-gateway/logger/sl"
	"fmt"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"

	"github.com/go-chi/chi/v5"
	"github.com/swaggest/swgui"
	swguiv5 "github.com/swaggest/swgui/v5emb"
)

const (
	envLocal = "local"
	envProd  = "prod"
	envDev   = "dev"
)

func main() {
	cfg := config.MustLoad()

	log := setupLogger(cfg.Env)

	log.Info("Configuration loaded successfully", "config", cfg)

	router := chi.NewRouter()

	docsHandler := swguiv5.NewWithConfig(swgui.Config{
		ShowTopBar: true,
		SettingsUI: map[string]string{
			"urls": `[
               {"name": "Auth Service", "url": "/api/openapi/auth-service.json"},
               {"name": "Job Service", "url": "/api/openapi/job-service.json"},
               {"name": "Media Service", "url": "/api/openapi/media-service.json"},
               {"name": "Subtitle Service", "url": "/api/openapi/subtitle-service.json"}
           ]`,
		},
	})

	router.Mount("/api/docs", docsHandler(
		"Sublio API",
		"/api/openapi/auth-service.json",
		"/api/docs",
	))

	router.Get("/api/openapi/{service}.json", func(w http.ResponseWriter, r *http.Request) {
		service := chi.URLParam(r, "service")
		data, err := openapi.Specs.ReadFile(filepath.Join("specs", service+".json"))
		if err != nil {
			http.NotFound(w, r)
			return
		}

		w.Header().Set("Content-Type", "application/json")
		_, err = w.Write(data)
		if err != nil {
			log.Error("Failed to write response", "err", err)
		}
	})

	srv := &http.Server{
		Addr:         cfg.HttpServer.Address,
		Handler:      router,
		ReadTimeout:  cfg.HttpServer.Timeout,
		WriteTimeout: cfg.HttpServer.Timeout,
		IdleTimeout:  cfg.HttpServer.IdleTimeout,
	}

	if err := srv.ListenAndServe(); err != nil {
		log.Error("Server error", sl.Err(err))
	}

	log.Error("Server stopped unexpectedly")

}

func setupLogger(env string) *slog.Logger {
	var log *slog.Logger

	fmt.Printf("env = %q, envLocal = %q, equal = %v\n", env, envLocal, env == envLocal)

	switch env {
	case envLocal:
		log = slog.New(
			slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}),
		)
	case envDev:
		log = slog.New(
			slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}),
		)
	case envProd:
		log = slog.New(
			slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}),
		)
	default: // If env config is invalid, set prod settings by default due to security
		log = slog.New(
			slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}),
		)
	}

	return log
}
