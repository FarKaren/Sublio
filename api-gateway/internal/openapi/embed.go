package openapi

import "embed"

//go:embed specs/*.json
var Specs embed.FS
