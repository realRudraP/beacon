package api

import (
	"context"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
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

	//Attendance
	GetAttendanceRecordByID(ctx context.Context, id uuid.UUID) (database.AttendanceRecord, error)
	UpdateAttendanceRecord(ctx context.Context, arg database.UpdateAttendanceRecordParams) (database.AttendanceRecord, error)
	InsertAttendanceRecord(ctx context.Context, arg database.InsertAttendanceRecordParams) (database.AttendanceRecord, error)
	UpdateAttendancePhoto(ctx context.Context, arg database.UpdateAttendancePhotoParams) error
	GetAttendanceRecordsForUser(ctx context.Context, arg database.GetAttendanceRecordsForUserParams) ([]database.AttendanceRecord, error)
	GetAttendanceRecordsForDateRange(ctx context.Context, arg database.GetAttendanceRecordsForDateRangeParams) ([]database.AttendanceRecord, error)
	GetAttendanceRecordsForUserAndDateRange(ctx context.Context, arg database.GetAttendanceRecordsForUserAndDateRangeParams) ([]database.GetAttendanceRecordsForUserAndDateRangeRow, error)
	CountAttendanceRecordsForUser(ctx context.Context, userID uuid.UUID) (int64, error)
	CountAttendanceRecordsForDateRange(ctx context.Context, arg database.CountAttendanceRecordsForDateRangeParams) (int64, error)
	CountAttendanceRecordsForUserAndDateRange(ctx context.Context, arg database.CountAttendanceRecordsForUserAndDateRangeParams) (int64, error)
	GetAttendanceRecordsForDateRangeWithUser(ctx context.Context, arg database.GetAttendanceRecordsForDateRangeWithUserParams) ([]database.GetAttendanceRecordsForDateRangeWithUserRow, error)
	GetUsersWithoutAttendanceForDate(ctx context.Context, date pgtype.Date) ([]database.GetUsersWithoutAttendanceForDateRow, error)
	GetOpenAttendanceRecordsForDate(ctx context.Context, date pgtype.Date) ([]database.GetOpenAttendanceRecordsForDateRow, error)
	GetStaleOpenAttendanceRecords(ctx context.Context, createdAt pgtype.Timestamptz) ([]database.GetStaleOpenAttendanceRecordsRow, error)
	GetAttendanceRecordsByUserId(ctx context.Context, arg database.GetAttendanceRecordsByUserIdParams) ([]database.GetAttendanceRecordsByUserIdRow, error)

	// Location Checks
	InsertLocationCheck(ctx context.Context, arg database.InsertLocationCheckParams) (database.LocationCheck, error)
	GetLocationCheckByID(ctx context.Context, id uuid.UUID) (database.LocationCheck, error)
	ListLocationChecks(ctx context.Context, arg database.ListLocationChecksParams) ([]database.ListLocationChecksRow, error)
	CountLocationChecks(ctx context.Context, arg database.CountLocationChecksParams) (int64, error)

	// Cash Transactions
	GetCashTransactionsByUserID(ctx context.Context, userID uuid.UUID) ([]database.CashTransaction, error)
	GetCashBalanceByUserID(ctx context.Context, userID uuid.UUID) (float64, error)
	InsertCashTransaction(ctx context.Context, arg database.InsertCashTransactionParams) (database.CashTransaction, error)
	GetCashTransactionByID(ctx context.Context, id uuid.UUID) (database.CashTransaction, error)
	GetAllWorkersCashSummary(ctx context.Context) ([]database.GetAllWorkersCashSummaryRow, error)

	UpdateUserFCMToken(ctx context.Context, arg database.UpdateUserFCMTokenParams) error
	GetUserFCMToken(ctx context.Context, userID uuid.UUID) (pgtype.Text, error)
}
