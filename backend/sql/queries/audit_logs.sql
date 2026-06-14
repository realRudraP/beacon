-- name: GetAllAuditLogs :many
SELECT * FROM audit_logs ORDER BY created_at DESC;

-- name: CreateAuditLog :one
INSERT INTO audit_logs (
    id, user_id, action, entity_type, entity_id, old_value, new_value, description
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8
)
RETURNING *;
