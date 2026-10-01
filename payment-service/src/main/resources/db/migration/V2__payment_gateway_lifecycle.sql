CREATE TABLE payment_gateways (
    id UUID PRIMARY KEY,
    provider VARCHAR(32) NOT NULL,
    display_name VARCHAR(200) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE payment_methods ADD COLUMN gateway_id UUID REFERENCES payment_gateways(id);
ALTER TABLE payment_methods ADD COLUMN display_metadata VARCHAR(120);
ALTER TABLE payment_methods ADD COLUMN failure_code VARCHAR(64);
ALTER TABLE payment_methods ADD COLUMN failure_stage VARCHAR(16);
ALTER TABLE payment_methods ADD COLUMN idempotency_key VARCHAR(120);
ALTER TABLE payment_methods ADD COLUMN request_hash VARCHAR(64);
ALTER TABLE payment_methods ADD COLUMN updated_at TIMESTAMPTZ;
UPDATE payment_methods SET updated_at = created_at;
ALTER TABLE payment_methods ALTER COLUMN updated_at SET NOT NULL;

CREATE INDEX payment_methods_gateway_idx ON payment_methods (gateway_id);
CREATE UNIQUE INDEX payment_methods_provider_reference_uq ON payment_methods (provider, provider_reference);
CREATE UNIQUE INDEX payment_methods_idempotency_key_uq ON payment_methods (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
