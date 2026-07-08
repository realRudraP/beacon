-- name: UpdateAttendancePhoto :exec
UPDATE attendance_records
SET
    check_in_photo_uri  = CASE WHEN @photo_type::text = 'in'  THEN @photo_uri::text ELSE check_in_photo_uri  END,
    check_out_photo_uri = CASE WHEN @photo_type::text = 'out' THEN @photo_uri::text ELSE check_out_photo_uri END,
    updated_at = now()
WHERE id = @id::uuid;

-- name: GetAttendanceRecordByID :one
SELECT *
FROM attendance_records
WHERE id = $1;

-- name: InsertAttendanceRecord :one
INSERT INTO attendance_records (
    id, user_id, date, check_in, check_in_photo_uri, check_in_lat, check_in_long, check_out, check_out_photo_uri, check_out_lat, check_out_long)
VALUES (
    $1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11
)
ON CONFLICT (id) DO NOTHING
RETURNING *;

-- name: UpdateAttendanceRecord :one
UPDATE attendance_records
SET
    check_in_photo_uri  = COALESCE($2, check_in_photo_uri),
    check_out = COALESCE($3, check_out),
    check_out_photo_uri = COALESCE($4, check_out_photo_uri),
    check_out_lat       = COALESCE($5, check_out_lat),
    check_out_long      = COALESCE($6, check_out_long),
    updated_at          = now()
WHERE id = $1
RETURNING *;
-- name: GetAttendanceRecordsForUser :many
SELECT * FROM attendance_records
WHERE user_id = $1
ORDER BY date DESC
LIMIT $2 OFFSET $3;

-- name: GetAttendanceRecordsForDateRange :many
SELECT * FROM attendance_records
WHERE date BETWEEN $1 AND $2
ORDER BY date DESC
LIMIT $3 OFFSET $4;

-- name: GetAttendanceRecordsForUserAndDateRange :many
SELECT * FROM attendance_records
WHERE user_id = $1 AND date BETWEEN $2 AND $3
ORDER BY date DESC
LIMIT $4 OFFSET $5;

-- name: CountAttendanceRecordsForUser :one
SELECT COUNT(*) FROM attendance_records
WHERE user_id = $1;

-- name: CountAttendanceRecordsForDateRange :one
SELECT COUNT(*) FROM attendance_records
WHERE date BETWEEN $1 AND $2;

-- name: CountAttendanceRecordsForUserAndDateRange :one
SELECT COUNT(*) FROM attendance_records
WHERE user_id = $1 AND date BETWEEN $2 AND $3;

-- name: GetAttendanceRecordsForDateRangeWithUser :many
SELECT ar.*, u.full_name, u.email
FROM attendance_records ar
JOIN users u ON u.id = ar.user_id
WHERE ar.date BETWEEN $1 AND $2
ORDER BY ar.date DESC
LIMIT $3 OFFSET $4;

-- name: GetUsersWithoutAttendanceForDate :many
SELECT u.id, u.full_name, u.email
FROM users u
LEFT JOIN attendance_records ar
  ON ar.user_id = u.id AND ar.date = $1
WHERE ar.id IS NULL;

-- name: GetOpenAttendanceRecordsForDate :many
SELECT ar.id, ar.user_id, ar.check_in, ar.check_in_photo_uri, ar.check_in_lat, ar.check_in_long,
       ar.check_out, ar.check_out_photo_uri, ar.check_out_lat, ar.check_out_long,
       ar.created_at, ar.updated_at, ar.date, u.full_name, u.email
FROM attendance_records ar
JOIN users u ON u.id = ar.user_id
WHERE ar.date = $1 AND ar.check_out IS NULL
ORDER BY ar.check_in ASC;

-- name: GetStaleOpenAttendanceRecords :many
SELECT ar.id, ar.user_id, ar.check_in, ar.check_in_photo_uri, ar.check_in_lat, ar.check_in_long,
       ar.check_out, ar.check_out_photo_uri, ar.check_out_lat, ar.check_out_long,
       ar.created_at, ar.updated_at, ar.date, u.full_name, u.email
FROM attendance_records ar
JOIN users u ON u.id = ar.user_id
WHERE ar.check_out IS NULL AND ar.created_at < $1
ORDER BY ar.created_at ASC;
