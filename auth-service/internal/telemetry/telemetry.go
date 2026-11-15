package telemetry

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"auth-service/internal/config"

	"github.com/prometheus/client_golang/prometheus"
	"github.com/prometheus/client_golang/prometheus/promhttp"
	"go.opentelemetry.io/otel"
	"go.opentelemetry.io/otel/attribute"
	"go.opentelemetry.io/otel/exporters/otlp/otlpmetric/otlpmetrichttp"
	"go.opentelemetry.io/otel/exporters/otlp/otlptrace/otlptracehttp"
	otelprom "go.opentelemetry.io/otel/exporters/prometheus"
	"go.opentelemetry.io/otel/propagation"
	sdkmetric "go.opentelemetry.io/otel/sdk/metric"
	"go.opentelemetry.io/otel/sdk/resource"
	sdktrace "go.opentelemetry.io/otel/sdk/trace"
	"go.opentelemetry.io/otel/trace"
)

type Runtime struct {
	PrometheusHandler http.Handler
	MetricsHandler    http.Handler
	shutdownFns       []func(context.Context) error
}

func (r *Runtime) Shutdown(ctx context.Context) error {
	var errs []error
	for i := len(r.shutdownFns) - 1; i >= 0; i-- {
		if err := r.shutdownFns[i](ctx); err != nil {
			errs = append(errs, err)
		}
	}
	return errors.Join(errs...)
}

func Init(ctx context.Context, cfg config.TelemetryConfig, exposePrometheus bool) (*Runtime, error) {
	result := &Runtime{}
	res := resource.NewSchemaless(attribute.String("service.name", cfg.ServiceName))

	otel.SetTextMapPropagator(propagation.NewCompositeTextMapPropagator(
		propagation.TraceContext{},
		propagation.Baggage{},
	))

	if cfg.Tracing.Enabled {
		traceURL, err := signalURL(cfg.OTLP.Endpoint, "traces")
		if err != nil {
			return nil, err
		}
		exporter, err := otlptracehttp.New(ctx, otlptracehttp.WithEndpointURL(traceURL))
		if err != nil {
			return nil, fmt.Errorf("create OTLP traces exporter: %w", err)
		}
		provider := sdktrace.NewTracerProvider(
			sdktrace.WithResource(res),
			sdktrace.WithSampler(sdktrace.ParentBased(
				sdktrace.TraceIDRatioBased(cfg.Tracing.SamplingProbability),
			)),
			sdktrace.WithBatcher(exporter),
		)
		otel.SetTracerProvider(provider)
		result.shutdownFns = append(result.shutdownFns, provider.Shutdown)
	}

	var readers []sdkmetric.Reader
	if cfg.Metrics.Exporter == "otlp" {
		metricURL, err := signalURL(cfg.OTLP.Endpoint, "metrics")
		if err != nil {
			_ = result.Shutdown(ctx)
			return nil, err
		}
		exporter, err := otlpmetrichttp.New(ctx, otlpmetrichttp.WithEndpointURL(metricURL))
		if err != nil {
			_ = result.Shutdown(ctx)
			return nil, fmt.Errorf("create OTLP metrics exporter: %w", err)
		}
		readers = append(readers, sdkmetric.NewPeriodicReader(exporter,
			sdkmetric.WithInterval(cfg.Metrics.Interval),
		))
	}

	if exposePrometheus {
		registry := prometheus.NewRegistry()
		exporter, err := otelprom.New(otelprom.WithRegisterer(registry))
		if err != nil {
			_ = result.Shutdown(ctx)
			return nil, fmt.Errorf("create prometheus exporter: %w", err)
		}
		readers = append(readers, exporter)
		result.PrometheusHandler = promhttp.HandlerFor(registry, promhttp.HandlerOpts{})
		result.MetricsHandler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			families, err := registry.Gather()
			if err != nil {
				http.Error(w, "metrics unavailable", http.StatusInternalServerError)
				return
			}
			names := make([]string, 0, len(families))
			for _, family := range families {
				names = append(names, family.GetName())
			}
			sort.Strings(names)
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(struct {
				Names []string `json:"names"`
			}{Names: names})
		})
	}

	if len(readers) > 0 {
		options := []sdkmetric.Option{sdkmetric.WithResource(res)}
		for _, reader := range readers {
			options = append(options, sdkmetric.WithReader(reader))
		}
		provider := sdkmetric.NewMeterProvider(options...)
		otel.SetMeterProvider(provider)
		result.shutdownFns = append(result.shutdownFns, provider.Shutdown)
	}
	return result, nil
}

// Config endpoint is a base URL; exporters need signal-specific URLs.
func signalURL(base, signal string) (string, error) {
	u, err := url.Parse(base)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return "", fmt.Errorf("invalid OTLP base URL: %q", base)
	}
	u.Path = strings.TrimRight(u.Path, "/") + "/v1/" + signal
	return u.String(), nil
}

// TraceHandler correlates ordinary slog records with the active OTEL span.
// It exports no OTLP logs: the existing slog handler writes them to stdout.
type TraceHandler struct{ next slog.Handler }

func NewTraceHandler(next slog.Handler) slog.Handler {
	return TraceHandler{next: next}
}

func (h TraceHandler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.next.Enabled(ctx, level)
}

func (h TraceHandler) Handle(ctx context.Context, record slog.Record) error {
	sc := trace.SpanContextFromContext(ctx)
	if sc.IsValid() {
		record.AddAttrs(
			slog.String("trace_id", sc.TraceID().String()),
			slog.String("span_id", sc.SpanID().String()),
		)
	}
	return h.next.Handle(ctx, record)
}

func (h TraceHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return TraceHandler{next: h.next.WithAttrs(attrs)}
}

func (h TraceHandler) WithGroup(name string) slog.Handler {
	return TraceHandler{next: h.next.WithGroup(name)}
}
