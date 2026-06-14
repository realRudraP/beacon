package api

import (
	"encoding/json"
	"net/http"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

type Geofence struct {
	Name       string  `json:"name"`
	CentreLat  float64 `json:"centre_lat"`
	CentreLong float64 `json:"centre_lng"`
	Radius     float64 `json:"radius"`
}

func (s *Server) createGeofence(w http.ResponseWriter, r *http.Request) {
	var req Geofence
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "Invalid request body",
		})
		return
	}
	claims := r.Context().Value("authClaims").(*token.Claims)

	if ParseRole(claims.Role) < RoleAdmin {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrCodeForbidden,
			Message: "Forbidden",
		})
		return
	}

	geofence, err := s.store.CreateGeofence(r.Context(), database.CreateGeofenceParams{
		ID:        uuid.New(),
		Name:      req.Name,
		CenterLat: req.CentreLat,
		CenterLng: req.CentreLong,
		Radius:    req.Radius,
	})
	if err != nil {
		s.logger.Error("Failed to create geofence", "error", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to create geofence",
		})
		return
	}
	s.writeJSON(w, http.StatusOK, geofence)
}

func (s *Server) getAllGeofences(w http.ResponseWriter, r *http.Request) {
	geofences, err := s.store.GetAllGeofences(r.Context())
	if err != nil {
		s.logger.Error("Failed to get geofences", "error", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to get geofences",
		})
		return
	}
	s.writeJSON(w, http.StatusOK, geofences)
}

func (s *Server) deleteGeofence(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)
	if ParseRole(claims.Role) < RoleAdmin {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrCodeForbidden,
			Message: "Forbidden",
		})
		return
	}

	geofenceIDStr := chi.URLParam(r, "id")
	geofenceID, err := uuid.Parse(geofenceIDStr)
	if err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "Invalid geofence ID",
		})
		return
	}

	err = s.store.DeleteGeofence(r.Context(), geofenceID)
	if err != nil {
		s.logger.Error("Failed to delete geofence", "error", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "Failed to delete geofence",
		})
		return
	}
	s.writeJSON(w, http.StatusOK, map[string]string{"message": "Geofence deleted successfully"})

}

func validateGeofence(req Geofence) map[string]string {
	errors := make(map[string]string)
	if req.Name == "" {
		errors["name"] = "Name is required"
	}
	if req.CentreLat < -90 || req.CentreLat > 90 {
		errors["centre_lat"] = "Invalid latitude"
	}
	if req.CentreLong < -180 || req.CentreLong > 180 {
		errors["centre_long"] = "Invalid longitude"
	}
	if req.Radius <= 0 {
		errors["radius"] = "Radius must be a positive number"
	}

	return errors
}
