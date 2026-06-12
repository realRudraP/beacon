-- name: CreateVerificationToken :one
INSERT INTO verification_tokens (type,token_hash, user_id,created_at, expires_at)
VALUES ($1, $2, $3, $4, $5)
RETURNING *;
