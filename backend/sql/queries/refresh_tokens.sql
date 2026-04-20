-- name: CreateRefreshToken :one
INSERT INTO refresh_tokens( token_hash, user_id, device_info, expires_at )
VALUES ( $1, $2, $3, $4 )
RETURNING *;

-- name: GetRefreshToken :one
SELECT * FROM refresh_tokens
WHERE token_hash = $1;

-- name: RevokeRefreshToken :exec
UPDATE refresh_tokens
SET revoked = true, revoked_reason = $2
WHERE token_hash = $1;

-- name: RevokeAllUserRefreshTokens :exec
UPDATE refresh_tokens
SET revoked = true, revoked_reason = $2
WHERE user_id = $1 AND revoked = false;

-- name: DeleteRefreshToken :exec
DELETE FROM refresh_tokens
WHERE token_hash = $1;
