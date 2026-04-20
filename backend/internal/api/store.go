package api

import (
	"context"

	"github.com/jackc/pgx/v5/pgtype"
	database "gitlab.com/realRudraP/beacon/backend/internal/database"
)

type Store interface {
	//users
	CreateUser(ctx context.Context, arg database.CreateUserParams) (database.User, error)
	GetUserByUsername(ctx context.Context, username string) (database.User, error)
	GetUserByID(ctx context.Context, id pgtype.UUID) (database.User, error)
	UpdateLastLogin(ctx context.Context, id pgtype.UUID) error

	//refresh tokens
	CreateRefreshToken(ctx context.Context, arg database.CreateRefreshTokenParams) (database.RefreshToken, error)
	GetRefreshToken(ctx context.Context, tokenHash string) (database.RefreshToken, error)
	RevokeRefreshToken(ctx context.Context, arg database.RevokeRefreshTokenParams) error
	RevokeAllUserRefreshTokens(ctx context.Context, arg database.RevokeAllUserRefreshTokensParams) error
	DeleteRefreshToken(ctx context.Context, tokenHash string) error
}
