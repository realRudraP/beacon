package api

import (
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

func convertTimeToPGTimeTz(t time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{
		Time:  t,
		Valid: true,
	}
}
func convertFloat64ToPGFloat8(f float64) pgtype.Float8 {
	return pgtype.Float8{
		Float64: f,
		Valid:   true,
	}
}

func optionalFloat64ToPGFloat8(f float64, present bool) pgtype.Float8 {
	if !present {
		return pgtype.Float8{}
	}
	return pgtype.Float8{Float64: f, Valid: true}
}

func stringToPGText(s string) pgtype.Text {
	if s == "" {
		return pgtype.Text{}
	}
	return pgtype.Text{String: s, Valid: true}
}
func convertTimeToPGDate(t time.Time) pgtype.Date {
	return pgtype.Date{Time: t, Valid: true}
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

// Helper to convert time.Time to pgtype.Timestamptz safely
func optionalTimeToPGTimeTz(t time.Time, valid bool) pgtype.Timestamptz {
	if !valid || t.IsZero() {
		return pgtype.Timestamptz{Valid: false}
	}
	return pgtype.Timestamptz{
		Time:  t,
		Valid: true,
	}
}
