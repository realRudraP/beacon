package api

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"

	"github.com/go-chi/chi/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
	"golang.org/x/crypto/bcrypt"
)

type Role int

const (
	RoleWorker Role = iota
	RoleAdmin
	RoleSuperAdmin
)

type registerUserRequest struct {
	Username    string `json:"username"`
	FullName    string `json:"full_name"`
	PhoneNumber string `json:"phone_number"`
	Password    string `json:"password"`
	Role        string `json:"role"`
	Email       string `json:"email"`
}

type registerUserResponse struct {
	Username    string `json:"username"`
	FullName    string `json:"full_name"`
	PhoneNumber string `json:"phone_number"`
	Role        string `json:"role"`
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

	claims := r.Context().Value("authClaims").(*token.Claims)
	requestedRole := ParseRole(req.Role)

	s.logger.Info("Recieved create user request", "requester", claims.Username, "requester_role", claims.Role, "requested_role", requestedRole)

	if ParseRole(claims.Role) <= requestedRole {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}

	if requestedRole == RoleSuperAdmin {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrUnauthorized,
			Message: "this type of account cannot be created",
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
		PhoneNumber:  convertStringToPGText(req.PhoneNumber),
		PasswordHash: string(hashedPassword),
		Role:         convertRoleStringToDBRole(req.Role),
		Email:        convertStringToPGText(req.Email),
	})
	if err != nil {
		var pgErr *pgconn.PgError
		if errors.Is(err, ErrUsernameTaken) || (errors.As(err, &pgErr) && pgErr.Code == "23505") {
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
		PhoneNumber: user.PhoneNumber.String,
		Role:        string(user.Role),
	})
}

func (s *Server) EditUser(w http.ResponseWriter, r *http.Request) {
	username := chi.URLParam(r, "username")
	var req registerUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request body",
		})
		return
	}

	if fieldErrors := validateEditUserRequest(req); len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeValidationError,
			Message: "validation failed",
			Fields:  fieldErrors,
		})
		return
	}

	claims := r.Context().Value("authClaims").(*token.Claims)

	if ParseRole(claims.Role) < RoleAdmin {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}
	userToChange, err := s.store.GetUserByUsername(r.Context(), username)
	if err != nil {
		s.writeAPIError(w, http.StatusNotFound, APIError{
			Code:    ErrUserNotFound.Error(),
			Message: "user not found",
		})
		return
	}
	if ParseRole(string(userToChange.Role)) >= ParseRole(claims.Role) {
		s.logger.Warn("user role change request denied due to insufficient permissions", "requester", claims.Username, "requester_role", claims.Role, "target_user_role", userToChange.Role)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}
	requestedRole := ParseRole(req.Role)
	if requestedRole >= ParseRole(claims.Role) {
		s.logger.Warn("user role change request denied due to insufficient permissions", "requester", claims.Username, "requester_role", claims.Role, "requested_role", req.Role)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}

	updated_user, err := s.store.UpdateUser(r.Context(), database.UpdateUserParams{
		Username:    req.Username,
		FullName:    req.FullName,
		PhoneNumber: convertStringToPGText(req.PhoneNumber),
		Email:       convertStringToPGText(req.Email),
		Role:        convertRoleStringToDBRole(req.Role),
		Active:      true,
	})
	if err != nil {
		s.logger.Error("failed to update user", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusOK, registerUserResponse{
		Username:    updated_user.Username,
		FullName:    updated_user.FullName,
		PhoneNumber: updated_user.PhoneNumber.String,
		Role:        string(updated_user.Role),
	})

}

func (s *Server) softDeleteUser(w http.ResponseWriter, r *http.Request) {
	username := chi.URLParam(r, "username")

	claims := r.Context().Value("authClaims").(*token.Claims)

	if ParseRole(claims.Role) < RoleAdmin {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}

	userToDelete, err := s.store.GetUserByUsername(r.Context(), username)
	if err != nil {
		s.writeAPIError(w, http.StatusNotFound, APIError{
			Code:    ErrUserNotFound.Error(),
			Message: "user not found",
		})
		return
	}

	if ParseRole(string(userToDelete.Role)) >= ParseRole(claims.Role) {
		s.logger.Warn("user deletion request denied due to insufficient permissions", "requester", claims.Username, "requester_role", claims.Role, "target_user_role", userToDelete.Role)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}

	_, err = s.store.UpdateUser(r.Context(), database.UpdateUserParams{
		Username:    userToDelete.Username,
		FullName:    userToDelete.FullName,
		PhoneNumber: userToDelete.PhoneNumber,
		Email:       userToDelete.Email,
		Role:        userToDelete.Role,
		Active:      false,
	})
	if err != nil {
		s.logger.Error("failed to delete user", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]string{
		"message": "user deleted successfully",
	})

}
func validateEditUserRequest(req registerUserRequest) FieldError {
	fieldErrs := make(map[string]string)

	if strings.TrimSpace(req.FullName) == "" {
		fieldErrs["full_name"] = "is required"
	}

	if strings.TrimSpace(req.PhoneNumber) == "" {
		fieldErrs["phone_number"] = "is required"
	} else if len(req.PhoneNumber) < 10 {
		fieldErrs["phone_number"] = "must be at least 10 digits"
	}

	if strings.TrimSpace(req.Role) == "" {
		fieldErrs["role"] = "is required"
	}
	return fieldErrs
}

func validateRegisterUserRequest(req registerUserRequest) FieldError {
	fieldErrs := make(map[string]string)

	if strings.TrimSpace(req.Username) == "" {
		fieldErrs["username"] = "is required"
	} else if len(req.Username) < 3 {
		fieldErrs["username"] = "must be at least 3 characters"
	}

	if strings.TrimSpace(req.FullName) == "" {
		fieldErrs["full_name"] = "is required"
	}

	if strings.TrimSpace(req.PhoneNumber) == "" {
		fieldErrs["phone_number"] = "is required"
	} else if len(req.PhoneNumber) < 10 {
		fieldErrs["phone_number"] = "must be at least 10 digits"
	}

	if strings.TrimSpace(req.Password) == "" {
		fieldErrs["password"] = "is required"
	} else if len(req.Password) < 6 {
		fieldErrs["password"] = "must be at least 6 characters"
	}

	if strings.TrimSpace(req.Role) == "" {
		fieldErrs["role"] = "is required"
	}

	return fieldErrs
}
