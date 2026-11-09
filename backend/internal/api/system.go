package api

import (
	"encoding/json"
	"net/http"

	"gitlab.com/realRudraP/beacon/backend/internal/config"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"golang.org/x/crypto/bcrypt"
)

type getConfigResponse struct {
	Branding      config.BrandingConfig `json:"branding"`
	Timezone      string                `json:"timezone"`
	SetupRequired bool                  `json:"setup_required,omitempty"`
}

func (s *Server) requireNotInitialized(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		setupRequired, err := s.store.CheckSetupRequired(r.Context())
		if err != nil {
			s.logger.Error("failed to check if setup is required", "error", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{
				Code:    ErrCodeInternalError,
				Message: "internal server error",
			})
			return
		}

		if !setupRequired {
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "system was initialized",
			})
			return
		}

		next.ServeHTTP(w, r)
	})
}

type HandleRegisterSuperAdminRequest struct {
	FullName string `json:"full_name"`
	Username string `json:"username"`
	Password string `json:"password"`
	Email    string `json:"email"`
}

func (s *Server) HandleRegisterSuperAdmin(w http.ResponseWriter, r *http.Request) {
	var req HandleRegisterSuperAdminRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request body",
		})
	}
	if fieldErrors := validateRegisterSuperAdminRequest(req); len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeValidationError,
			Message: "validation failed",
			Fields:  fieldErrors,
		})
		return
	}
	hashedPassword, err := bcrypt.GenerateFromPassword([]byte(req.Password), bcrypt.DefaultCost)
	if err != nil {
		s.logger.Error("failed to hash password", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	superadmin, err := s.store.CreateSuperAdmin(r.Context(), database.CreateSuperAdminParams{
		Username:     req.Username,
		PasswordHash: string(hashedPassword),
		FullName:     req.FullName,
		Email:        stringToPGText(req.Email),
	})
	if err != nil {
		s.logger.Error("failed to create superadmin", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	resp := registerUserResponse{
		Username: superadmin.Username,
		FullName: superadmin.FullName,
	}
	s.writeJSON(w, http.StatusOK, resp)

}

func (s *Server) getConfig(w http.ResponseWriter, r *http.Request) {
	setup_required, err := s.store.CheckSetupRequired(r.Context())
	if err != nil {
		s.logger.Error("failed to check if setup is required", "error", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	timezone := "UTC"
	if s.AppConfig != nil && s.AppConfig.App.Timezone != "" {
		timezone = s.AppConfig.App.Timezone
	}

	resp := getConfigResponse{
		Branding:      s.AppConfig.Branding,
		Timezone:      timezone,
		SetupRequired: setup_required,
	}
	s.writeJSON(w, http.StatusOK, resp)
}

func validateRegisterSuperAdminRequest(req HandleRegisterSuperAdminRequest) FieldError {
	fieldErrors := make(map[string]string)
	if req.FullName == "" {
		fieldErrors["username"] = "is required"
	}
	if req.Username == "" {
		fieldErrors["username"] = "is required"
	}
	if req.Password == "" {
		fieldErrors["password"] = "is required"
	} else if len(req.Password) < 8 {
		fieldErrors["password"] = "must be at least 8 characters"
	}
	return fieldErrors
}
