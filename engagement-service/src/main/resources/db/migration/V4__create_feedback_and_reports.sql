-- Phase 6: feedback, reports and moderation (see docs/feedback-and-reports.md).
-- All text columns are plain text with length limits; the API never renders HTML.

-- One rating per traveler and travel, written only for a finished participation.
-- Feedback cannot be edited or deleted by its author, so no change history is needed.
CREATE TABLE feedback (
    feedback_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES subscription (subscription_id),
    travel_id UUID NOT NULL,
    traveler_id UUID NOT NULL,
    manager_id UUID NOT NULL,
    travel_title VARCHAR(200) NOT NULL,
    author_display_name VARCHAR(200) NOT NULL,
    rating SMALLINT NOT NULL,
    comment VARCHAR(1000),
    idempotency_key UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT feedback_rating_range CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT feedback_once_per_travel UNIQUE (traveler_id, travel_id),
    CONSTRAINT feedback_idempotency_unique UNIQUE (traveler_id, idempotency_key)
);

CREATE INDEX feedback_by_manager ON feedback (manager_id, created_at);
CREATE INDEX feedback_by_travel ON feedback (travel_id, created_at);

-- A traveler's report against a manager, another traveler or a travel.
CREATE TABLE report (
    report_id UUID PRIMARY KEY,
    reporter_id UUID NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id UUID NOT NULL,
    target_display_name VARCHAR(200) NOT NULL,
    category VARCHAR(32) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    assignee_id UUID,
    -- "reporter:type:target" while OPEN or IN_REVIEW, NULL once closed: one open report per
    -- reporter and target, even under concurrent requests.
    open_key VARCHAR(120),
    idempotency_key UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT report_open_unique UNIQUE (open_key),
    CONSTRAINT report_idempotency_unique UNIQUE (reporter_id, idempotency_key)
);

CREATE INDEX report_by_reporter ON report (reporter_id, created_at);
CREATE INDEX report_by_target ON report (target_type, target_id, status);
CREATE INDEX report_queue ON report (status, created_at);

-- Append-only moderation history: rows are inserted, never updated or deleted.
CREATE TABLE report_moderation (
    entry_id UUID PRIMARY KEY,
    report_id UUID NOT NULL REFERENCES report (report_id),
    -- 1, 2, 3... per report: keeps the order even when two steps share a timestamp.
    step INTEGER NOT NULL,
    admin_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL,
    note VARCHAR(1000) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT report_moderation_step_unique UNIQUE (report_id, step)
);
