-- R2.2B. Old outbox rows have no reliable commit-order sequence. Fail closed:
-- existing environments need a reviewed deterministic backfill/reconciliation.
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM order_outbox_event) THEN
        RAISE EXCEPTION 'Outbox V15 needs reviewed legacy event ordering and backfill';
    END IF;
END $$;

ALTER TABLE order_outbox_event ADD COLUMN event_sequence bigint GENERATED ALWAYS AS IDENTITY;
ALTER TABLE order_outbox_event ADD CONSTRAINT uk_outbox_event_sequence UNIQUE(event_sequence);
ALTER TABLE order_outbox_event ADD COLUMN status varchar(16) NOT NULL DEFAULT 'PENDING';
ALTER TABLE order_outbox_event ADD COLUMN attempt_count integer NOT NULL DEFAULT 0;
ALTER TABLE order_outbox_event ADD COLUMN claim_generation bigint NOT NULL DEFAULT 0;
ALTER TABLE order_outbox_event ADD COLUMN lease_owner varchar(120);
ALTER TABLE order_outbox_event ADD COLUMN lease_token uuid;
ALTER TABLE order_outbox_event ADD COLUMN lease_until timestamptz;
ALTER TABLE order_outbox_event ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp();
ALTER TABLE order_outbox_event ADD COLUMN last_error varchar(500);
ALTER TABLE order_outbox_event ADD CONSTRAINT chk_outbox_delivery_status
    CHECK(status IN ('PENDING','IN_FLIGHT','PUBLISHED','DEAD_LETTER'));
ALTER TABLE order_outbox_event ADD CONSTRAINT chk_outbox_delivery_attempts
    CHECK(attempt_count>=0 AND claim_generation>=0);
ALTER TABLE order_outbox_event ADD CONSTRAINT chk_outbox_delivery_lease
    CHECK((status='IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
      OR (status<>'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL));
CREATE INDEX idx_outbox_claim_ready ON order_outbox_event(event_sequence,next_attempt_at)
    WHERE status='PENDING';
CREATE INDEX idx_outbox_claim_expired ON order_outbox_event(lease_until)
    WHERE status='IN_FLIGHT';
CREATE INDEX idx_outbox_aggregate_order ON order_outbox_event(aggregate_id,event_sequence,status);

-- This initial consumer is an idempotent, durable internal projection sink.
-- External broker/webhook sinks must implement the same event-ID contract.
CREATE TABLE order_event_projection_inbox (
    consumer_id varchar(120) NOT NULL,
    event_id uuid NOT NULL REFERENCES order_outbox_event(id),
    aggregate_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    payload varchar(512) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(consumer_id,event_id)
);
CREATE INDEX idx_order_event_projection_aggregate ON order_event_projection_inbox(aggregate_id,applied_at);
