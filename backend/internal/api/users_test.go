package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/api"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

const (
	registerUserPath   = "/api/v1/users/register"
	getUserProfilePath = "/api/v1/users/me"
	TEST_JWT_SECRET    = "12345678901234567890123456789012"
)

var _ api.Store = (*InMemoryStore)(nil)

type InMemoryStore struct {
	users         map[string]database.User
	refreshTokens map[string]database.RefreshToken
	tokenMaker    token.Maker
}

func NewInMemoryStore(tokenMaker token.Maker) *InMemoryStore {
	return &InMemoryStore{
		users:         make(map[string]database.User),
		refreshTokens: make(map[string]database.RefreshToken),
		tokenMaker:    tokenMaker,
	}
}

func (s *InMemoryStore) CreateUser(ctx context.Context, params database.CreateUserParams) (database.User, error) {
	if _, exists := s.users[params.Username]; exists {
		return database.User{}, api.ErrUsernameTaken
	}
	user := database.User{
		Username:     params.Username,
		FullName:     params.FullName,
		PhoneNumber:  params.PhoneNumber,
		PasswordHash: params.PasswordHash,
	}
	s.users[params.Username] = user
	return user, nil
}

func (s *InMemoryStore) GetUserByUsername(ctx context.Context, username string) (database.User, error) {
	user, exists := s.users[username]
	if !exists {
		return database.User{}, api.ErrUserNotFound
	}
	return user, nil
}

func (s *InMemoryStore) GetUserByID(ctx context.Context, id pgtype.UUID) (database.User, error) {
	for _, u := range s.users {
		if u.ID.String() == id.String() {
			return u, nil
		}
	}
	return database.User{}, api.ErrUserNotFound
}

func (s *InMemoryStore) UpdateLastLogin(ctx context.Context, id pgtype.UUID) error {
	// No op for testing. Only included to satisfy the Store interface.
	return nil
}

func (s *InMemoryStore) CreateRefreshToken(ctx context.Context, arg database.CreateRefreshTokenParams) (database.RefreshToken, error) {
	token := database.RefreshToken{
		TokenHash: arg.TokenHash,
		UserID:    arg.UserID,
		ExpiresAt: arg.ExpiresAt,
		Revoked:   false,
	}
	s.refreshTokens[arg.TokenHash] = token
	return token, nil
}

func (s *InMemoryStore) GetRefreshToken(ctx context.Context, tokenHash string) (database.RefreshToken, error) {
	token, exists := s.refreshTokens[tokenHash]
	if !exists {
		return database.RefreshToken{}, api.ErrTokenNotFound
	}
	return token, nil
}

func (s *InMemoryStore) RevokeRefreshToken(ctx context.Context, arg database.RevokeRefreshTokenParams) error {
	token, exists := s.refreshTokens[arg.TokenHash]
	if !exists {
		return api.ErrTokenNotFound
	}
	token.Revoked = true
	s.refreshTokens[arg.TokenHash] = token
	return nil
}

func (s *InMemoryStore) RevokeAllUserRefreshTokens(ctx context.Context, arg database.RevokeAllUserRefreshTokensParams) error {
	for hash, token := range s.refreshTokens {
		if token.UserID == arg.UserID {
			token.Revoked = true
			s.refreshTokens[hash] = token
		}
	}
	return nil
}

func (s *InMemoryStore) DeleteRefreshToken(ctx context.Context, tokenHash string) error {
	delete(s.refreshTokens, tokenHash)
	return nil
}

func (s *InMemoryStore) GetUserID(username string) pgtype.UUID {
	user, exists := s.users[username]
	if !exists {
		return pgtype.UUID{}
	}
	return user.ID
}

func newTestServer(t *testing.T) (*api.Server, *InMemoryStore) {
	t.Helper()
	tokenMaker := token.NewJWTMaker(TEST_JWT_SECRET)

	store := NewInMemoryStore(tokenMaker)
	server := api.NewTestServer(store, tokenMaker)
	return server, store
}

