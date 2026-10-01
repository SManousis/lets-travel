CREATE INDEX admin_sessions_subject_expiry_idx ON admin_sessions (subject, expires_at);
