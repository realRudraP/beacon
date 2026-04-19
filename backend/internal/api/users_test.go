package api_test

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"gitlab.com/realRudraP/beacon/backend/internal/api"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

const (
	registerUserPath = "/api/v1/users/register"
)

var _ api.Store = (*InMemoryStore)(nil)

type InMemoryStore struct {
	users map[string]database.User
}

func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{users: make(map[string]database.User)}
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

func newTestServer(t *testing.T) (*api.Server, *InMemoryStore) {
	t.Helper()
	store := NewInMemoryStore()
	server := api.NewTestServer(store)
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
