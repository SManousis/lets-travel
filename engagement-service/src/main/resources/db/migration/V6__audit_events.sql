-- Append-only record of privileged and destructive actions (AuditLog, docs/security.md).
CREATE TABLE audit_event (
    audit_id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_id UUID,
    actor_role VARCHAR(20) NOT NULL,
    action VARCHAR(80) NOT NULL,
    target_type VARCHAR(40) NOT NULL,
    target_id VARCHAR(80),
    request_id VARCHAR(64),
    details VARCHAR(1000) NOT NULL
);

CREATE INDEX audit_event_time ON audit_event (occurred_at);
CREATE INDEX audit_event_target ON audit_event (target_type, target_id);
