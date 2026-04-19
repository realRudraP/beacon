-- name: CreateUser :one
INSERT INTO users(
 username, password_hash, full_name, phone_number
) VALUES (
    $1, $2, $3, $4
)
RETURNING *;

-- name: GetUserByUsername :one
SELECT * FROM users WHERE username = $1;
