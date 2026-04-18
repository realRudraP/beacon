-- +goose Up

CREATE TYPE role AS ENUM ('worker', 'manager', 'admin');
CREATE TABLE users(
    id UUID PRIMARY KEY,
    username TEXT UNIQUE NOT NULL,
    password_hash TEXT NOT NULL,
    role role NOT NULL DEFAULT 'worker',
    full_name TEXT NOT NULL,
    phone_number TEXT NOT NULL,
    active BOOL NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    last_login timestamptz
    );

CREATE TABLE refresh_tokens(
    token_hash TEXT PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    device_info TEXT,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked BOOL NOT NULL DEFAULT false,
    revoked_reason TEXT
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);

CREATE TABLE attendance_records(
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id) ON DELETE CASCADE,
    check_in timestamptz NOT NULL,
    check_in_photo_uri TEXT,
    check_in_lat DOUBLE PRECISION,
    check_in_long DOUBLE PRECISION,

    check_out timestamptz,
    check_out_photo_uri TEXT,
    check_out_lat DOUBLE PRECISION,
    check_out_long DOUBLE PRECISION,

    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_attendance_user_id ON attendance_records(user_id);
CREATE INDEX idx_attendance_check_in ON attendance_records(check_in);

-- +goose Down
DROP TABLE attendance_records;
DROP TABLE refresh_tokens;
DROP TABLE users;
DROP TYPE role;
