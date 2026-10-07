-- Phase 4: payment follow-ups owned by engagement-service.
-- refund_due is set in the same transaction that cancels a CONFIRMED (paid) subscription. It stays
-- TRUE until payment-service has accepted the refund request, so a payment-service outage only
-- delays the refund; the housekeeping job asks again.
ALTER TABLE subscription ADD COLUMN refund_due BOOLEAN NOT NULL DEFAULT FALSE;

-- The expiry job looks up unpaid reservations whose payment window has passed.
CREATE INDEX subscription_by_expiry ON subscription (status, reservation_expires_at);
