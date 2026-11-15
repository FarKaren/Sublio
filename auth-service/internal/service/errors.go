package service

import "errors"

var (
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrUserAlreadyExists  = errors.New("user already exists")
	ErrSessionExpired     = errors.New("session expired")
	ErrUnauthorized       = errors.New("no session")
	ErrUnavailable        = errors.New("authentication provider unavailable")
	ErrInvalidInput       = errors.New("invalid input")
)
