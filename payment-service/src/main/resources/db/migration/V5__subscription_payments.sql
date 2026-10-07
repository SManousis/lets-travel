-- Phase 4: one payment per subscription. The row is written (state PROCESSING) BEFORE the provider
-- is called, so a crash or timeout can never leave an unrecorded charge; reconciliation finishes it.
-- Only safe data is stored: no card numbers, no account credentials, no provider secrets.
CREATE TABLE subscription_payment (
    payment_id UUID PRIMARY KEY,
    subscription_id UUID NOT NULL,
    user_id UUID NOT NULL,
    -- Amount and currency are copied from engagement-service's price snapshot, never from the browser.
    amount_minor BIGINT NOT NULL,
    currency CHAR(3) NOT NULL,
    -- STRIPE, PAYPAL or TEST (the built-in simulation provider).
    provider VARCHAR(16) NOT NULL,
    -- Saved payment method used (payment_methods.id); NULL for the TEST provider.
    method_id UUID,
    -- APPROVE, DECLINE or TIMEOUT for the TEST provider; NULL otherwise.
    test_scenario VARCHAR(16),
    -- Safe text shown to the traveler, e.g. "visa •••• 4242".
    method_display VARCHAR(120) NOT NULL,
    idempotency_key VARCHAR(64) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    -- PROCESSING, REQUIRES_RECONCILIATION, SUCCEEDED, FAILED, REFUND_PENDING, REFUNDED
    state VARCHAR(32) NOT NULL,
    -- Provider's charge ID (Stripe PaymentIntent / PayPal capture) once verified.
    provider_reference VARCHAR(255),
    failure_code VARCHAR(64),
    -- TRUE once engagement-service has been told the outcome (confirm or release the place).
    outcome_delivered BOOLEAN NOT NULL DEFAULT FALSE,
    refund_reason VARCHAR(64),
    refund_reference VARCHAR(255),
    -- Incremented on every provider attempt; also the optimistic-lock token for state changes.
    attempts INT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT subscription_payment_amount_positive CHECK (amount_minor > 0),
    -- Exactly one payment per subscription, whatever key a duplicate request uses.
    CONSTRAINT subscription_payment_one_per_subscription UNIQUE (subscription_id),
    -- A retried request (browser or gateway) with the same key finds the first payment.
    CONSTRAINT subscription_payment_idempotency UNIQUE (user_id, idempotency_key)
);

CREATE INDEX subscription_payment_by_user ON subscription_payment (user_id, created_at);
CREATE INDEX subscription_payment_by_state ON subscription_payment (state, updated_at);
