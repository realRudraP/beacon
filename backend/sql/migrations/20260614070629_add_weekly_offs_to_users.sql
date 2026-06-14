-- +goose Up
ALTER TABLE users ADD COLUMN weekly_off_days SMALLINT[] DEFAULT '{}';
-- +goose Down
ALTER TABLE users DROP COLUMN weekly_off_days;
