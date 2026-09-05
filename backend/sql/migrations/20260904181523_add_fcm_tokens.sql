-- +goose Up
ALTER TABLE users ADD COLUMN fcm_token TEXT;

-- +goose Down
ALTER TABLE users DROP COLUMN fcm_token;
