package token

import (
	"time"

	"github.com/google/uuid"
)

type Claims struct {
	Username  string    `json:"username"`
	UserId    uuid.UUID `json:"user_id"`
	TokenType string    `json:"token_type"`
	Role      string    `json:"role"`
}

type Maker interface {
	CreateToken(claims Claims, duration time.Duration) (string, error)
	VerifyToken(token string) (*Claims, error)
}
