package api_test

import (
	"bytes"
	"net/http"
	"net/http/httptest"
	"testing"
)

const (
	loginUserPath    = "/api/v1/auth/login"
	refreshTokenPath = "/api/v1/auth/refresh"
)

// registerAndLogin is a test helper that registers a user and logs in,
// returning the access and refresh tokens.
func registerAndLogin(t *testing.T, handler http.Handler, username, password, deviceInfo string) (accessToken, refreshToken string) {
	t.Helper()
	postJSON(t, handler, registerUserPath, map[string]string{
		"username":     username,
		"full_name":    "Test User",
		"phone_number": "9999999999",
		"password":     password,
	})
	rr := postJSON(t, handler, loginUserPath, map[string]string{
		"username":    username,
		"password":    password,
		"device_info": deviceInfo,
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("registerAndLogin: login failed with %d: %s", rr.Code, rr.Body.String())
	}
	var resp map[string]any
	decodeJSON(t, rr, &resp)
	accessToken, _ = resp["access_token"].(string)
	refreshToken, _ = resp["refresh_token"].(string)
	if accessToken == "" || refreshToken == "" {
		t.Fatal("registerAndLogin: missing tokens in login response")
	}
	return accessToken, refreshToken
}

// --- TestLoginUser ---

func TestLoginUser(t *testing.T) {
	server, _ := newTestServer(t)
	handler := server.Routes()

	postJSON(t, handler, registerUserPath, map[string]string{
		"username":     "alice",
		"full_name":    "Alice Smith",
		"phone_number": "9999999999",
		"password":     "securepassword",
	})

	t.Run("valid credentials returns access and refresh tokens", func(t *testing.T) {
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
			"device_info": "Alice's iPhone",
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

	t.Run("missing password returns 400 with field error", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"device_info": "Alice's iPhone",
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		if _, ok := fields["password"]; !ok {
			t.Fatal("expected password field error")
		}
	})

	t.Run("missing device_info returns 400 with field error", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{
			"username": "alice",
			"password": "securepassword",
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		if _, ok := fields["device_info"]; !ok {
			t.Fatal("expected device_info field error")
		}
	})

	t.Run("all fields missing returns 400 with all field errors", func(t *testing.T) {
		rr := postJSON(t, handler, loginUserPath, map[string]string{})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		for _, field := range []string{"username", "password", "device_info"} {
			if _, ok := fields[field]; !ok {
				t.Fatalf("expected field error for %q", field)
			}
		}
	})

	t.Run("malformed JSON returns 400", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, loginUserPath, bytes.NewBufferString("{invalid}"))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", rr.Code)
		}
	})

	t.Run("empty body returns 400", func(t *testing.T) {
		req := httptest.NewRequest(http.MethodPost, loginUserPath, bytes.NewBufferString(""))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", rr.Code)
		}
	})

	t.Run("multiple logins produce distinct refresh tokens", func(t *testing.T) {
		rr1 := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"password":    "securepassword",
			"device_info": "Device A",
		})
		rr2 := postJSON(t, handler, loginUserPath, map[string]string{
			"username":    "alice",
			"password":    "securepassword",
			"device_info": "Device B",
		})
		var resp1, resp2 map[string]any
		decodeJSON(t, rr1, &resp1)
		decodeJSON(t, rr2, &resp2)
		token1, _ := resp1["refresh_token"].(string)
		token2, _ := resp2["refresh_token"].(string)
		if token1 == token2 {
			t.Fatal("expected distinct refresh tokens for separate logins")
		}
	})
}

// --- TestRefreshToken ---

func TestRefreshToken(t *testing.T) {
	t.Run("valid refresh token returns new token pair", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		_, refreshToken := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": refreshToken,
			"device_info":   "Alice's iPhone",
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

	t.Run("new refresh token is distinct from old one", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		_, oldRefreshToken := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": oldRefreshToken,
			"device_info":   "Alice's iPhone",
		})
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		newRefreshToken, _ := resp["refresh_token"].(string)
		if newRefreshToken == oldRefreshToken {
			t.Fatal("expected new refresh token to differ from old one")
		}
	})

	t.Run("reuse of exchanged refresh token returns 401 (rotation enforcement)", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		_, oldRefreshToken := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		// First use — should succeed and revoke oldRefreshToken
		postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": oldRefreshToken,
			"device_info":   "Alice's iPhone",
		})

		// Second use of same token — must be rejected
		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": oldRefreshToken,
			"device_info":   "Alice's iPhone",
		})
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401 on reuse of exchanged token, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("garbage token string returns 401", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": "this.is.garbage",
			"device_info":   "Some Device",
		})
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("access token presented as refresh token returns 401", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		accessToken, _ := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": accessToken,
			"device_info":   "Alice's iPhone",
		})
		if rr.Code != http.StatusUnauthorized {
			t.Fatalf("expected 401 when using access token as refresh token, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("missing refresh_token returns 400 with field error", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"device_info": "Alice's iPhone",
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		if _, ok := fields["refresh_token"]; !ok {
			t.Fatal("expected refresh_token field error")
		}
	})

	t.Run("missing device_info returns 400 with field error", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		_, refreshToken := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{
			"refresh_token": refreshToken,
		})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
		var resp map[string]any
		decodeJSON(t, rr, &resp)
		fields, ok := resp["fields"].(map[string]any)
		if !ok {
			t.Fatal("expected fields object in response")
		}
		if _, ok := fields["device_info"]; !ok {
			t.Fatal("expected device_info field error")
		}
	})

	t.Run("all fields missing returns 400", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()

		rr := postJSON(t, handler, refreshTokenPath, map[string]string{})
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d: %s", rr.Code, rr.Body.String())
		}
	})

	t.Run("malformed JSON returns 400", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()

		req := httptest.NewRequest(http.MethodPost, refreshTokenPath, bytes.NewBufferString("{invalid}"))
		req.Header.Set("Content-Type", "application/json")
		rr := httptest.NewRecorder()
		handler.ServeHTTP(rr, req)
		if rr.Code != http.StatusBadRequest {
			t.Fatalf("expected 400, got %d", rr.Code)
		}
	})

	t.Run("chained refresh produces valid token each time", func(t *testing.T) {
		server, _ := newTestServer(t)
		handler := server.Routes()
		_, currentRefreshToken := registerAndLogin(t, handler, "alice", "securepassword", "Alice's iPhone")

		for i := range 3 {
			rr := postJSON(t, handler, refreshTokenPath, map[string]string{
				"refresh_token": currentRefreshToken,
				"device_info":   "Alice's iPhone",
			})
			if rr.Code != http.StatusOK {
				t.Fatalf("chain refresh %d: expected 200, got %d: %s", i+1, rr.Code, rr.Body.String())
			}
			var resp map[string]any
			decodeJSON(t, rr, &resp)
			next, _ := resp["refresh_token"].(string)
			if next == "" {
				t.Fatalf("chain refresh %d: missing refresh_token in response", i+1)
			}
			currentRefreshToken = next
		}
	})
}
