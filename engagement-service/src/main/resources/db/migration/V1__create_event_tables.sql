-- Transactional outbox: written in the same transaction as the business change and
-- published afterwards, so other services never miss an engagement event.
CREATE TABLE outbox_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    aggregate_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    trace_id VARCHAR(64),
    payload TEXT NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    publish_attempts INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX outbox_event_pending ON outbox_event (published_at, occurred_at);

-- Inbox: event IDs already applied, so redelivered events are ignored.
CREATE TABLE processed_event (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
