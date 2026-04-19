package api_test

import (
	"context"
	"errors"
	"testing"

	"gitlab.com/realRudraP/beacon/backend/internal/api"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

type InMemoryUserStore struct {
	users map[string]database.User
}

func NewInMemoryUserStore() *InMemoryUserStore {
	return &InMemoryUserStore{users: make(map[string]database.User)}
}

func (s *InMemoryUserStore) CreateUser(ctx context.Context, arg database.CreateUserParams) (database.User, error) {
	if _, exists := s.users[arg.Username]; exists {
		return database.User{}, api.ErrUsernameTaken
	}
	user := database.User{
		Username:     arg.Username,
		FullName:     arg.FullName,
		PhoneNumber:  arg.PhoneNumber,
		PasswordHash: arg.PasswordHash,
	}
	s.users[arg.Username] = user
	return user, nil
}

func (s *InMemoryUserStore) GetUserByUsername(ctx context.Context, username string) (database.User, error) {
	user, exists := s.users[username]
	if !exists {
		return database.User{}, api.ErrUserNotFound
	}
	return user, nil
}

func TestUserRegistrationFlow(t *testing.T) {
	// A shared datastore is implemented for common use throughout
	// all the tests
	datastore := NewInMemoryUserStore()
	t.Run("check if user is able to register", func(t *testing.T) {
		ctx := context.Background()
		user := database.CreateUserParams{
			Username:     "alice",
			FullName:     "Alice Smith",
			PasswordHash: "very_secure_passwords_hash",
			PhoneNumber:  "999999999",
		}
		_, err := datastore.CreateUser(ctx, user)
		if err != nil {
			t.Fatalf("Error in registration of user: %v", err)
		}
	})

	t.Run("check that duplicate username raises error", func(t *testing.T) {
		ctx := context.Background()
		user := database.CreateUserParams{
			Username:    "alice",
			FullName:    "Alice May",
			PhoneNumber: "8888888888",
		}
		_, err := datastore.CreateUser(ctx, user)
		if !errors.Is(err, api.ErrUsernameTaken) {
			t.Fatal("Expected ErrUsernameTaken when registering duplicate username")
		}
	})

	t.Run("get existing user", func(t *testing.T) {
		ctx := context.Background()
		user, err := datastore.GetUserByUsername(ctx, "alice")
		if err != nil {
			t.Fatalf("expected user, got error: %v", err)
		}
		if user.Username != "alice" {
			t.Fatalf("expected username alice, got %s", user.Username)
		}
	})

	t.Run("get non-existent user fails", func(t *testing.T) {
		ctx := context.Background()
		_, err := datastore.GetUserByUsername(ctx, "ghost")
		if !errors.Is(err, api.ErrUserNotFound) {
			t.Fatalf("expected ErrUserNotFound but got: %v", err)
		}
	})
}
