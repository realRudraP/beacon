package api

import (
	"time"

	"github.com/jackc/pgx/v5/pgtype"
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
