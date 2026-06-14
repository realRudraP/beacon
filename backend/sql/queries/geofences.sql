-- name: GetAllGeofences :many
SELECT * FROM geofences ORDER BY created_at DESC;

-- name: CreateGeofence :one
INSERT INTO geofences (
    id, name, center_lat, center_lng, radius
) VALUES (
    $1, $2, $3, $4, $5
)
RETURNING *;

-- name: UpdateGeofence :one
UPDATE geofences
SET
    name = $2,
    center_lat = $3,
    center_lng = $4,
    radius = $5,
    updated_at = now()
WHERE id = $1
RETURNING *;

-- name: DeleteGeofence :exec
DELETE FROM geofences WHERE id = $1;
