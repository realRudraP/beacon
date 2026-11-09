package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

const (
	maxLocationChecksPerBatch   = 6
	locationCheckWindow         = 9 * time.Hour
	minLocationCheckSequence    = 1
	maxLocationCheckSequence    = 6
	defaultLocationCheckLimit   = 100
	maxLocationCheckLimit       = 500
	locationCheckDateLayout     = "2006-01-02"
	locationCheckFallbackLayout = dateLayout
)

// ---------------------------------------------------------------------------
// Worker ingest — background location checks captured on the device
// POST /api/v1/attendance/location-checks
// ---------------------------------------------------------------------------

type locationCheckInput struct {
	ID                 string   `json:"id"`
	AttendanceRecordID string   `json:"attendance_record_id"`
	CapturedAt         string   `json:"captured_at"`
	Latitude           *float64 `json:"latitude"`
	Longitude          *float64 `json:"longitude"`
	Accuracy           *float64 `json:"accuracy"`
	SequenceNumber     *int     `json:"sequence_number"`
}

type ingestLocationChecksRequest struct {
	Checks []locationCheckInput `json:"checks"`
}

type locationCheckRejection struct {
	ID     string `json:"id"`
	Reason string `json:"reason"`
}

type ingestLocationChecksResponse struct {
	Accepted   []uuid.UUID              `json:"accepted"`
	Duplicates []uuid.UUID              `json:"duplicates"`
	Rejected   []locationCheckRejection `json:"rejected"`
}

type parsedLocationCheck struct {
	id                 uuid.UUID
	attendanceRecordID uuid.UUID
	capturedAt         time.Time
	latitude           float64
	longitude          float64
	accuracy           pgtype.Float8
	sequenceNumber     int16
}

func (s *Server) handleIngestLocationChecks(w http.ResponseWriter, r *http.Request) {
	var req ingestLocationChecksRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
		})
		return
	}

	if len(req.Checks) == 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  map[string]string{"checks": "at least one check is required"},
		})
		return
	}
	if len(req.Checks) > maxLocationChecksPerBatch {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  map[string]string{"checks": "no more than 6 checks are allowed per request"},
		})
		return
	}

	claims := r.Context().Value("authClaims").(*token.Claims)

	geofences, err := s.store.GetAllGeofences(r.Context())
	if err != nil {
		s.logger.Error("failed to fetch geofences", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	resp := ingestLocationChecksResponse{
		Accepted:   make([]uuid.UUID, 0, len(req.Checks)),
		Duplicates: make([]uuid.UUID, 0),
		Rejected:   make([]locationCheckRejection, 0),
	}

	for _, input := range req.Checks {
		parsed, reason := parseLocationCheckInput(input)
		if reason != "" {
			resp.Rejected = append(resp.Rejected, locationCheckRejection{ID: input.ID, Reason: reason})
			continue
		}

		record, err := s.store.GetAttendanceRecordByID(r.Context(), parsed.attendanceRecordID)
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				resp.Rejected = append(resp.Rejected, locationCheckRejection{
					ID:     input.ID,
					Reason: "attendance record not found",
				})
				continue
			}
			s.logger.Error("failed to fetch attendance record for location check", "err", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{
				Code:    ErrCodeInternalError,
				Message: "something went wrong",
			})
			return
		}

		if record.UserID != claims.UserID {
			resp.Rejected = append(resp.Rejected, locationCheckRejection{
				ID:     input.ID,
				Reason: "attendance record does not belong to this user",
			})
			continue
		}

		if reason := validateLocationCheckWindow(parsed.capturedAt, record); reason != "" {
			resp.Rejected = append(resp.Rejected, locationCheckRejection{ID: input.ID, Reason: reason})
			continue
		}

		nearestID, nearestName, distance, within := evaluateLocationCheck(parsed.latitude, parsed.longitude, geofences)

		_, err = s.store.InsertLocationCheck(r.Context(), database.InsertLocationCheckParams{
			ID:                  parsed.id,
			AttendanceRecordID:  parsed.attendanceRecordID,
			UserID:              claims.UserID,
			CapturedAt:          convertTimeToPGTimeTz(parsed.capturedAt),
			Latitude:            parsed.latitude,
			Longitude:           parsed.longitude,
			Accuracy:            parsed.accuracy,
			SequenceNumber:      parsed.sequenceNumber,
			WithinGeofence:      within,
			NearestGeofenceID:   nearestID,
			NearestGeofenceName: nearestName,
			DistanceMeters:      distance,
		})
		if err != nil {
			if errors.Is(err, pgx.ErrNoRows) {
				resp.Duplicates = append(resp.Duplicates, parsed.id)
				continue
			}
			s.logger.Error("failed to insert location check", "err", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{
				Code:    ErrCodeInternalError,
				Message: "something went wrong",
			})
			return
		}

		resp.Accepted = append(resp.Accepted, parsed.id)
	}

	s.writeJSON(w, http.StatusOK, resp)
}

