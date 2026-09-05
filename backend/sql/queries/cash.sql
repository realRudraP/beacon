-- name: InsertCashTransaction :one
INSERT INTO cash_transactions (
   id,
   user_id,
   type,
   amount,
   category,
   description,
   receipt_photo_uri,
   created_by,
   created_at
) VALUES (
$1, $2, $3, $4, $5, $6, $7, $8, $9
)
ON CONFLICT(id) DO NOTHING
RETURNING *;

-- name: GetCashTransactionsByUserID :many
SELECT
    id,
    user_id,
    type,
    amount,
    category,
    description,
    receipt_photo_uri,
    created_by,
    created_at
FROM cash_transactions
WHERE user_id = $1
ORDER BY created_at DESC;

-- name: GetCashBalanceByUserID :one
SELECT
    COALESCE(
        SUM(
            CASE
                WHEN type = 'credit' THEN amount
                ELSE -amount
            END
        ),
        0
    )::FLOAT8 AS balance
FROM cash_transactions
WHERE user_id = $1;


-- name: GetCashTransactionByID :one
SELECT
    id,
    user_id,
    type,
    amount,
    category,
    description,
    receipt_photo_uri,
    created_by,
    created_at
FROM cash_transactions
WHERE id = $1;

-- name: GetAllWorkersCashSummary :many
SELECT
    u.id AS user_id,
    u.full_name,
    u.phone_number,
    u.active,
    COALESCE(SUM(CASE WHEN ct.type = 'credit' THEN ct.amount ELSE -ct.amount END), 0)::FLOAT8 AS current_balance,
    COALESCE(SUM(CASE WHEN ct.type = 'credit' THEN ct.amount ELSE 0 END), 0)::FLOAT8 AS total_credited,
    COALESCE(SUM(CASE WHEN ct.type = 'debit' THEN ct.amount ELSE 0 END), 0)::FLOAT8 AS total_debited,
    MAX(ct.created_at) AS last_transaction_at
FROM users u
LEFT JOIN cash_transactions ct ON ct.user_id = u.id
WHERE u.role = 'worker' AND u.active = true
GROUP BY u.id
ORDER BY u.full_name ASC;
