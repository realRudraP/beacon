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

type editUserRequest struct {
	FullName    *string `json:"full_name,omitempty"`
	Email       *string `json:"email,omitempty"`
	Role        *string `json:"role,omitempty"`
	PhoneNumber *string `json:"phone_number,omitempty"`
	Password    *string `json:"password,omitempty"`
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

	var req editUserRequest
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

	if ParseRole(claims.Role) != RoleSuperAdmin && ParseRole(string(userToChange.Role)) >= ParseRole(claims.Role) {
		s.logger.Warn("user edit request denied due to insufficient permissions",
			"requester", claims.Username, "requester_role", claims.Role,
			"target_user", userToChange.Username, "target_user_role", userToChange.Role)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}

	newFullName := userToChange.FullName
	newEmail := userToChange.Email.String
	newPhone := userToChange.PhoneNumber.String
	newRole := userToChange.Role

	if req.FullName != nil {
		newFullName = *req.FullName
	}
	if req.Email != nil {
		newEmail = *req.Email
	}
	if req.PhoneNumber != nil {
		newPhone = *req.PhoneNumber
	}

	if req.Role != nil {
		requestedRole := ParseRole(*req.Role)

		if requestedRole > ParseRole(claims.Role) {
			s.logger.Warn("user role change request denied due to insufficient permissions",
				"requester", claims.Username, "requester_role", claims.Role,
				"requested_role", *req.Role)
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "you are not allowed to perform this operation",
			})
			return
		}

		// A superadmin cannot demote themselves.
		if claims.Username == userToChange.Username &&
			ParseRole(claims.Role) == RoleSuperAdmin &&
			requestedRole < RoleSuperAdmin {
			s.logger.Warn("superadmin attempted to change their own role",
				"requester", claims.Username, "requested_role", *req.Role)
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "you cannot change your own role",
			})
			return
		}

		newRole = convertRoleStringToDBRole(*req.Role)
	}

	updateParams := database.UpdateUserParams{
		Username:    username,
		FullName:    newFullName,
		PhoneNumber: convertStringToPGText(newPhone),
		Email:       convertStringToPGText(newEmail),
		Role:        newRole,
		Active:      userToChange.Active,
	}

	if req.Password != nil && *req.Password != "" {
		hashed, err := bcrypt.GenerateFromPassword([]byte(*req.Password), bcrypt.DefaultCost)
		if err != nil {
			s.logger.Error("failed to hash password", "err", err)
			s.writeAPIError(w, http.StatusInternalServerError, APIError{
				Code:    ErrCodeInternalError,
				Message: "something went wrong",
			})
			return
		}
		updateParams.PasswordHash = string(hashed)
	}

	updatedUser, err := s.store.UpdateUser(r.Context(), updateParams)
	if err != nil {
		s.logger.Error("failed to update user", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusOK, registerUserResponse{
		Username:    updatedUser.Username,
		FullName:    updatedUser.FullName,
		PhoneNumber: updatedUser.PhoneNumber.String,
		Role:        string(updatedUser.Role),
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

	if ParseRole(string(userToDelete.Role)) == RoleSuperAdmin {
		s.logger.Warn("attempt to delete a superadmin account denied",
			"requester", claims.Username, "requester_role", claims.Role,
			"target_user", userToDelete.Username)
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "superadmin accounts cannot be deleted",
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

func validateEditUserRequest(req editUserRequest) map[string]string {
	fieldErrors := map[string]string{}

	if req.FullName != nil && strings.TrimSpace(*req.FullName) == "" {
		fieldErrors["full_name"] = "full name cannot be empty"
	}

	if req.Email != nil {
		if strings.TrimSpace(*req.Email) == "" {
			fieldErrors["email"] = "email cannot be empty"
		} else if !isValidEmail(*req.Email) {
			fieldErrors["email"] = "invalid email address"
		}
	}
	if req.PhoneNumber != nil && *req.PhoneNumber != "" && !isValidPhoneNumber(*req.PhoneNumber) {
		fieldErrors["phone_number"] = "invalid phone number"
	}
	if req.Role != nil && !isValidRole(*req.Role) {
		fieldErrors["role"] = "invalid role"
	}
	if req.Password != nil && *req.Password != "" && len(*req.Password) < 8 {
		fieldErrors["password"] = "password must be at least 8 characters long"
	}

	return fieldErrors
}

func validateRegisterUserRequest(req registerUserRequest) map[string]string {
	fieldErrors := map[string]string{}

	if strings.TrimSpace(req.FullName) == "" {
		fieldErrors["full_name"] = "full name is required"
	}

	if strings.TrimSpace(req.Username) == "" {
		fieldErrors["username"] = "username is required"
	}

	if strings.TrimSpace(req.Email) == "" {
		fieldErrors["email"] = "email is required"
	} else if !isValidEmail(req.Email) {
		fieldErrors["email"] = "invalid email address"
	}

	if req.PhoneNumber != "" && !isValidPhoneNumber(req.PhoneNumber) {
		fieldErrors["phone_number"] = "invalid phone number"
	}

	if !isValidRole(req.Role) {
		fieldErrors["role"] = "invalid role"
	}

	if len(req.Password) < 8 {
		fieldErrors["password"] = "password must be at least 8 characters long"
	}

	return fieldErrors
}
