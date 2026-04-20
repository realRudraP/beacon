package token_test

import (
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

const (
	JWT_TEST_SECRET = "12345678901234567890123456789012"
)

func TestJWTMaker(t *testing.T) {
	maker := token.NewJWTMaker(JWT_TEST_SECRET)

	t.Run("valid token contains correct claims", func(t *testing.T) {
		claims := token.Claims{
			Username:  "alice",
			UserID:    generateUUID(),
			TokenType: "access",
			Role:      "worker",
		}
		tokenStr, err := maker.CreateToken(claims, 15*time.Minute)
		if err != nil {
			t.Fatalf("failed to create token: %v", err)
		}

		verifiedClaims, err := maker.VerifyToken(tokenStr)
		if err != nil {
			t.Fatalf("failed to verify token: %v", err)
		}

		assertClaimsEqual(t, &claims, verifiedClaims)
	})

	t.Run("expired token returns error", func(t *testing.T) {
		claims := token.Claims{
			Username:  "bob",
			UserID:    generateUUID(),
			TokenType: "access",
			Role:      "worker",
		}

		tokenStr, err := maker.CreateToken(claims, -1)
		if err != nil {
			t.Fatalf("failed to create token: %v", err)
		}

		_, err = maker.VerifyToken(tokenStr)
		if err == nil {
			t.Fatalf("expected error for expired token, got nil")
		}
	})

	t.Run("invalid token returns error", func(t *testing.T) {
		_, err := maker.VerifyToken("invalid.token.string")
		if err == nil {
			t.Fatalf("expected error for invalid token, got nil")
		}
	})

	t.Run("token with wrong secret returns error", func(t *testing.T) {
		claims := token.Claims{
			Username:  "charlie",
			UserID:    generateUUID(),
			TokenType: "access",
			Role:      "worker",
		}

		wrongMaker := token.NewJWTMaker("absolutelywrong")
		wrongToken, err := wrongMaker.CreateToken(claims, 15*time.Minute)
		if err != nil {
			t.Fatalf("failed to create token with wrong maker: %v", err)
		}
		_, err = maker.VerifyToken(wrongToken)
		if err == nil {
			t.Fatalf("expected error for invalid token, got nil")
		}
	})

	t.Run("tampered token returns error", func(t *testing.T) {
		claims := token.Claims{
			Username:  "dave",
			UserID:    generateUUID(),
			TokenType: "access",
			Role:      "worker",
		}

		tokenStr, err := maker.CreateToken(claims, 15*time.Minute)
		if err != nil {
			t.Fatalf("failed to create token: %v", err)
		}

		// Tamper with the token by changing a character
		tamperedToken := tokenStr[:len(tokenStr)-1] + "x"

		_, err = maker.VerifyToken(tamperedToken)
		if err == nil {
			t.Fatalf("expected error for tampered token, got nil")
		}
	})

}
func assertClaimsEqual(t *testing.T, expected, actual *token.Claims) {
	t.Helper()
	if expected.Username != actual.Username {
		t.Errorf("expected username %s, got %s", expected.Username, actual.Username)
	}
	if expected.UserID != actual.UserID {
		t.Errorf("expected user ID %s, got %s", expected.UserID, actual.UserID)
	}
	if expected.TokenType != actual.TokenType {
		t.Errorf("expected token type %s, got %s", expected.TokenType, actual.TokenType)
	}
	if expected.Role != actual.Role {
		t.Errorf("expected role %s, got %s", expected.Role, actual.Role)
	}
}

func generateUUID() pgtype.UUID {
	return pgtype.UUID{Bytes: uuid.New(), Valid: true}
}
