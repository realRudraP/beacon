package api

import (
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
)

const (
	defaultAttendanceLimit = 20
	maxAttendanceLimit     = 100
	dateLayout             = "02-01-2006"
)

// pgTextToString flattens a nullable pgtype.Text to a plain string for JSON responses.
func pgTextToString(t pgtype.Text) string {
	if !t.Valid {
		return ""
	}
	return t.String
}

type attendanceRecordResponse struct {
	ID                   uuid.UUID `json:"id"`
	UserID               uuid.UUID `json:"user_id"`
	Date                 string    `json:"date"`
	CheckIn              string    `json:"check_in,omitempty"`
	CheckInPhotoURI      string    `json:"check_in_photo_uri,omitempty"`
	CheckOut             string    `json:"check_out,omitempty"`
	CheckOutPhotoURI     string    `json:"check_out_photo_uri,omitempty"`
	LocationChecksCount  int64     `json:"location_checks_count"`
	LocationChecksWithin int64     `json:"location_checks_within"`
}

type attendanceWithUserResponse struct {
	ID                   uuid.UUID `json:"id"`
	UserID               uuid.UUID `json:"user_id"`
	FullName             string    `json:"full_name"`
	Email                string    `json:"email"`
	Date                 string    `json:"date"`
	CheckIn              string    `json:"check_in,omitempty"`
	CheckInPhotoURI      string    `json:"check_in_photo_uri,omitempty"`
	CheckOut             string    `json:"check_out,omitempty"`
	CheckOutPhotoURI     string    `json:"check_out_photo_uri,omitempty"`
	LocationChecksCount  int64     `json:"location_checks_count"`
	LocationChecksWithin int64     `json:"location_checks_within"`
}

func toAttendanceWithUserResponse(id, userID uuid.UUID, fullName string, email pgtype.Text, date pgtype.Date, checkIn, checkOut pgtype.Timestamptz, checkInPhoto, checkOutPhoto pgtype.Text, locationChecksCount, locationChecksWithin int64) attendanceWithUserResponse {
	resp := attendanceWithUserResponse{
		ID:                   id,
		UserID:               userID,
		FullName:             fullName,
		Email:                pgTextToString(email),
		CheckInPhotoURI:      pgTextToString(checkInPhoto),
		CheckOutPhotoURI:     pgTextToString(checkOutPhoto),
		LocationChecksCount:  locationChecksCount,
		LocationChecksWithin: locationChecksWithin,
	}
	if date.Valid {
		resp.Date = date.Time.Format(dateLayout)
	}
	if checkIn.Valid {
		resp.CheckIn = checkIn.Time.Format(time.RFC3339)
	}
	if checkOut.Valid {
		resp.CheckOut = checkOut.Time.Format(time.RFC3339)
	}
	return resp
}

func toAttendanceRecordResponse(rec database.GetAttendanceRecordsForUserAndDateRangeRow) attendanceRecordResponse {
	resp := attendanceRecordResponse{
		ID:                   rec.ID,
		UserID:               rec.UserID,
		CheckInPhotoURI:      pgTextToString(rec.CheckInPhotoUri),
		CheckOutPhotoURI:     pgTextToString(rec.CheckOutPhotoUri),
		LocationChecksCount:  rec.LocationChecksCount,
		LocationChecksWithin: rec.LocationChecksWithin,
	}
	if rec.Date.Valid {
		resp.Date = rec.Date.Time.Format(dateLayout)
	}
	if rec.CheckIn.Valid {
		resp.CheckIn = rec.CheckIn.Time.Format(time.RFC3339)
	}
	if rec.CheckOut.Valid {
		resp.CheckOut = rec.CheckOut.Time.Format(time.RFC3339)
	}
	return resp
}

// ---------------------------------------------------------------------------
// List attendance (admin) — filterable by user_id + date range, paginated
// GET /admin/attendance?user_id=&from=&to=&limit=&offset=
// ---------------------------------------------------------------------------

