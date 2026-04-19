package api

import (
	"context"

	database "gitlab.com/realRudraP/beacon/backend/internal/database"
)

type Store interface {
	CreateUser(ctx context.Context, arg database.CreateUserParams) (database.User, error)
	GetUserByUsername(ctx context.Context, username string) (database.User, error)
}
