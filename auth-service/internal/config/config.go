package config

import (
	"errors"
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"

	"github.com/ilyakaznacheev/cleanenv"
)

type HTTPServer struct {
	Address           string        `yaml:"address" env:"HTTP_ADDRESS" env-default:"localhost:8081"`
	ReadTimeout       time.Duration `yaml:"read_timeout" env-default:"10s"`
	ReadHeaderTimeout time.Duration `yaml:"read_header_timeout" env-default:"5s"`
	WriteTimeout      time.Duration `yaml:"write_timeout" env-default:"10s"`
	IdleTimeout       time.Duration `yaml:"idle_timeout" env-default:"60s"`
	ShutdownTimeout   time.Duration `yaml:"shutdown_timeout" env-default:"10s"`
}

type KeycloakConfig struct {
	URL            string        `yaml:"url" env:"KEYCLOAK_URL"`
	Realm          string        `yaml:"realm"`
	ClientID       string        `yaml:"client_id"`
	ClientSecret   string        `yaml:"-"`
	RequestTimeout time.Duration `yaml:"request_timeout" env-default:"5s"`
}

type VaultConfig struct {
	Address        string        `yaml:"address" env:"VAULT_ADDR" env-default:"http://localhost:8200"`
	RoleID         string        `yaml:"role_id" env:"VAULT_ROLE_ID"`
	SecretIDFile   string        `yaml:"secret_id_file" env:"VAULT_SECRET_ID_FILE" env-default:"/run/secrets/vault_secret_id"`
	RequestTimeout time.Duration `yaml:"request_timeout" env-default:"10s"`
}

type SecurityConfig struct {
	CookieName     string   `yaml:"cookie_name" env-default:"sublio_refresh"`
	CookieSecure   bool     `yaml:"cookie_secure" env:"COOKIE_SECURE"`
	CookieSameSite string   `yaml:"cookie_same_site" env-default:"lax"`
	AllowedOrigins []string `yaml:"allowed_origins"`
}

type ManagementConfig struct {
	HealthPath        string `yaml:"health_path" env-default:"/actuator/health"`
	MetricsPath       string `yaml:"metrics_path" env-default:"/actuator/metrics"`
	InfoPath          string `yaml:"info_path" env-default:"/actuator/info"`
	PrometheusPath    string `yaml:"prometheus_path" env-default:"/actuator/prometheus"`
	PrometheusEnabled bool   `yaml:"prometheus_enabled" env-default:"true"`
}

type TraceConfig struct {
	Enabled             bool    `yaml:"enabled" env:"TRACING_ENABLED" env-default:"true"`
	SamplingProbability float64 `yaml:"sampling_probability" env-default:"1"`
}

type OTLPConfig struct {
	Endpoint string `yaml:"endpoint" env:"OTLP_EXPORTER_ENDPOINT" env-default:"http://localhost:4318"`
	Protocol string `yaml:"protocol" env-default:"http/protobuf"`
}

type MetricsConfig struct {
	Exporter string        `yaml:"exporter" env:"METRICS_EXPORTER" env-default:"otlp"`
	Interval time.Duration `yaml:"interval" env-default:"15s"`
}

type LogsConfig struct {
	Exporter string `yaml:"exporter" env-default:"none"`
}

type TelemetryConfig struct {
	ServiceName string        `yaml:"service_name" env-default:"auth-service"`
	Tracing     TraceConfig   `yaml:"tracing"`
	OTLP        OTLPConfig    `yaml:"otlp"`
	Metrics     MetricsConfig `yaml:"metrics"`
	Logs        LogsConfig    `yaml:"logs"`
}

type Config struct {
	Env        string           `yaml:"env" env-default:"local"`
	HTTPServer HTTPServer       `yaml:"http_server"`
	Keycloak   KeycloakConfig   `yaml:"keycloak"`
	Vault      VaultConfig      `yaml:"vault"`
	Security   SecurityConfig   `yaml:"security"`
	Management ManagementConfig `yaml:"management"`
	Telemetry  TelemetryConfig  `yaml:"telemetry"`
}

