package api

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"net/http"
	"time"

	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
	"golang.org/x/crypto/bcrypt"
)

type loginUserRequest struct {
	Username   string `json:"username"`
	Password   string `json:"password"`
	DeviceInfo string `json:"device_info"`
}

type loginUserResponse struct {
	AccessToken  string `json:"access_token"`
	RefreshToken string `json:"refresh_token"`
}

func (s *Server) LoginUser(w http.ResponseWriter, r *http.Request) {
	var req loginUserRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request body",
		})
		return
	}

	if fieldErrors := validateLoginUserRequest(req); len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeValidationError,
			Message: "validation failed",
			Fields:  fieldErrors,
		})
		return
	}

	user, err := s.store.GetUserByUsername(r.Context(), req.Username)
	if err != nil {
		s.writeAPIError(w, http.StatusUnauthorized, APIError{
			Code:    ErrUnauthorized,
			Message: "invalid credentials",
		})
		return
	}

	if err := bcrypt.CompareHashAndPassword([]byte(user.PasswordHash), []byte(req.Password)); err != nil {
		s.writeAPIError(w, http.StatusUnauthorized, APIError{
			Code:    ErrUnauthorized,
			Message: "invalid credentials",
		})
		return
	}
	accessTokenClaims := token.Claims{
		UserID:    user.ID,
		Username:  user.Username,
		Role:      string((user.Role)),
		TokenType: token.ACCESS_TOKEN,
	}

	accessToken, err := s.tokenMaker.CreateToken(accessTokenClaims, token.ACCESS_TOKEN_DURATION)
	if err != nil {
		s.logger.Error("failed to create access token", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	refreshTokenClaims := token.Claims{
		UserID:    user.ID,
		Username:  user.Username,
		Role:      string((user.Role)),
		TokenType: token.REFRESH_TOKEN,
	}
	refreshTokenDuration := token.REFRESH_TOKEN_DURATION
	if user.Role == database.RoleAdmin || user.Role == database.RoleManager {
		refreshTokenDuration = token.ADMIN_REFRESH_TOKEN_DURATION
	}
	refreshToken, err := s.tokenMaker.CreateToken(refreshTokenClaims, refreshTokenDuration)
	if err != nil {
		s.logger.Error("failed to create refresh token", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	refreshTokenHash, err := hashRefreshToken(refreshToken, []byte(s.REFRESH_TOKEN_HASH_SECRET))
	if err != nil {
		s.logger.Error("failed to hash refresh token", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	refreshTokenStorage := database.CreateRefreshTokenParams{
		UserID:     user.ID,
		TokenHash:  string(refreshTokenHash),
		DeviceInfo: convertStringToPGText(req.DeviceInfo),
		ExpiresAt:  convertTimeToPGTimeTz(time.Now().Add(refreshTokenDuration)),
	}

	if _, err := s.store.CreateRefreshToken(r.Context(), refreshTokenStorage); err != nil {
		s.logger.Error("failed to store refresh token", "err", err, "user", user, "token_info", refreshTokenStorage)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return

	}
	if err := s.store.UpdateLastLogin(r.Context(), user.ID); err != nil {
		s.logger.Error("failed to update last login", "err", err, "user", user)
		// Not returning error to avoid login failure due to last login update failure
	}
	s.writeJSON(w, http.StatusOK, loginUserResponse{
		AccessToken:  accessToken,
		RefreshToken: refreshToken,
	})

}

func validateLoginUserRequest(req loginUserRequest) FieldError {

	fieldErrors := make(FieldError)

	if req.Username == "" {
		fieldErrors["username"] = "username is required"
	}
	if req.Password == "" {
		fieldErrors["password"] = "password is required"
	}
	if req.DeviceInfo == "" {
		fieldErrors["device_info"] = "device_info is required"
	}
	return fieldErrors
}

func convertStringToPGText(s string) pgtype.Text {
	return pgtype.Text{
		String: s,
		Valid:  true,
	}
}
func convertTimeToPGTimeTz(t time.Time) pgtype.Timestamptz {
	return pgtype.Timestamptz{
		Time:  t,
		Valid: true,
	}
}

func hashRefreshToken(token string, secret []byte) (string, error) {
	h := hmac.New(sha256.New, secret)
	_, err := h.Write([]byte(token))
	if err != nil {
		return "", err
	}
	return base64.StdEncoding.EncodeToString(h.Sum(nil)), nil
}
