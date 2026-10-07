-- Sessions now carry a rotating refresh token and a CSRF token, both stored only as
-- SHA-256 hashes. Existing admin sessions are short-lived access tokens; dropping
-- them signs everyone out once when this release is deployed.
DROP TABLE admin_sessions;

CREATE TABLE auth_sessions (
    session_id UUID PRIMARY KEY,
    subject UUID NOT NULL,
    refresh_token_hash CHAR(64) NOT NULL UNIQUE,
    previous_refresh_token_hash CHAR(64),
    csrf_token_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX auth_sessions_subject_idx ON auth_sessions (subject);
CREATE INDEX auth_sessions_previous_refresh_idx ON auth_sessions (previous_refresh_token_hash);

-- OPERATOR granted no capabilities in Travel Plan. Map it to the least privileged
-- Let's Travel role; an admin can promote the account afterwards.
UPDATE auth_identities SET role = 'TRAVELER' WHERE role = 'OPERATOR';
ALTER TABLE auth_identities ADD CONSTRAINT auth_identities_role_check
    CHECK (role IN ('ADMIN', 'TRAVEL_MANAGER', 'TRAVELER'));
