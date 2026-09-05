package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

const attendanceUpdateWindow = 24 * time.Hour

const clockTimeLayout = time.RFC3339
const attendanceUserDateUniqueConstraint = "uq_attendance_records_user_date"

// ---------------------------------------------------------------------------
// Mark attendance (clock-in, with optional same-request clock-out)
// ---------------------------------------------------------------------------

type markAttendanceRequest struct {
	ID               string   `json:"id"`
	ClockInTime      string   `json:"clock_in_time"`
	ClockInLat       *float64 `json:"clock_in_lat"`
	ClockInLong      *float64 `json:"clock_in_long"`
	ClockInPhotoURI  string   `json:"clock_in_photo_uri,omitempty"`
	ClockOutTime     string   `json:"clock_out_time,omitempty"`
	ClockOutLat      float64  `json:"clock_out_lat,omitempty"`
	ClockOutLong     float64  `json:"clock_out_long,omitempty"`
	ClockOutPhotoURI string   `json:"clock_out_photo_uri,omitempty"`
}

func (s *Server) handleMarkAttendance(w http.ResponseWriter, r *http.Request) {
	var req markAttendanceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.logger.Error("failed to decode mark attendance request", "err", err)
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
		})
		return
	}

	fieldErrors := validateMarkAttendanceRequest(req)

	var recordID uuid.UUID
	if req.ID != "" {
		var err error
		recordID, err = uuid.Parse(req.ID)
		if err != nil {
			fieldErrors["id"] = "id must be a valid UUID"
		}
	}

	var clockInTime time.Time
	if req.ClockInTime != "" {
		var err error
		clockInTime, err = time.Parse(clockTimeLayout, req.ClockInTime)
		if err != nil {
			fieldErrors["clock_in_time"] = "Clock-in time must be a valid RFC3339 timestamp"
		}
	}

	hasClockOut := req.ClockOutTime != ""
	var clockOutTime time.Time
	if hasClockOut {
		var err error
		clockOutTime, err = time.Parse(clockTimeLayout, req.ClockOutTime)
		if err != nil {
			fieldErrors["clock_out_time"] = "Clock-out time must be a valid RFC3339 timestamp"
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

	clockInLat := *req.ClockInLat
	clockInLong := *req.ClockInLong

	geofences, err := s.store.GetAllGeofences(r.Context())
	if err != nil {
		s.logger.Error("failed to fetch geofences", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if !isWithinAnyGeofence(clockInLat, clockInLong, geofences) {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
			Fields: map[string]string{
				"clock_in_lat":  "Clock-in location is outside any approved geofence",
				"clock_in_long": "Clock-in location is outside any approved geofence",
			},
		})
		return
	}

	claims := r.Context().Value("authClaims").(*token.Claims)

	params := database.InsertAttendanceRecordParams{
		ID:               recordID,
		UserID:           claims.UserID,
		Date:             convertTimeToPGDate(clockInTime),
		CheckIn:          convertTimeToPGTimeTz(clockInTime),
		CheckInPhotoUri:  stringToPGText(req.ClockInPhotoURI),
		CheckInLat:       convertFloat64ToPGFloat8(clockInLat),
		CheckInLong:      convertFloat64ToPGFloat8(clockInLong),
		CheckOutPhotoUri: stringToPGText(req.ClockOutPhotoURI),
		CheckOutLat:      optionalFloat64ToPGFloat8(req.ClockOutLat, hasClockOut),
		CheckOutLong:     optionalFloat64ToPGFloat8(req.ClockOutLong, hasClockOut),
		CheckOut:         optionalTimeToPGTimeTz(clockOutTime, hasClockOut),
	}

	record, err := s.store.InsertAttendanceRecord(r.Context(), params)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			// ON CONFLICT (id) DO NOTHING fired — almost certainly a retried
			// sync from the offline client. Treat it as a successful no-op.
			existing, getErr := s.store.GetAttendanceRecordByID(r.Context(), recordID)
			if getErr != nil {
				s.logger.Error("failed to fetch existing attendance record after conflict", "err", getErr)
				s.writeAPIError(w, http.StatusInternalServerError, APIError{
					Code:    ErrCodeInternalError,
					Message: "something went wrong",
				})
				return
			}

			if existing.UserID != claims.UserID {
				// Same id, different owner — a real conflict, not a benign retry.
				s.writeAPIError(w, http.StatusConflict, APIError{
					Code:    ErrCodeInvalidRequest,
					Message: "an attendance record with this id already exists",
				})
				return
			}

			s.writeJSON(w, http.StatusOK, existing)
			return
		}

		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" && pgErr.ConstraintName == attendanceUserDateUniqueConstraint {
			s.writeAPIError(w, http.StatusConflict, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "you have already clocked in today",
			})
			return
		}

		s.logger.Error("failed to insert attendance record", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusCreated, record)
}

