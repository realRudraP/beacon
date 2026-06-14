package api

import (
	"context"

	"github.com/google/uuid"
	database "gitlab.com/realRudraP/beacon/backend/internal/database"
)

type Store interface {
	//users
	CreateSuperAdmin(ctx context.Context, arg database.CreateSuperAdminParams) (database.User, error)
	CreateUser(ctx context.Context, arg database.CreateUserParams) (database.User, error)
	GetUserByUsername(ctx context.Context, username string) (database.User, error)
	GetUserByID(ctx context.Context, id uuid.UUID) (database.User, error)
	UpdateLastLogin(ctx context.Context, id uuid.UUID) error
	CheckSetupRequired(ctx context.Context) (bool, error)
	GetAllUsers(ctx context.Context) ([]database.User, error)
	UpdateUser(ctx context.Context, arg database.UpdateUserParams) (database.User, error)

	//refresh tokens
	CreateRefreshToken(ctx context.Context, arg database.CreateRefreshTokenParams) (database.RefreshToken, error)
	GetRefreshToken(ctx context.Context, tokenHash string) (database.RefreshToken, error)
	RevokeRefreshToken(ctx context.Context, arg database.RevokeRefreshTokenParams) error
	RevokeAllUserRefreshTokens(ctx context.Context, arg database.RevokeAllUserRefreshTokensParams) error
	DeleteRefreshToken(ctx context.Context, tokenHash string) error
	CreateVerificationToken(ctx context.Context, arg database.CreateVerificationTokenParams) (database.VerificationToken, error)

	//Geofences
	CreateGeofence(ctx context.Context, arg database.CreateGeofenceParams) (database.Geofence, error)
	GetAllGeofences(ctx context.Context) ([]database.Geofence, error)
	UpdateGeofence(ctx context.Context, arg database.UpdateGeofenceParams) (database.Geofence, error)
	DeleteGeofence(ctx context.Context, id uuid.UUID) error

	// Audit Logs
	CreateAuditLog(ctx context.Context, arg database.CreateAuditLogParams) (database.AuditLog, error)
	GetAllAuditLogs(ctx context.Context) ([]database.AuditLog, error)

	//Configuration
	GetAllConfiguration(ctx context.Context) ([]database.Configuration, error)
	GetSyncConfigByKey(ctx context.Context, key string) (database.Configuration, error)
	UpsertSyncConfig(ctx context.Context, arg database.UpsertSyncConfigParams) (database.Configuration, error)
}
