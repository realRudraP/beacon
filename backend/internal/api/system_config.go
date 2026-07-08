package api

import (
	"encoding/json"
	"net/http"
	"time"

	"github.com/google/uuid"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

type SyncConfigResponse struct {
	ServerTime     time.Time      `json:"server_time"`
	GlobalPolicies GlobalPolicies `json:"global_policies"`
	User           UserPolicies   `json:"user_policies"`
}

type NotificationRule struct {
	ID          string                 `json:"id"`
	Title       string                 `json:"title"`
	Description string                 `json:"description"`
	Enabled     bool                   `json:"enabled"`
	Time        string                 `json:"time"`
	Conditions  NotificationConditions `json:"conditions"`
}

type NotificationConditions struct {
	IsWorkingDay          *bool `json:"is_working_day,omitempty"`
	IsPublicHoliday       *bool `json:"is_public_holiday,omitempty"`
	HasClockedInToday     *bool `json:"has_clocked_in_today,omitempty"`
	HasClockedOutToday    *bool `json:"has_clocked_out_today,omitempty"`
	HasPendingSyncRecords *bool `json:"has_pending_sync_records,omitempty"`
}

type GlobalPolicies struct {
	AllowedAccuracyMeters float64             `json:"allowed_accuracy_meters"`
	PublicHolidays        []string            `json:"public_holidays"`
	Geofences             []database.Geofence `json:"geofences"`
	NotificationRules     []NotificationRule  `json:"notification_rules"`
}

type UserPolicies struct {
	WeeklyDaysOff []int16 `json:"weekly_days_off"`
}

func (s *Server) HandleSyncConfig(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("claims").(*token.Claims)

	user, err := s.store.GetUserByUsername(r.Context(), claims.Username)
	if err != nil {
		s.logger.Error("failed to fetch user by username", "username", claims.Username, "err", err)
		s.writeAPIError(w, http.StatusNotFound, APIError{
			Code:    ErrUserNotFound.Error(),
			Message: "failed to find user in system",
		})
		return
	}

	dbGeofences, err := s.store.GetAllGeofences(r.Context())
	if err != nil {
		s.logger.Error("failed to fetch geofences", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "failed to fetch geofences",
		})
		return
	}

	if dbGeofences == nil {
		dbGeofences = []database.Geofence{}
	}
	configRow, err := s.store.GetSyncConfigByKey(r.Context(), "device_policies")
	var globalPolicies GlobalPolicies

	if err == nil {
		if err := json.Unmarshal(configRow.Value, &globalPolicies); err != nil {
			s.logger.Error("failed to unmarshal global policies", "err", err)
			globalPolicies = defaultGlobalPolicies()
		}
	} else {
		globalPolicies = defaultGlobalPolicies()
	}

	globalPolicies.Geofences = dbGeofences

	resp := SyncConfigResponse{
		ServerTime:     time.Now().UTC(),
		GlobalPolicies: globalPolicies,
		User: UserPolicies{
			WeeklyDaysOff: user.WeeklyOffDays,
		},
	}
	s.writeJSON(w, http.StatusOK, resp)

}

func (s *Server) HandleGetSyncConfig(w http.ResponseWriter, r *http.Request) {
	configRow, err := s.store.GetSyncConfigByKey(r.Context(), "device_policies")
	var policies GlobalPolicies

	if err != nil {
		// If it doesn't exist yet, return the defaults!
		policies = defaultGlobalPolicies()
	} else {
		if err := json.Unmarshal(configRow.Value, &policies); err != nil {
			s.logger.Error("failed to unmarshal global policies", "err", err)
			policies = defaultGlobalPolicies()
		}
	}

	policies.Geofences, err = s.store.GetAllGeofences(r.Context())
	if err != nil {
		s.logger.Error("failed to fetch geofences for sync config response", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "failed to fetch geofences",
		})
		return
	}
	s.writeJSON(w, http.StatusOK, policies)
}

func (s *Server) HandleUpdateSyncConfig(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)

	if ParseRole(claims.Role) < RoleAdmin {
		s.logger.Warn("unauthorized attempt to change system config", "user", claims.Username)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrCodeForbidden,
			Message: "You do not have permission to perform this action",
		})
		return
	}
	var req GlobalPolicies
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "Invalid JSON payload",
		})
		return
	}

	if req.AllowedAccuracyMeters <= 0 {
		req.AllowedAccuracyMeters = 100 // enforce a sane minimum
	}

	jsonBytes, err := json.Marshal(req)
	if err != nil {
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to process configuration",
		})
		return
	}

	currentPolicy, err := s.store.GetSyncConfigByKey(r.Context(), "device_policies")
	if err != nil && err.Error() != "sql: no rows in result set" {
		s.logger.Error("failed to fetch existing config", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to save configuration",
		})
		return
	}

	updatedPolicies, err := s.store.UpsertSyncConfig(r.Context(), database.UpsertSyncConfigParams{
		Key:   "device_policies",
		Value: jsonBytes,
	})
	if err != nil {
		s.logger.Error("failed to upsert config", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to save configuration",
		})
		return
	}
	s.logger.Info("updated global policies", "updated_by", claims.Username, "new_policies", req)
	s.store.CreateAuditLog(r.Context(), database.CreateAuditLogParams{
		ID:         uuid.New(),
		UserID:     claims.UserID,
		Action:     "updated sync config",
		EntityType: "sync config",
		EntityID:   "device_policies",
		OldValue:   currentPolicy.Value,
		NewValue:   updatedPolicies.Value,
	})
	s.writeJSON(w, http.StatusOK, req)
}

// Sensible defaultGlobalPolicies in case the config is not set or fails to unmarshal.
// This ensures that the app can still function with reasonable defaults rather than breaking entirely.
func defaultGlobalPolicies() GlobalPolicies {
	return GlobalPolicies{
		AllowedAccuracyMeters: 100,
		PublicHolidays:        []string{},
		Geofences:             []database.Geofence{},
		NotificationRules:     []NotificationRule{},
	}
}