func validateMarkAttendanceRequest(req markAttendanceRequest) map[string]string {
	fieldErrors := make(map[string]string)

	if req.ID == "" {
		fieldErrors["id"] = "id is required"
	}
	if req.ClockInTime == "" {
		fieldErrors["clock_in_time"] = "Clock-in time is required"
	}

	if req.ClockInLat == nil {
		fieldErrors["clock_in_lat"] = "Clock-in latitude is required"
	} else if *req.ClockInLat < -90 || *req.ClockInLat > 90 {
		fieldErrors["clock_in_lat"] = "Clock-in latitude must be between -90 and 90"
	}

	if req.ClockInLong == nil {
		fieldErrors["clock_in_long"] = "Clock-in longitude is required"
	} else if *req.ClockInLong < -180 || *req.ClockInLong > 180 {
		fieldErrors["clock_in_long"] = "Clock-in longitude must be between -180 and 180"
	}

	if req.ClockOutTime != "" {
		if req.ClockOutLat == 0 && req.ClockOutLong == 0 {
			fieldErrors["clock_out_lat"] = "Clock-out latitude is required when clock-out time is provided"
			fieldErrors["clock_out_long"] = "Clock-out longitude is required when clock-out time is provided"
		}
		if req.ClockOutLat < -90 || req.ClockOutLat > 90 {
			fieldErrors["clock_out_lat"] = "Clock-out latitude must be between -90 and 90"
		}
		if req.ClockOutLong < -180 || req.ClockOutLong > 180 {
			fieldErrors["clock_out_long"] = "Clock-out longitude must be between -180 and 180"
		}
	}

	return fieldErrors
}

// ---------------------------------------------------------------------------
// Update attendance (clock-out / late photo upload, within 24h of clock-in)
// Clock-outs are intentionally not geofence-checked — can be from anywhere.
// ---------------------------------------------------------------------------

type updateAttendanceRequest struct {
	ClockInPhotoURI  string  `json:"clock_in_photo_uri,omitempty"`
	ClockOutTime     string  `json:"clock_out_time"`
	ClockOutLat      float64 `json:"clock_out_lat"`
	ClockOutLong     float64 `json:"clock_out_long"`
	ClockOutPhotoURI string  `json:"clock_out_photo_uri,omitempty"`
}

func (s *Server) handleUpdateAttendance(w http.ResponseWriter, r *http.Request) {
	idParam := r.PathValue("id")
	recordID, err := uuid.Parse(idParam)
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid attendance record id",
		})
		return
	}

	var req updateAttendanceRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.logger.Error("failed to decode update attendance request", "err", err)
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
		})
		return
	}

	fieldErrors := validateUpdateAttendanceRequest(req)

	hasClockOut := req.ClockOutTime != ""
	var clockOutTime time.Time
	if hasClockOut {
		var err error
		clockOutTime, err = time.Parse(clockTimeLayout, req.ClockOutTime)
		if err != nil {
			fieldErrors["clock_out_time"] = "Clock-out time must be a valid RFC3339 timestamp"
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

	claims := r.Context().Value("authClaims").(*token.Claims)

	existing, err := s.store.GetAttendanceRecordByID(r.Context(), recordID)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			s.writeAPIError(w, http.StatusNotFound, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "attendance record not found",
			})
			return
		}
		s.logger.Error("failed to fetch attendance record", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if existing.UserID != claims.UserID {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to update this attendance record",
		})
		return
	}

	if time.Since(existing.CreatedAt.Time) > attendanceUpdateWindow {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "this attendance record is more than 24 hours old and can no longer be updated",
		})
		return
	}

	updated, err := s.store.UpdateAttendanceRecord(r.Context(), database.UpdateAttendanceRecordParams{
		ID:               recordID,
		CheckInPhotoUri:  stringToPGText(req.ClockInPhotoURI),
		CheckOut:         optionalTimeToPGTimeTz(clockOutTime, hasClockOut),
		CheckOutPhotoUri: stringToPGText(req.ClockOutPhotoURI),
		CheckOutLat:      convertFloat64ToPGFloat8(req.ClockOutLat),
		CheckOutLong:     convertFloat64ToPGFloat8(req.ClockOutLong),
	})
	if err != nil {
		s.logger.Error("failed to update attendance record", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusOK, updated)
}

func validateUpdateAttendanceRequest(req updateAttendanceRequest) map[string]string {
	fieldErrors := make(map[string]string)

	if req.ClockOutLat < -90 || req.ClockOutLat > 90 {
		fieldErrors["clock_out_lat"] = "Clock-out latitude must be between -90 and 90"
	}
	if req.ClockOutLong < -180 || req.ClockOutLong > 180 {
		fieldErrors["clock_out_long"] = "Clock-out longitude must be between -180 and 180"
	}

	return fieldErrors
}

func (s *Server) handleGetMyAttendance(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)

	fromStr := r.URL.Query().Get("from")
	toStr := r.URL.Query().Get("to")

	var fromDate, toDate pgtype.Date

	if fromStr != "" {
		t, err := time.Parse("2006-01-02", fromStr)
		if err != nil {
			s.writeAPIError(w, http.StatusBadRequest, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "invalid 'from' date format, expected YYYY-MM-DD",
			})
			return
		}
		fromDate = convertTimeToPGDate(t)
	}

	if toStr != "" {
		t, err := time.Parse("2006-01-02", toStr)
		if err != nil {
			s.writeAPIError(w, http.StatusBadRequest, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "invalid 'to' date format, expected YYYY-MM-DD",
			})
			return
		}
		toDate = convertTimeToPGDate(t)
	}

	records, err := s.store.GetAttendanceRecordsByUserId(r.Context(), database.GetAttendanceRecordsByUserIdParams{
		UserID:    claims.UserID,
		StartDate: fromDate,
		EndDate:   toDate,
	})
	if err != nil {
		s.logger.Error("failed to fetch attendance records", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if records == nil {
		records = []database.GetAttendanceRecordsByUserIdRow{}
	}

	s.writeJSON(w, http.StatusOK, records)
}
