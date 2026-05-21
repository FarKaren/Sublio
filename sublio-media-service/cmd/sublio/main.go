package sublio

import (
	"log/slog"
	"os"
	"sublio-media-service/config"
)

const (
	envLocal = "LOCAL"
	envProd  = "PROD"
	envDev   = "DEV"
)

func main() {

	cfg := config.MustLoad()
	log := setupLogger(cfg.Env)

	log.Info("Configuration successfully loaded", slog.String("env", cfg.Env))

}

func setupLogger(env string) *slog.Logger {
	var log *slog.Logger

	switch env {
	case envLocal:
		log = slog.New(slog.NewTextHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}))
	case envProd:
		log = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	case envDev:
		log = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelDebug}))
	default:
		log = slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	}

	return log
}
