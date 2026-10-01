ALTER TABLE payment_methods ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE payment_methods ADD COLUMN select_default_on_connect BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE payment_methods AS method
SET is_default = TRUE
FROM (
    SELECT DISTINCT ON (user_id) id
    FROM payment_methods
    WHERE status = 'ACTIVE'
    ORDER BY user_id, created_at, id
) AS first_active
WHERE method.id = first_active.id;

ALTER TABLE payment_methods ADD CONSTRAINT payment_method_default_active
    CHECK (NOT is_default OR status = 'ACTIVE');
CREATE UNIQUE INDEX payment_methods_one_default_per_user_uq ON payment_methods (user_id)
    WHERE is_default;
