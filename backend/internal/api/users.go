package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/jackc/pgx/v5/pgconn"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"golang.org/x/crypto/bcrypt"
)

type registerUserRequest struct {
	Username    string `json:"username"`
	FullName    string `json:"full_name"`
	PhoneNumber string `json:"phone_number"`
	Password    string `json:"password"`
}

type registerUserResponse struct {
	Username    string `json:"username"`
	FullName    string `json:"full_name"`
	PhoneNumber string `json:"phone_number"`
}

func (s *Server) RegisterUser(w http.ResponseWriter, r *http.Request) {
	var req registerUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request body",
		})
		return
	}

	if fieldErrors := validateRegisterUserRequest(req); len(fieldErrors) > 0 {
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

	user, err := s.store.CreateUser(r.Context(), database.CreateUserParams{
		Username:     req.Username,
		FullName:     req.FullName,
		PhoneNumber:  req.PhoneNumber,
		PasswordHash: string(hashedPassword),
	})

	if err != nil {
		var pgErr *pgconn.PgError
		if errors.As(err, &pgErr) && pgErr.Code == "23505" {
			s.writeAPIError(w, http.StatusConflict, APIError{
				Code:    ErrCodeConflict,
				Message: "username already taken",
			})
			return
		}

		s.logger.Error("failed to create user", "err", err)

		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusCreated, registerUserResponse{
		Username:    user.Username,
		FullName:    user.FullName,
		PhoneNumber: user.PhoneNumber,
	})
}

func validateRegisterUserRequest(req registerUserRequest) map[string]string {
	errors := make(map[string]string)

	if strings.TrimSpace(req.Username) == "" {
		errors["username"] = "is required"
	} else if len(req.Username) < 3 {
		errors["username"] = "must be at least 3 characters"
	}

	if strings.TrimSpace(req.FullName) == "" {
		errors["full_name"] = "is required"
	}

	if strings.TrimSpace(req.PhoneNumber) == "" {
		errors["phone_number"] = "is required"
	} else if len(req.PhoneNumber) < 10 {
		errors["phone_number"] = "must be at least 10 digits"
	}

	if strings.TrimSpace(req.Password) == "" {
		errors["password"] = "is required"
	} else if len(req.Password) < 6 {
		errors["password"] = "must be at least 6 characters"
	}

	return errors

}
