package api_test

import (
	"net/http"
	"testing"
)

var (
	loginUserPath = "/api/v1/auth/login"
)

func TestLoginUser(t *testing.T) {
	server, _ := newTestServer(t)
	handler := server.Routes()

	// pre-register a account to log in as
	postJSON(t, handler, registerUserPath, map[string]string{
		"username":     "alice",
		"full_name":    "Alice Smith",
		"phone_number": "9999999999",
		"password":     "securepassword",
	})

	t.Run("valid credentials returns tokens", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"password":    "securepassword",
			"device_info": "Alice's iPhone",
		})
		if rr.Code != http.StatusOK {
			t.Fatalf("expected 200, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		if _, ok := resp["access_token"]; !ok {
			t.Fatal("expected access_token in response")
		}
		if _, ok := resp["refresh_token"]; !ok {
			t.Fatal("expected refresh_token in response")
		}
	})

	t.Run("wrong password returns 401", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"password":    "wrongpassword",
			"device_info": "Alice's G15",
		})
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("non-existent user returns 401", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "ghost",
			"password":    "doesntmatter",
			"device_info": "Unknown Device",
		})
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("missing fields returns 400", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username": "alice",
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
	})

}