func MustLoad() *Config {
	path := os.Getenv("CONFIG_PATH")
	if path == "" {
		panic("CONFIG_PATH is required")
	}
	var cfg Config
	if err := cleanenv.ReadConfig(path, &cfg); err != nil {
		panic(fmt.Errorf("read config: %w", err))
	}
	if err := cfg.Validate(); err != nil {
		panic(fmt.Errorf("invalid config: %w", err))
	}
	return &cfg
}

func (c *Config) Validate() error {
	if c.HTTPServer.Address == "" || c.HTTPServer.ReadTimeout <= 0 || c.HTTPServer.ReadHeaderTimeout <= 0 || c.HTTPServer.WriteTimeout <= 0 || c.HTTPServer.ShutdownTimeout <= 0 {
		return errors.New("invalid HTTP server settings")
	}
	if c.Keycloak.Realm == "" || c.Keycloak.ClientID == "" || c.Keycloak.URL == "" || c.Keycloak.RequestTimeout <= 0 {
		return errors.New("keycloak.url, realm, client_id and request_timeout are required")
	}
	u, err := url.Parse(c.Keycloak.URL)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return errors.New("keycloak.url must be an http(s) URL")
	}
	if err := c.Vault.Validate(); err != nil {
		return err
	}
	if c.Security.CookieName == "" || strings.ContainsAny(c.Security.CookieName, " ;,\t\n") {
		return errors.New("invalid cookie name")
	}
	switch c.Security.CookieSameSite {
	case "lax", "strict":
	case "none":
		if !c.Security.CookieSecure {
			return errors.New("SameSite=None requires Secure cookies")
		}
	default:
		return errors.New("cookie_same_site must be lax, strict, or none")
	}
	if c.Env == "prod" && !c.Security.CookieSecure {
		return errors.New("Secure cookie is required in prod")
	}
	if len(c.Security.AllowedOrigins) == 0 {
		return errors.New("security.allowed_origins is required for cookie-based refresh/logout CSRF protection")
	}
	for _, origin := range c.Security.AllowedOrigins {
		u, err := url.Parse(origin)
		if err != nil || u.Scheme == "" || u.Host == "" || u.Path != "" || u.RawQuery != "" || u.Fragment != "" || (u.Scheme != "http" && u.Scheme != "https") {
			return fmt.Errorf("invalid allowed origin: %q", origin)
		}
	}
	if c.Telemetry.ServiceName == "" {
		return errors.New("telemetry.service_name is required")
	}
	if c.Telemetry.Tracing.SamplingProbability < 0 || c.Telemetry.Tracing.SamplingProbability > 1 {
		return errors.New("tracing.sampling_probability must be between 0 and 1")
	}
	if c.Telemetry.OTLP.Protocol != "http/protobuf" {
		return errors.New("only http/protobuf is supported for OTLP")
	}
	if _, err := url.ParseRequestURI(c.Telemetry.OTLP.Endpoint); err != nil {
		return fmt.Errorf("invalid telemetry.otlp.endpoint: %w", err)
	}
	if c.Telemetry.Metrics.Exporter != "otlp" && c.Telemetry.Metrics.Exporter != "none" {
		return errors.New("metrics.exporter must be otlp or none")
	}
	if c.Telemetry.Metrics.Exporter == "otlp" && c.Telemetry.Metrics.Interval <= 0 {
		return errors.New("metrics.interval must be positive")
	}
	if c.Telemetry.Logs.Exporter != "none" {
		return errors.New("logs.exporter must be none (logs use slog)")
	}
	managementPaths := []string{c.Management.HealthPath, c.Management.InfoPath, c.Management.MetricsPath, c.Management.PrometheusPath}
	seen := make(map[string]bool, len(managementPaths))
	for _, path := range managementPaths {
		if !strings.HasPrefix(path, "/") || seen[path] {
			return errors.New("management paths must be absolute and unique")
		}
		seen[path] = true
	}
	return nil
}

func (c VaultConfig) Validate() error {
	u, err := url.Parse(c.Address)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
		return errors.New("vault.address must be an http(s) URL without credentials, query or fragment")
	}
	if strings.TrimSpace(c.RoleID) == "" || strings.TrimSpace(c.SecretIDFile) == "" || c.RequestTimeout <= 0 {
		return errors.New("vault.role_id (VAULT_ROLE_ID), secret_id_file and positive request_timeout are required")
	}
	return nil
}
