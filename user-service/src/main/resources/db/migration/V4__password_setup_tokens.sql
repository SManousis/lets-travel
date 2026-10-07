-- One-time links that let a user choose their password after an admin created the
-- account. Only a SHA-256 hash of each token is stored.
CREATE TABLE password_setup_tokens (
    token_hash CHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ
);
CREATE INDEX password_setup_tokens_user_idx ON password_setup_tokens (user_id);