func parseLocationCheckInput(input locationCheckInput) (parsedLocationCheck, string) {
	id, err := uuid.Parse(input.ID)
	if err != nil {
		return parsedLocationCheck{}, "id must be a valid UUID"
	}

	attendanceID, err := uuid.Parse(input.AttendanceRecordID)
	if err != nil {
		return parsedLocationCheck{}, "attendance_record_id must be a valid UUID"
	}

	capturedAt, err := time.Parse(time.RFC3339, input.CapturedAt)
	if err != nil {
		return parsedLocationCheck{}, "captured_at must be a valid RFC3339 timestamp"
	}

	if input.Latitude == nil {
		return parsedLocationCheck{}, "latitude is required"
	}
	if *input.Latitude < -90 || *input.Latitude > 90 {
		return parsedLocationCheck{}, "latitude must be between -90 and 90"
	}

	if input.Longitude == nil {
		return parsedLocationCheck{}, "longitude is required"
	}
	if *input.Longitude < -180 || *input.Longitude > 180 {
		return parsedLocationCheck{}, "longitude must be between -180 and 180"
	}

	if input.SequenceNumber == nil {
		return parsedLocationCheck{}, "sequence_number is required"
	}
	if *input.SequenceNumber < minLocationCheckSequence || *input.SequenceNumber > maxLocationCheckSequence {
		return parsedLocationCheck{}, "sequence_number must be between 1 and 6"
	}

	accuracy := pgtype.Float8{}
	if input.Accuracy != nil {
		accuracy = pgtype.Float8{Float64: *input.Accuracy, Valid: true}
	}

	return parsedLocationCheck{
		id:                 id,
		attendanceRecordID: attendanceID,
		capturedAt:         capturedAt.UTC(),
		latitude:           *input.Latitude,
		longitude:          *input.Longitude,
		accuracy:           accuracy,
		sequenceNumber:     int16(*input.SequenceNumber),
	}, ""
}

func validateLocationCheckWindow(capturedAt time.Time, record database.AttendanceRecord) string {
	if !record.CheckIn.Valid {
		return "attendance record has no clock-in time"
	}

	start := record.CheckIn.Time
	end := start.Add(locationCheckWindow)
	if record.CheckOut.Valid {
		end = record.CheckOut.Time
	}

	if capturedAt.Before(start) {
		return "captured_at is before clock-in"
	}
	if capturedAt.After(end) {
		return "captured_at is outside the attendance window"
	}
	return ""
}

func evaluateLocationCheck(latitude, longitude float64, geofences []database.Geofence) (pgtype.UUID, pgtype.Text, pgtype.Float8, bool) {
	if len(geofences) == 0 {
		return pgtype.UUID{}, pgtype.Text{}, pgtype.Float8{}, false
	}

	id, name, distance, within := nearestGeofence(latitude, longitude, geofences)
	return pgtype.UUID{Bytes: id, Valid: true},
		pgtype.Text{String: name, Valid: true},
		pgtype.Float8{Float64: distance, Valid: true},
		within
}

