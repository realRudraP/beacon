-- name: CreateUser :one
INSERT INTO users(
    id, username, password_hash, full_name, phone_number, role
) VALUES (
    $1, $2, $3, $4, $5, $6
)
RETURNING *;
