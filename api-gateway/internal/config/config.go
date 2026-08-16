package config

import (
	"log"
	"os"
	"time"

	"github.com/ilyakaznacheev/cleanenv"
)

type HttpServer struct {
	Address     string        `yaml:"address" env-default:"localhost:8080"`
	Timeout     time.Duration `yaml:"timeout" env-default:"3s"`
	IdleTimeout time.Duration `yaml:"idle_timeout" env-default:"60s"`
}

type Config struct {
	Env        string     `yaml:"env" env-default:"local" env-required:"true"`
	HttpServer HttpServer `yaml:"http_server" env-default:""`
}

func MustLoad() *Config {
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		log.Fatal("CONFIG_PATH environment variable is not set")
	}

	if _, err := os.Stat(configPath); os.IsNotExist(err) {
		log.Fatalf("config file does not exist: %s", configPath)
	}

	var config Config

	if err := cleanenv.ReadConfig(configPath, &config); err != nil {
		log.Fatalf("failed to read config file: %v", err)
	}

	return &config
}