// ---------------------------------------------------------------------------
// Admin list — background location checks with worker info
// GET /api/v1/attendance/admin/location-checks
// ---------------------------------------------------------------------------

type locationCheckResponse struct {
	ID                  uuid.UUID  `json:"id"`
	AttendanceRecordID  uuid.UUID  `json:"attendance_record_id"`
	UserID              uuid.UUID  `json:"user_id"`
	FullName            string     `json:"full_name"`
	Email               string     `json:"email"`
	CapturedAt          string     `json:"captured_at"`
	Latitude            float64    `json:"latitude"`
	Longitude           float64    `json:"longitude"`
	Accuracy            *float64   `json:"accuracy,omitempty"`
	SequenceNumber      int16      `json:"sequence_number"`
	WithinGeofence      bool       `json:"within_geofence"`
	NearestGeofenceID   *uuid.UUID `json:"nearest_geofence_id,omitempty"`
	NearestGeofenceName string     `json:"nearest_geofence_name,omitempty"`
	DistanceMeters      *float64   `json:"distance_meters,omitempty"`
}

func (s *Server) handleListLocationChecks(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)
	if ParseRole(claims.Role) < RoleAdmin {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrCodeForbidden,
			Message: "Forbidden",
		})
		return
	}

	q := r.URL.Query()
	fieldErrors := make(map[string]string)

	from, to, ok := s.parseLocationCheckRange(q.Get("from"), q.Get("to"), fieldErrors)
	limit, offset, okPagination := parseLocationCheckPagination(q.Get("limit"), q.Get("offset"), fieldErrors)
	if !ok || !okPagination || len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  fieldErrors,
		})
		return
	}

	userParam := q.Get("user_id")
	attendanceParam := q.Get("attendance_id")

	var userID pgtype.UUID
	if userParam != "" {
		parsed, err := uuid.Parse(userParam)
		if err != nil {
			fieldErrors["user_id"] = "user_id must be a valid UUID"
		} else {
			userID = pgtype.UUID{Bytes: parsed, Valid: true}
		}
	}

	var attendanceID pgtype.UUID
	if attendanceParam != "" {
		parsed, err := uuid.Parse(attendanceParam)
		if err != nil {
			fieldErrors["attendance_id"] = "attendance_id must be a valid UUID"
		} else {
			attendanceID = pgtype.UUID{Bytes: parsed, Valid: true}
		}
	}

	if len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields:  fieldErrors,
		})
		return
	}

	listParams := database.ListLocationChecksParams{
		UserID:             userID,
		AttendanceRecordID: attendanceID,
		FromTs:             from,
		ToTs:               to,
		Limit:              limit,
		Offset:             offset,
	}

	rows, err := s.store.ListLocationChecks(r.Context(), listParams)
	if err != nil {
		s.logger.Error("failed to fetch location checks", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	total, err := s.store.CountLocationChecks(r.Context(), database.CountLocationChecksParams{
		UserID:             userID,
		AttendanceRecordID: attendanceID,
		FromTs:             from,
		ToTs:               to,
	})
	if err != nil {
		s.logger.Error("failed to count location checks", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{Code: ErrCodeInternalError, Message: "something went wrong"})
		return
	}

	resp := make([]locationCheckResponse, 0, len(rows))
	for _, row := range rows {
		resp = append(resp, toLocationCheckResponse(row))
	}

	s.writeJSON(w, http.StatusOK, struct {
		Checks []locationCheckResponse `json:"checks"`
		Total  int64                   `json:"total"`
		Limit  int32                   `json:"limit"`
		Offset int32                   `json:"offset"`
	}{resp, total, limit, offset})
}

func toLocationCheckResponse(row database.ListLocationChecksRow) locationCheckResponse {
	resp := locationCheckResponse{
		ID:                  row.ID,
		AttendanceRecordID:  row.AttendanceRecordID,
		UserID:              row.UserID,
		FullName:            row.FullName,
		Email:               pgTextToString(row.Email),
		Latitude:            row.Latitude,
		Longitude:           row.Longitude,
		SequenceNumber:      row.SequenceNumber,
		WithinGeofence:      row.WithinGeofence,
		NearestGeofenceName: pgTextToString(row.NearestGeofenceName),
	}
	if row.CapturedAt.Valid {
		resp.CapturedAt = row.CapturedAt.Time.UTC().Format(time.RFC3339)
	}
	if row.Accuracy.Valid {
		value := row.Accuracy.Float64
		resp.Accuracy = &value
	}
	if row.NearestGeofenceID.Valid {
		id := uuid.UUID(row.NearestGeofenceID.Bytes)
		resp.NearestGeofenceID = &id
	}
	if row.DistanceMeters.Valid {
		value := row.DistanceMeters.Float64
		resp.DistanceMeters = &value
	}
	return resp
}

func (s *Server) parseLocationCheckRange(fromRaw, toRaw string, fieldErrors map[string]string) (from, to pgtype.Timestamptz, ok bool) {
	loc := s.AppConfig.Location()
	now := time.Now().In(loc)

	fromTime := now.AddDate(0, 0, -30)
	toTime := now

	if fromRaw != "" {
		parsed, err := parseLocationCheckTime(fromRaw, loc, false)
		if err != nil {
			fieldErrors["from"] = "from must be an RFC3339 timestamp or a date (YYYY-MM-DD / DD-MM-YYYY)"
			return from, to, false
		}
		fromTime = parsed
	}

	if toRaw != "" {
		parsed, err := parseLocationCheckTime(toRaw, loc, true)
		if err != nil {
			fieldErrors["to"] = "to must be an RFC3339 timestamp or a date (YYYY-MM-DD / DD-MM-YYYY)"
			return from, to, false
		}
		toTime = parsed
	}

	if toTime.Before(fromTime) {
		fieldErrors["to"] = "to must not be before from"
		return from, to, false
	}

	return convertTimeToPGTimeTz(fromTime.UTC()), convertTimeToPGTimeTz(toTime.UTC()), true
}

// parseLocationCheckTime accepts RFC3339 timestamps as well as date-only values
// interpreted in the configured reporting timezone. For date-only upper bounds,
// the following midnight is returned so ranges are half-open.
func parseLocationCheckTime(raw string, loc *time.Location, exclusiveDate bool) (time.Time, error) {
	if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
		return parsed, nil
	}

	for _, layout := range []string{locationCheckDateLayout, locationCheckFallbackLayout} {
		parsed, err := time.ParseInLocation(layout, raw, loc)
		if err == nil {
			if exclusiveDate {
				parsed = parsed.AddDate(0, 0, 1)
			}
			return parsed, nil
		}
	}

	return time.Time{}, errors.New("invalid time")
}

func parseLocationCheckPagination(limitRaw, offsetRaw string, fieldErrors map[string]string) (limit, offset int32, ok bool) {
	ok = true
	limit = defaultLocationCheckLimit

	if limitRaw != "" {
		parsed, err := strconv.Atoi(limitRaw)
		if err != nil || parsed < 1 || parsed > maxLocationCheckLimit {
			fieldErrors["limit"] = "limit must be an integer between 1 and " + strconv.Itoa(maxLocationCheckLimit)
			ok = false
		} else {
			limit = int32(parsed)
		}
	}

	if offsetRaw != "" {
		parsed, err := strconv.Atoi(offsetRaw)
		if err != nil || parsed < 0 {
			fieldErrors["offset"] = "offset must be a non-negative integer"
			ok = false
		} else {
			offset = int32(parsed)
		}
	}

	return limit, offset, ok
}
