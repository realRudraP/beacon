package token

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
)

var (
	ErrExpiredToken = errors.New("token has expired")
	ErrInvalidToken = errors.New("token is invalid")
)

const (
	JWT_ISSUER = "beacon"
)

type JWTMaker struct {
	secretKey []byte
}

type jwtClaims struct {
	Username  string      `json:"username"`
	UserId    pgtype.UUID `json:"user_id"`
	TokenType string      `json:"token_type"`
	Role      string      `json:"role"`
	jwt.RegisteredClaims
}

func NewJWTMaker(secretKey string) *JWTMaker {
	return &JWTMaker{secretKey: []byte(secretKey)}
}

func (m *JWTMaker) CreateToken(claims Claims, duration time.Duration) (string, error) {
	internal := jwtClaims{
		Username:  claims.Username,
		UserId:    claims.UserID,
		TokenType: claims.TokenType,
		Role:      claims.Role,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(duration)),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			Issuer:    JWT_ISSUER,
			ID:        uuid.New().String(),
		},
	}

	token := jwt.NewWithClaims(jwt.SigningMethodHS256, internal)
	return token.SignedString(m.secretKey)
}

func (m *JWTMaker) VerifyToken(tokenStr string) (*Claims, error) {
	token, err := jwt.ParseWithClaims(tokenStr, &jwtClaims{}, func(t *jwt.Token) (any, error) {
		if _, ok := t.Method.(*jwt.SigningMethodHMAC); !ok {
			return nil, ErrInvalidToken
		}
		return m.secretKey, nil
	})
	if err != nil {
		if errors.Is(err, jwt.ErrTokenExpired) {
			return nil, ErrExpiredToken
		}
		return nil, ErrInvalidToken
	}

	internal, ok := token.Claims.(*jwtClaims)
	if !ok || !token.Valid {
		return nil, ErrInvalidToken
	}

	return &Claims{
		Username:  internal.Username,
		UserID:    internal.UserId,
		TokenType: internal.TokenType,
		Role:      internal.Role,
	}, nil
}
