package api

import (
	"encoding/base64"
	"encoding/json"
	"net/http"
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
