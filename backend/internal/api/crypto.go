package api

import (
	"crypto/hmac"
	"crypto/sha256"
)

func HMACSHA256(token string, secret []byte) ([]byte, error) {
	h := hmac.New(sha256.New, secret)
	_, err := h.Write([]byte(token))
	if err != nil {
		return nil, err
	}
	return h.Sum(nil), nil
}
