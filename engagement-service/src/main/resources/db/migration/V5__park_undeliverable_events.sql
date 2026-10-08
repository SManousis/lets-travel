-- Events the consumer rejected as unprocessable are parked (dead-letter) instead of blocking the
-- events behind them. An admin can list them and send them again (see docs/operations.md).
ALTER TABLE outbox_event ADD COLUMN parked_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE outbox_event ADD COLUMN last_error VARCHAR(500);
