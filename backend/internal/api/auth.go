package api

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
	"golang.org/x/crypto/bcrypt"
)

const (
	TOKEN_REVOCATION_REASON_LOGOUT         = "logout"
	TOKEN_REVOCATION_REASON_TOKEN_EXCHANGE = "token_exchange"
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
	refreshToken, accessToken, err := createTokenPairForUser(user, s.tokenMaker)
	if err != nil {
		s.logger.Error("failed to create token pair for user", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	refreshTokenHash, err := HMACSHA256(refreshToken, []byte(s.REFRESH_TOKEN_HASH_SECRET))
	if err != nil {
		s.logger.Error("failed to hash refresh token", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	refreshTokenDuration := refreshTokenDurationForRole(user.Role)
	if err := storeRefreshTokenForUser(r.Context(), refreshTokenHash, user, req.DeviceInfo, s.store, refreshTokenDuration); err != nil {
		s.logger.Error("failed to store refresh token for user", "err", err, "user", user)
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

// -------------------REFRESH TOKEN HANDLER-------------------

type RefreshTokenRequest struct {
	RefreshToken string `json:"refresh_token"`
	DeviceInfo   string `json:"device_info"`
}

func (s *Server) HandleRefreshToken(w http.ResponseWriter, r *http.Request) {
	// This handler will exchange a valid refresh token for a new access token
	//  and refresh token.
	var req RefreshTokenRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request body",
		})
		return
	}

	if fieldErrors := validateRefreshTokenRequest(req); len(fieldErrors) > 0 {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeValidationError,
			Message: "validation failed",
			Fields:  fieldErrors,
		})
		return
	}

	claims, err := s.tokenMaker.VerifyToken(req.RefreshToken)
	if err != nil || claims.TokenType != token.REFRESH_TOKEN {
		s.writeAPIError(w, http.StatusUnauthorized, APIError{
			Code:    ErrUnauthorized,
			Message: "invalid refresh token",
		})
		return
	}

	refreshTokenHash, err := HMACSHA256(req.RefreshToken, []byte(s.REFRESH_TOKEN_HASH_SECRET))
	if err != nil {
		s.logger.Error("failed to hash refresh token", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	storedToken, err := s.store.GetRefreshToken(r.Context(), convertByteArrayToBase64String(refreshTokenHash))
	if err != nil {
		if err == ErrTokenNotFound {
			s.writeAPIError(w, http.StatusUnauthorized, APIError{
				Code:    ErrUnauthorized,
				Message: "refresh token not found",
			})
			return
		}
		s.logger.Error("failed to get refresh token from store", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if storedToken.Revoked || time.Now().After(storedToken.ExpiresAt.Time) {
		s.writeAPIError(w, http.StatusUnauthorized, APIError{
			Code:    ErrUnauthorized,
			Message: "refresh token is revoked or expired",
		})
		return
	}

	user, err := s.store.GetUserByID(r.Context(), claims.UserID)
	if err != nil {
		s.logger.Error("failed to get user by ID", "err", err, "user_id", claims.UserID)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	newRefreshToken, accessToken, err := createTokenPairForUser(user, s.tokenMaker)
	if err != nil {
		s.logger.Error("failed to create token pair for user", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	newRefreshTokenHash, err := HMACSHA256(newRefreshToken, []byte(s.REFRESH_TOKEN_HASH_SECRET))
	if err != nil {
		s.logger.Error("failed to hash new refresh token", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
	}
	refreshTokenDuration := refreshTokenDurationForRole(user.Role)
	if err := storeRefreshTokenForUser(r.Context(), newRefreshTokenHash, user, req.DeviceInfo, s.store, refreshTokenDuration); err != nil {
		s.logger.Error("failed to store new refresh token", "err", err, "user", user)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}
	if err := revokeRefreshToken(r.Context(), refreshTokenHash, s.store, TOKEN_REVOCATION_REASON_TOKEN_EXCHANGE); err != nil {
		s.logger.Error("failed to revoke old refresh token", "err", err, "user", user)
		// Not returning error to avoid token exchange failure due to revocation failure
	}

	s.writeJSON(w, http.StatusOK, loginUserResponse{
		AccessToken:  accessToken,
		RefreshToken: newRefreshToken,
	})

}

func createTokenPairForUser(user database.User, tokenMaker token.Maker) (refreshToken, accessToken string, err error) {
	accessTokenClaims := token.Claims{
		UserID:    user.ID,
		Username:  user.Username,
		Role:      string(user.Role),
		TokenType: token.ACCESS_TOKEN,
	}

	accessToken, err = tokenMaker.CreateToken(accessTokenClaims, token.ACCESS_TOKEN_DURATION)
	if err != nil {
		return "", "", err
	}

	refreshTokenClaims := token.Claims{
		UserID:    user.ID,
		Username:  user.Username,
		Role:      string(user.Role),
		TokenType: token.REFRESH_TOKEN,
	}
	refreshTokenDuration := refreshTokenDurationForRole(user.Role)

	refreshToken, err = tokenMaker.CreateToken(refreshTokenClaims, refreshTokenDuration)
	if err != nil {
		return "", "", err
	}

	return refreshToken, accessToken, nil
}

func storeRefreshTokenForUser(ctx context.Context, refreshTokenHash []byte, user database.User, deviceInfo string, store Store, tokenValidFor time.Duration) error {
	// Takes a hashed refresh token and stores it in the database
	refreshTokenStorage := database.CreateRefreshTokenParams{
		UserID:     user.ID,
		TokenHash:  convertByteArrayToBase64String(refreshTokenHash),
		DeviceInfo: convertStringToPGText(deviceInfo),
		ExpiresAt:  convertTimeToPGTimeTz(time.Now().Add(tokenValidFor)),
	}
	_, err := store.CreateRefreshToken(ctx, refreshTokenStorage)

	return err
}

func revokeRefreshToken(ctx context.Context, refreshTokenHash []byte, store Store, reason string) error {
	// Takes a hashed refresh token and revokes it in the database
	if err := store.RevokeRefreshToken(ctx, database.RevokeRefreshTokenParams{
		TokenHash:     convertByteArrayToBase64String(refreshTokenHash),
		RevokedReason: convertStringToPGText(reason),
	}); err != nil {
		return err
	}
	return nil
}

func refreshTokenDurationForRole(role database.Role) time.Duration {
	switch role {
	case database.RoleAdmin:
		return token.ADMIN_REFRESH_TOKEN_DURATION
	case database.RoleManager, database.RoleWorker:
		return token.REFRESH_TOKEN_DURATION
	}
	return token.REFRESH_TOKEN_DURATION
}

// -------------------HELPER FUNCTIONS-------------------
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

func validateRefreshTokenRequest(req RefreshTokenRequest) FieldError {

	fieldErrors := make(FieldError)

	if req.RefreshToken == "" {
		fieldErrors["refresh_token"] = "refresh_token is required"
	}
	if req.DeviceInfo == "" {
		fieldErrors["device_info"] = "device_info is required"
	}
	return fieldErrors
}
