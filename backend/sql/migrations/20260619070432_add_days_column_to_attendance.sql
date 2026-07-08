-- +goose Up
ALTER TABLE attendance_records ADD COLUMN date DATE;

UPDATE attendance_records SET date = check_in::date WHERE date IS NULL;

ALTER TABLE attendance_records ALTER COLUMN date SET NOT NULL;

ALTER TABLE attendance_records
    ADD CONSTRAINT uq_attendance_records_user_date UNIQUE (user_id, date);

-- +goose Down
SELECT 'down SQL query';
