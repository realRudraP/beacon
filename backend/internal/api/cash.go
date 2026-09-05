package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgtype"
	"gitlab.com/realRudraP/beacon/backend/internal/database"
	"gitlab.com/realRudraP/beacon/backend/internal/token"
)

type createCashTransactionRequest struct {
	ID              string  `json:"id"`
	UserID          string  `json:"user_id,omitempty"` // Required for admins, ignored/forbidden for workers
	Type            string  `json:"type"`              // "credit" or "debit"
	Amount          float64 `json:"amount"`
	Category        string  `json:"category"`
	Description     string  `json:"description,omitempty"`
	ReceiptPhotoURI string  `json:"receipt_photo_uri,omitempty"`
	CreatedAt       string  `json:"created_at,omitempty"`
}

type cashLedgerResponse struct {
	Balance      float64                    `json:"balance"`
	Transactions []database.CashTransaction `json:"transactions"`
}

// ---------------------------------------------------------------------------
// GET /cash/transactions
// ---------------------------------------------------------------------------

func (s *Server) handleGetMyCashTransactions(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)

	targetUserID := claims.UserID

	// If manager/admin wants to view a specific worker's ledger
	targetUserParam := r.URL.Query().Get("user_id")
	if targetUserParam != "" {
		if claims.Role == "worker" {
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "workers can only view their own cash ledger",
			})
			return
		}
		parsedID, err := uuid.Parse(targetUserParam)
		if err != nil {
			s.writeAPIError(w, http.StatusBadRequest, APIError{
				Code:    ErrCodeInvalidRequest,
				Message: "invalid target user_id",
			})
			return
		}
		targetUserID = parsedID
	}

	transactions, err := s.store.GetCashTransactionsByUserID(r.Context(), targetUserID)
	if err != nil {
		s.logger.Error("failed to fetch cash transactions", "user_id", targetUserID, "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	balance, err := s.store.GetCashBalanceByUserID(r.Context(), targetUserID)
	if err != nil {
		s.logger.Error("failed to calculate cash balance", "user_id", targetUserID, "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if transactions == nil {
		transactions = []database.CashTransaction{}
	}

	s.writeJSON(w, http.StatusOK, cashLedgerResponse{
		Balance:      balance,
		Transactions: transactions,
	})
}

// ---------------------------------------------------------------------------
// POST /cash/transactions
// ---------------------------------------------------------------------------

func (s *Server) handleCreateCashTransaction(w http.ResponseWriter, r *http.Request) {
	var req createCashTransactionRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		s.writeAPIError(w, http.StatusBadRequest, APIError{
			Code:    ErrCodeInvalidRequest,
			Message: "invalid request payload",
		})
		return
	}

	fieldErrors := make(map[string]string)

	transactionID, err := uuid.Parse(req.ID)
	if err != nil {
		fieldErrors["id"] = "id must be a valid UUID"
	}

	if req.Type != "credit" && req.Type != "debit" {
		fieldErrors["type"] = "type must be 'credit' or 'debit'"
	}

	if req.Amount <= 0 {
		fieldErrors["amount"] = "amount must be greater than 0"
	}

	if req.Category == "" {
		fieldErrors["category"] = "category is required"
	}

	createdAt := time.Now()
	if req.CreatedAt != "" {
		parsedTime, parseErr := time.Parse(time.RFC3339, req.CreatedAt)
		if parseErr != nil {
			fieldErrors["created_at"] = "created_at must be an RFC3339 timestamp"
		} else {
			createdAt = parsedTime
		}
	}

	claims := r.Context().Value("authClaims").(*token.Claims)

	var targetUserID uuid.UUID

	if claims.Role == "worker" {
		// Worker is always forced to their own user ID and can only spend (debit)
		targetUserID = claims.UserID
		if req.Type == "credit" {
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "workers cannot create cash top-ups/credits",
			})
			return
		}
		if req.UserID != "" && req.UserID != claims.UserID.String() {
			s.writeAPIError(w, http.StatusForbidden, APIError{
				Code:    ErrUnauthorized,
				Message: "workers cannot record transactions for other accounts",
			})
			return
		}
	} else {
		// Admin / Manager MUST provide the target worker's user_id
		if req.UserID == "" {
			fieldErrors["user_id"] = "user_id is required when creating transactions as an admin"
		} else {
			parsedID, parseErr := uuid.Parse(req.UserID)
			if parseErr != nil {
				fieldErrors["user_id"] = "user_id must be a valid UUID"
			} else {
				targetUserID = parsedID
			}
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

	var amountNumeric pgtype.Numeric
	_ = amountNumeric.Scan(fmt.Sprintf("%.2f", req.Amount))

	params := database.InsertCashTransactionParams{
		ID:              transactionID,
		UserID:          targetUserID,
		Type:            database.CashTransactionType(req.Type),
		Amount:          amountNumeric,
		Category:        req.Category,
		Description:     stringToPGText(req.Description),
		ReceiptPhotoUri: stringToPGText(req.ReceiptPhotoURI),
		CreatedBy:       claims.UserID,
		CreatedAt:       convertTimeToPGTimeTz(createdAt),
	}

	record, err := s.store.InsertCashTransaction(r.Context(), params)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			existing, getErr := s.store.GetCashTransactionByID(r.Context(), transactionID)
			if getErr != nil {
				s.logger.Error("failed to get existing transaction after conflict", "err", getErr)
				s.writeAPIError(w, http.StatusInternalServerError, APIError{
					Code:    ErrCodeInternalError,
					Message: "something went wrong",
				})
				return
			}
			s.writeJSON(w, http.StatusOK, existing)
			return
		}

		s.logger.Error("failed to insert cash transaction", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if req.Type == "credit" {
		// Run asynchronously so notification latency doesn't block the API response
		go func(targetID uuid.UUID, amount float64, category string) {
			fcmToken, err := s.store.GetUserFCMToken(context.Background(), targetID)
			if err != nil || !fcmToken.Valid || fcmToken.String == "" {
				return
			}

			title := fmt.Sprintf("₹%.2f Petty Cash Added", amount)
			body := fmt.Sprintf("Your account has been credited with ₹%.2f for %s.", amount, category)

			s.sendPushNotification(fcmToken.String, title, body)
		}(targetUserID, req.Amount, req.Category)
	}

	s.writeJSON(w, http.StatusCreated, record)
}

// handleGetWorkersCashSummary handles GET /cash/summary (Admin/Superadmin only)
func (s *Server) handleGetWorkersCashSummary(w http.ResponseWriter, r *http.Request) {
	claims := r.Context().Value("authClaims").(*token.Claims)

	// Guard: Only admins/superadmins can see the whole farm cash summary
	if claims.Role != "admin" && claims.Role != "superadmin" {
		s.writeAPIError(w, http.StatusForbidden, APIError{
			Code:    ErrUnauthorized,
			Message: "only administrators can access the petty cash summary",
		})
		return
	}

	summary, err := s.store.GetAllWorkersCashSummary(r.Context())
	if err != nil {
		s.logger.Error("failed to fetch workers cash summary", "err", err)
		s.writeAPIError(w, http.StatusInternalServerError, APIError{
			Code:    ErrCodeInternalError,
			Message: "something went wrong",
		})
		return
	}

	if summary == nil {
		summary = []database.GetAllWorkersCashSummaryRow{}
	}

	s.writeJSON(w, http.StatusOK, summary)
}
