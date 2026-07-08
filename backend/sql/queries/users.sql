-- name: CreateSuperAdmin :one
INSERT INTO users(
 username, password_hash, full_name, email,role)
    VALUES (
        $1, $2, $3, $4, 'superadmin'
    )
RETURNING *;
-- name: CreateUser :one
INSERT INTO users(
 username, password_hash, full_name, phone_number, email, role
) VALUES (
    $1, $2, $3, $4, $5, $6
)
RETURNING *;

-- name: GetUserByUsername :one
SELECT * FROM users WHERE username = $1;

-- name: GetUserByID :one
SELECT * FROM users WHERE id = $1;

-- name: UpdateLastLogin :exec
UPDATE users
SET last_login = now()
WHERE id = $1;

-- name: CheckSetupRequired :one
SELECT NOT EXISTS (
    SELECT 1 FROM users
) AS setup_required;

-- name: GetAllUsers :many
SELECT * FROM users WHERE active=true ORDER BY created_at DESC;

-- name: UpdateUser :one
UPDATE users
SET full_name = $2, phone_number = $3, email = $4, role = $5, active = $6, password_hash = COALESCE($7, password_hash), weekly_off_days = COALESCE($8, weekly_off_days), updated_at = now()
WHERE username = $1 RETURNING *;
