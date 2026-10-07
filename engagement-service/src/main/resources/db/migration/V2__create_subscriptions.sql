-- Subscriptions (Phase 3). engagement-service owns subscription state; travel-service owns the
-- place count and is asked to reserve/release a place atomically (see docs/subscriptions.md).
CREATE TABLE subscription (
    subscription_id UUID PRIMARY KEY,
    travel_id UUID NOT NULL,
    traveler_id UUID NOT NULL,
    -- Snapshot of the travel when the place was reserved.
    manager_id UUID NOT NULL,
    travel_title VARCHAR(200) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    time_zone VARCHAR(64) NOT NULL,
    cancellation_cutoff DATE NOT NULL,
    price_amount_minor BIGINT NOT NULL,
    price_currency CHAR(3) NOT NULL,
    status VARCHAR(32) NOT NULL,
    -- "travelId:travelerId" while PENDING_PAYMENT or CONFIRMED, NULL afterwards: at most one active
    -- subscription per traveler and travel, even under concurrent requests.
    active_key VARCHAR(80),
    idempotency_key UUID NOT NULL,
    payment_id UUID,
    reservation_expires_at TIMESTAMP WITH TIME ZONE,
    cancellation_reason VARCHAR(500),
    cancelled_by UUID,
    cancelled_at TIMESTAMP WITH TIME ZONE,
    -- False until travel-service confirmed the place was given back; a retry finishes the release.
    seat_released BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT subscription_active_unique UNIQUE (active_key),
    CONSTRAINT subscription_idempotency_unique UNIQUE (traveler_id, idempotency_key)
);

CREATE INDEX subscription_by_traveler ON subscription (traveler_id, created_at);
CREATE INDEX subscription_by_travel ON subscription (travel_id, created_at);
CREATE INDEX subscription_by_status ON subscription (status, end_date);

-- Append-only audit trail of every status change, including who made it and why. It is also the
-- traveler's cancellation history.
CREATE TABLE subscription_history (
    history_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL REFERENCES subscription (subscription_id),
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    actor_id UUID,
    reason VARCHAR(500),
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX subscription_history_by_subscription ON subscription_history (subscription_id, occurred_at);
