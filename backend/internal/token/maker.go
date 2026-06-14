package token

import (
	"time"

	"github.com/google/uuid"
)

const (
	ACCESS_TOKEN  = "access"
	REFRESH_TOKEN = "refresh"

	ACCESS_TOKEN_DURATION = 15 * time.Minute
	// Long lived refresh token duration to account for cases when the
	// user might be on leave or not actively using the app.
	REFRESH_TOKEN_DURATION       = 120 * 24 * time.Hour
	ADMIN_REFRESH_TOKEN_DURATION = 7 * 24 * time.Hour
)

type Claims struct {
	Username  string    `json:"username"`
	UserID    uuid.UUID `json:"user_id"`
	TokenType string    `json:"token_type"`
	Role      string    `json:"role"`
}

type Maker interface {
	CreateToken(claims Claims, duration time.Duration) (string, error)
	VerifyToken(token string) (*Claims, error)
}