func (s *Server) handleListAttendance(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	fieldErrors := make(map[string]string)

	from, to, ok := parseDateRange(q.Get("from"), q.Get("to"), fieldErrors)
	limit, offset, ok2 := parsePagination(q.Get("limit"), q.Get("offset"), fieldErrors)
	if !ok || !ok2 || len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  fieldErrors,
		})
		return
	}

	userIDParam := q.Get("user_id")

	// Filtered by user + date range — no join, so no full_name/email.
	if userIDParam != "" {
		userID, err := uuid.Parse(userIDParam)
		if err != nil {
			s.writeAPIError(w, http.StatusBadRequest, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "invalid request payload",
				Fields:  map[string]string{"user_id": "user_id must be a valid UUID"},
			})
			return
		}

		records, err := s.store.GetAttendanceRecordsForUserAndDateRange(r.Context(), database.GetAttendanceRecordsForUserAndDateRangeParams{
			UserID: userID,
			Date:   from,
			Date_2: to,
			Limit:  limit,
			Offset: offset,
		})
		if err != nil {
			s.logger.Error("failed to fetch attendance records", "err", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
			return
		}

		total, err := s.store.CountAttendanceRecordsForUserAndDateRange(r.Context(), database.CountAttendanceRecordsForUserAndDateRangeParams{
			UserID: userID,
			Date:   from,
			Date_2: to,
		})
		if err != nil {
			s.logger.Error("failed to count attendance records", "err", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
			return
		}

		resp := make([]attendanceRecordResponse, 0, len(records))
		for _, rec := range records {
			resp = append(resp, toAttendanceRecordResponse(rec))
		}

		s.writeJSON(w, http.StatusOK, struct {
			Records []attendanceRecordResponse `json:"records"`
			Total   int64                      `json:"total"`
			Limit   int32                      `json:"limit"`
			Offset  int32                      `json:"offset"`
		}{resp, total, limit, offset})
		return
	}

	// All users, date range only — joined query, has full_name/email.
	records, err := s.store.GetAttendanceRecordsForDateRangeWithUser(r.Context(), database.GetAttendanceRecordsForDateRangeWithUserParams{
		Date:   from,
		Date_2: to,
		Limit:  limit,
		Offset: offset,
	})
	if err != nil {
		s.logger.Error("failed to fetch attendance records", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	total, err := s.store.CountAttendanceRecordsForDateRange(r.Context(), database.CountAttendanceRecordsForDateRangeParams{
		Date:   from,
		Date_2: to,
	})
	if err != nil {
		s.logger.Error("failed to count attendance records", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	resp := make([]attendanceWithUserResponse, 0, len(records))
	for _, rec := range records {
		resp = append(resp, toAttendanceWithUserResponse(
			rec.ID, rec.UserID, rec.FullName, rec.Email, rec.Date,
			rec.CheckIn, rec.CheckOut, rec.CheckInPhotoUri, rec.CheckOutPhotoUri,
			rec.LocationChecksCount, rec.LocationChecksWithin,
		))
	}

	s.writeJSON(w, http.StatusOK, struct {
		Records []attendanceWithUserResponse `json:"records"`
		Total   int64                        `json:"total"`
		Limit   int32                        `json:"limit"`
		Offset  int32                        `json:"offset"`
	}{resp, total, limit, offset})
}

// ---------------------------------------------------------------------------
// Currently clocked in — open shifts for a given date (defaults to today)
// GET /admin/attendance/open?date=
// ---------------------------------------------------------------------------

func (s *Server) handleGetOpenAttendance(w http.ResponseWriter, r *http.Request) {
	date, err := parseSingleDate(r.URL.Query().Get("date"))
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  map[string]string{"date": "date must be in DD-MM-YYYY format"},
		})
		return
	}

	records, err := s.store.GetOpenAttendanceRecordsForDate(r.Context(), date)
	if err != nil {
		s.logger.Error("failed to fetch open attendance records", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	resp := make([]attendanceWithUserResponse, 0, len(records))
	for _, rec := range records {
		resp = append(resp, toAttendanceWithUserResponse(
			rec.ID, rec.UserID, rec.FullName, rec.Email, rec.Date,
			rec.CheckIn, rec.CheckOut, rec.CheckInPhotoUri, rec.CheckOutPhotoUri,
			0, 0,
		))
	}

	s.writeJSON(w, http.StatusOK, resp)
}

// ---------------------------------------------------------------------------
// Absentees — users with no attendance record for a given date
// GET /admin/attendance/absent?date=
// ---------------------------------------------------------------------------

func (s *Server) handleGetAbsentUsers(w http.ResponseWriter, r *http.Request) {
	date, err := parseSingleDate(r.URL.Query().Get("date"))
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  map[string]string{"date": "date must be in DD-MM-YYYY format"},
		})
		return
	}

	users, err := s.store.GetUsersWithoutAttendanceForDate(r.Context(), date)
	if err != nil {
		s.logger.Error("failed to fetch absent users", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	type absentUser struct {
		ID       uuid.UUID `json:"id"`
		FullName string    `json:"full_name"`
		Email    string    `json:"email"`
	}
	resp := make([]absentUser, 0, len(users))
	for _, u := range users {
		resp = append(resp, absentUser{ID: u.ID, FullName: u.FullName, Email: pgTextToString(u.Email)})
	}

	s.writeJSON(w, http.StatusOK, resp)
}

// ---------------------------------------------------------------------------
// Stale open shifts — check-in with no check-out past the update window
// GET /admin/attendance/stale
// ---------------------------------------------------------------------------

func (s *Server) handleGetStaleOpenAttendance(w http.ResponseWriter, r *http.Request) {
	cutoff := convertTimeToPGTimeTz(time.Now().Add(-attendanceUpdateWindow))

	records, err := s.store.GetStaleOpenAttendanceRecords(r.Context(), cutoff)
	if err != nil {
		s.logger.Error("failed to fetch stale open attendance records", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	resp := make([]attendanceWithUserResponse, 0, len(records))
	for _, rec := range records {
		resp = append(resp, toAttendanceWithUserResponse(
			rec.ID, rec.UserID, rec.FullName, rec.Email, rec.Date,
			rec.CheckIn, rec.CheckOut, rec.CheckInPhotoUri, rec.CheckOutPhotoUri,
			0, 0,
		))
	}

	s.writeJSON(w, http.StatusOK, resp)
}

// ---------------------------------------------------------------------------
// Shared param parsing helpers
// ---------------------------------------------------------------------------

func parseSingleDate(raw string) (pgtype.Date, error) {
	if raw == "" {
		raw = time.Now().Format(dateLayout)
	}
	t, err := time.Parse(dateLayout, raw)
	if err != nil {
		return pgtype.Date{}, err
	}
	return convertTimeToPGDate(t), nil
}

func parseDateRange(fromRaw, toRaw string, fieldErrors map[string]string) (from, to pgtype.Date, ok bool) {
	ok = true

	fromT, err := time.Parse(dateLayout, orDefault(fromRaw, time.Now().AddDate(0, 0, -30).Format(dateLayout)))
	if err != nil {
		fieldErrors["from"] = "from must be in DD-MM-YYYY format"
		ok = false
	}

	toT, err := time.Parse(dateLayout, orDefault(toRaw, time.Now().Format(dateLayout)))
	if err != nil {
		fieldErrors["to"] = "to must be in DD-MM-YYYY format"
		ok = false
	}

	if ok && toT.Before(fromT) {
		fieldErrors["to"] = "to must not be before from"
		ok = false
	}

	if !ok {
		return from, to, false
	}

	return convertTimeToPGDate(fromT), convertTimeToPGDate(toT), true
}

func parsePagination(limitRaw, offsetRaw string, fieldErrors map[string]string) (limit, offset int32, ok bool) {
	ok = true
	limit = defaultAttendanceLimit

	if limitRaw != "" {
		l, err := strconv.Atoi(limitRaw)
		if err != nil || l < 1 || l > maxAttendanceLimit {
			fieldErrors["limit"] = "limit must be an integer between 1 and " + strconv.Itoa(maxAttendanceLimit)
			ok = false
		} else {
			limit = int32(l)
		}
	}

	if offsetRaw != "" {
		o, err := strconv.Atoi(offsetRaw)
		if err != nil || o < 0 {
			fieldErrors["offset"] = "offset must be a non-negative integer"
			ok = false
		} else {
			offset = int32(o)
		}
	}

	return limit, offset, ok
}

func orDefault(v, def string) string {
	if v == "" {
		return def
	}
	return v
}
