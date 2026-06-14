-- name: GetAllConfiguration :many
SELECT * FROM configuration;

-- name: GetSyncConfigByKey :one
SELECT * FROM configuration WHERE key = $1;

-- name: UpsertSyncConfig :one
INSERT INTO configuration (key, value, updated_at)
VALUES ($1, $2, now())
ON CONFLICT (key) DO UPDATE
SET
    value = EXCLUDED.value,
    updated_at = now()
RETURNING *;
