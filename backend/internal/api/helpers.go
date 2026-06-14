package api

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
	"net/mail"
	"regexp"
)

// --------------------HTTP Helpers--------------------

func (s *Server) writeJSON(w http.ResponseWriter, status int, data any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(data)
}

func (s *Server) writeAPIError(w http.ResponseWriter, statusCode int, err APIError) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(statusCode)
	_ = json.NewEncoder(w).Encode(err)
}

// -------------------- Encoding Helpers---------------------

func convertByteArrayToBase64String(b []byte) string {
	return base64.StdEncoding.EncodeToString(b)
}

func ParseRole(roleStr string) Role {
	switch roleStr {
	case "superadmin":
		return RoleSuperAdmin
	case "admin":
		return RoleAdmin
	default:
		return RoleWorker
	}
}

// ---------------------------------------------------------------------------
// Validators
// ---------------------------------------------------------------------------

func isValidEmail(email string) bool {
	addr, err := mail.ParseAddress(email)
	if err != nil {
		return false
	}
	// Ensure that the parsed email address matches the original input
	// to avoid cases where might accept emails of the form
	// "John Doe <john@doe.com>" as valid.
	return addr.Address == email
}

var mobileNumberRegex = regexp.MustCompile(`^\+?[1-9]\d{7,14}$`)

func isValidPhoneNumber(phoneNumber string) bool {
	return mobileNumberRegex.MatchString(phoneNumber)
}

var validRoles = map[string]bool{
	"superadmin": true,
	"admin":      true,
	"worker":     true,
}

func isValidRole(role string) bool {
	return validRoles[role]
}
