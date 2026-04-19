package api

import (
	"errors"
)

type APIError struct {
	Code    string            `json:"code"`
	Message string            `json:"message"`
	Fields  map[string]string `json:"fields,omitempty"`
}

const (
	ErrCodeInvalidRequest  = "INVALID_REQUEST"
	ErrCodeValidationError = "VALIDATION_ERROR"
	ErrCodeInternalError   = "INTERNAL_ERROR"
	ErrCodeConflict        = "CONFLICT"
	ErrUnauthorized        = "UNAUTHORIZED"
)

var (
	ErrUsernameTaken = errors.New("username already taken")
	ErrUserNotFound  = errors.New("no user found for the given identifier")
)
