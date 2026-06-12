package api

import (
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

func convertStringToPGText(s string) pgtype.Text {
	return pgtype.Text{
		String: s,
		Valid:  true,
	}
}
func convertTimeToPGTimeTz(t time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{
		Time:  t,
		Valid: true,
	}
}

func convertRoleStringToDBRole(roleStr string) database.Role {
	switch roleStr {
	case "worker":
		return database.RoleWorker
	case "admin":
		return database.RoleAdmin
	case "superadmin":
		return database.RoleSuperadmin
	default:
		return database.RoleWorker // default to worker if invalid role is provided
	}
}
