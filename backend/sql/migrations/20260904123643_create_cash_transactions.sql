-- +goose Up
CREATE TYPE cash_transaction_type AS ENUM ('credit', 'debit');

CREATE TABLE cash_transactions (
    id                  UUID                  PRIMARY KEY,
    user_id             UUID                  REFERENCES users(id) ON DELETE CASCADE NOT NULL,
    type                cash_transaction_type NOT NULL,
    amount              NUMERIC(12, 2)        NOT NULL CHECK (amount > 0),
    category            TEXT                  NOT NULL,
    description         TEXT,
    receipt_photo_uri   TEXT,
    created_by          UUID                  REFERENCES users(id) NOT NULL,
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT now()
);

CREATE INDEX idx_cash_transactions_user_id ON cash_transactions(user_id);
CREATE INDEX idx_cash_transactions_created_at ON cash_transactions(created_at);

-- +goose Down
DROP TABLE cash_transactions;
DROP TYPE cash_transaction_type;