func postJSON(t *testing.T, handler http.Handler, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	b, err := json.Marshal(body)
	if err != nil {
		t.Fatalf("failed to marshal request body: %v", err)
	}
	req := httptest.NewRequest(http.MethodPost, path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)
	return rr
}
func decodeJSON(t *testing.T, rr *httptest.ResponseRecorder, dst any) {
	t.Helper()
	if err := json.NewDecoder(rr.Body).Decode(dst); err != nil {
		t.Fatalf("failed to decode response body: %v", err)
	}
}
func TestRegisterUser(t *testing.T) {
	server, _ := newTestServer(t)
	handler := server.Routes()

	t.Run("valid registration succeeds", func(t *testing.T) {
		rr := postJSON(t, handler, registerUserPath, map[string]string{
			"username":     "alice",
			"full_name":    "Alice Smith",
			"phone_number": "9999999999",
			"password":     "securepassword",
		})
		if rr.Code != http.StatusCreated {
			t.Fatalf("expected 201, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]string
		json.NewDecoder(rr.Body).Decode(&resp)
		if resp["username"] != "alice" {
			t.Fatalf("expected username alice, got %s", resp["username"])
		}
		if _, ok := resp["password"]; ok {
			t.Fatal("response must not contain password field")
		}
	})

	t.Run("duplicate username returns 409", func(t *testing.T) {
		rr := postJSON(t, handler, registerUserPath, map[string]string{
			"username":     "alice",
			"full_name":    "Alice Other",
			"phone_number": "8888888888",
			"password":     "anotherpassword",
		})
		if rr.Code != http.StatusConflict {
			t.Fatalf("expected 409, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("malformed JSON returns 400", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, registerUserPath, bytes.NewBufferString("{invalid}"))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", rr.Code)
		}
	})

	t.Run("missing required fields returns 400 with field errors", func(t *testing.T) {
		rr := postJSON(t, handler, registerUserPath, map[string]string{
			"username": "ab",
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", rr.Code)
		}
		var resp map[string]any
		json.NewDecoder(rr.Body).Decode(&resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		if _, ok := fields["username"]; !ok {
			t.Fatal("expected username field error")
		}
		if _, ok := fields["password"]; !ok {
			t.Fatal("expected password field error")
		}
	})
}

func TestGetUserProfile(t *testing.T) {
	server, store := newTestServer(t)
	handler := server.Routes()

	// pre-register alice
	postJSON(t, handler, registerUserPath, map[string]string{
		"username":     "alice",
		"full_name":    "Alice Smith",
		"phone_number": "9999999999",
		"password":     "securepassword",
	})

	// log in to get a real access token
	rr := postJSON(t, handler, loginUserPath, map[string]string{
		"username":    "alice",
		"password":    "securepassword",
		"device_info": "test device",
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("login failed: %s", rr.Body.String())
	}
	var loginResp map[string]any
	decodeJSON(t, rr, &loginResp)
	accessToken := loginResp["access_token"].(string)

	t.Run("valid token returns profile", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, getUserProfilePath, nil)
		req.Header.Set("Authorization", "Bearer "+accessToken)
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)

		if rr.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		if resp["username"] != "alice" {
			t.Fatalf("expected username alice, got %s", resp["username"])
		}
		if _, ok := resp["password_hash"]; ok {
			t.Fatal("response must not contain password_hash")
		}
	})

	t.Run("missing token returns 401", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodGet, getUserProfilePath, nil)
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)

		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rr.Code)
		}
	})

	t.Run("expired token returns 401", func(t *testing.T) {
		expiredToken, err := store.tokenMaker.CreateToken(token.Claims{
			Username:  "alice",
			UserID:    store.GetUserID("alice"),
			Role:      "worker",
			TokenType: token.ACCESS_TOKEN,
		}, -time.Minute)
		if err != nil {
			t.Fatalf("failed to create expired token: %v", err)
		}
		req := httptest.NewRequest(http.MethodGet, getUserProfilePath, nil)
		req.Header.Set("Authorization", "Bearer "+expiredToken)
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)

		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rr.Code)
		}
	})

	t.Run("refresh token rejected as access token", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"password":    "securepassword",
			"device_info": "test device",
		})
		var loginResp map[string]any
		decodeJSON(t, rr, &loginResp)
		refreshToken := loginResp["refresh_token"].(string)

		req := httptest.NewRequest(http.MethodGet, getUserProfilePath, nil)
		req.Header.Set("Authorization", "Bearer "+refreshToken)
		rr = httptest.NewRecorder()
		handler.ServeHTTP(rr, req)

		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d", rr.Code)
		}
	})
}
