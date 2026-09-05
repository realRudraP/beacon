package api

import (
	"encoding/json"
	"net/http"

	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

type getUserProfileResponse struct {
	ID            string  `json:"id"`
	Username      string  `json:"username"`
	FullName      string  `json:"full_name"`
	PhoneNumber   string  `json:"phone_number"`
	Role          string  `json:"role"`
	Active        bool    `json:"active"`
	Email         string  `json:"email"`
	WeeklyOffDays []int16 `json:"weekly_off_days"`
}

func (s *Server) GetAllUsers(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)
	user, err := s.store.GetUserByID(r.Context(), claims.UserID)
	if err != nil {
		s.logger.Error("failed to get requesters profile", "err", err)
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "something went wrong",
		})
		return
	}
	if ParseRole(string(user.Role)) < RoleAdmin {
		s.logger.Warn("unauthorized access to GetAllUsers")
		s.writeAPIError(w, http.StatusUnauthorized, APIError{
			Code:    ErrUnauthorized,
			Message: "you are not allowed to perform this operation",
		})
		return
	}
	users, err := s.store.GetAllUsers(r.Context())
	if err != nil {
		s.logger.Error("failed to get all users", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	var resp []getUserProfileResponse
	for _, user := range users {
		resp = append(resp, getUserProfileResponse{
			ID:            user.ID.String(),
			Username:      user.Username,
			FullName:      user.FullName,
			PhoneNumber:   user.PhoneNumber.String,
			Role:          string(user.Role),
			Email:         user.Email.String,
			Active:        user.Active,
			WeeklyOffDays: user.WeeklyOffDays,
		})
	}
	s.writeJSON(w, http.StatusOK, resp)
}

func (s *Server) GetUserProfile(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)
	user, err := s.store.GetUserByID(r.Context(), claims.UserID)
	if err != nil {
		s.logger.Error("failed to get user profile", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	s.writeJSON(w, http.StatusOK, getUserProfileResponse{
		ID:            user.ID.String(),
		Username:      user.Username,
		FullName:      user.FullName,
		PhoneNumber:   user.PhoneNumber.String,
		Role:          string(user.Role),
		Email:         user.Email.String,
		Active:        user.Active,
		WeeklyOffDays: user.WeeklyOffDays,
	})
}

type updateFCMTokenRequest struct {
	FCMToken string `json:"fcm_token"`
}

func (s *Server) handleUpdateFCMToken(w http.ResponseWriter, r *http.Request) {
	var req updateFCMTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil || req.FCMToken == "" {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "fcm_token is required",
		})
		return
	}

	claims := r.Context().Value("authClaims").(*token.Claims)

	err := s.store.UpdateUserFCMToken(r.Context(), database.UpdateUserFCMTokenParams{
		ID:       claims.UserID,
		FcmToken: stringToPGText(req.FCMToken),
	})
	if err != nil {
		s.logger.Error("failed to update FCM token", "user_id", claims.UserID, "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	s.writeJSON(w, http.StatusOK, map[string]string{"status": "ok"})
}
