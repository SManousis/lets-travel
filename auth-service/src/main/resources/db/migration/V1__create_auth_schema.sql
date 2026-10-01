CREATE TABLE admin_sessions (
    jti UUID PRIMARY KEY,
    subject VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX admin_sessions_expiry_idx ON admin_sessions (expires_at);
