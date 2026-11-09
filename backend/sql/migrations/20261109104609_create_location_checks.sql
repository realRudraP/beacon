-- +goose Up
CREATE TABLE location_checks (
    id                    UUID             PRIMARY KEY,
    attendance_record_id  UUID             NOT NULL REFERENCES attendance_records(id) ON DELETE CASCADE,
    user_id               UUID             NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    captured_at           TIMESTAMPTZ      NOT NULL,
    latitude              DOUBLE PRECISION NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude             DOUBLE PRECISION NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    accuracy              DOUBLE PRECISION,
    sequence_number       SMALLINT         NOT NULL CHECK (sequence_number BETWEEN 1 AND 6),
    within_geofence       BOOLEAN          NOT NULL,
    nearest_geofence_id   UUID             REFERENCES geofences(id) ON DELETE SET NULL,
    nearest_geofence_name TEXT,
    distance_meters       DOUBLE PRECISION,
    created_at            TIMESTAMPTZ      NOT NULL DEFAULT now(),
    UNIQUE (attendance_record_id, sequence_number)
);

CREATE INDEX idx_location_checks_user_captured ON location_checks(user_id, captured_at);
CREATE INDEX idx_location_checks_attendance ON location_checks(attendance_record_id, captured_at);

-- +goose Down
DROP TABLE location_checks;
