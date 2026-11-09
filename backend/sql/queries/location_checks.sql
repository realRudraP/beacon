-- name: InsertLocationCheck :one
INSERT INTO location_checks (
    id, attendance_record_id, user_id, captured_at, latitude, longitude, accuracy,
    sequence_number, within_geofence, nearest_geofence_id, nearest_geofence_name, distance_meters
) VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12
)
ON CONFLICT DO NOTHING
RETURNING *;

-- name: GetLocationCheckByID :one
SELECT *
FROM location_checks
WHERE id = $1;

-- name: ListLocationChecks :many
SELECT lc.*, u.full_name, u.email
FROM location_checks lc
JOIN users u ON u.id = lc.user_id
WHERE (sqlc.narg(user_id)::uuid IS NULL OR lc.user_id = sqlc.narg(user_id)::uuid)
  AND (sqlc.narg(attendance_record_id)::uuid IS NULL OR lc.attendance_record_id = sqlc.narg(attendance_record_id)::uuid)
  AND (sqlc.narg(from_ts)::timestamptz IS NULL OR lc.captured_at >= sqlc.narg(from_ts)::timestamptz)
  AND (sqlc.narg(to_ts)::timestamptz IS NULL OR lc.captured_at < sqlc.narg(to_ts)::timestamptz)
ORDER BY lc.captured_at DESC
LIMIT sqlc.arg('limit') OFFSET sqlc.arg('offset');

-- name: CountLocationChecks :one
SELECT COUNT(*)
FROM location_checks lc
WHERE (sqlc.narg(user_id)::uuid IS NULL OR lc.user_id = sqlc.narg(user_id)::uuid)
  AND (sqlc.narg(attendance_record_id)::uuid IS NULL OR lc.attendance_record_id = sqlc.narg(attendance_record_id)::uuid)
  AND (sqlc.narg(from_ts)::timestamptz IS NULL OR lc.captured_at >= sqlc.narg(from_ts)::timestamptz)
  AND (sqlc.narg(to_ts)::timestamptz IS NULL OR lc.captured_at < sqlc.narg(to_ts)::timestamptz);
