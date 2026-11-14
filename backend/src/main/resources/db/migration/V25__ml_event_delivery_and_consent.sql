-- ML-R2.1: independent, bounded PostgreSQL at-least-once delivery queue.
-- Existing order_outbox_event is constrained to customer_order and must not be reused.
-- Migration backfills only REAL V24 ml_trade_event rows, never invents timestamps.
CREATE TABLE ml_event_delivery (
    event_id uuid PRIMARY KEY REFERENCES ml_trade_event(event_id),
    event_sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    status varchar(16) NOT NULL DEFAULT 'PENDING',
    attempt_count integer NOT NULL DEFAULT 0,
    claim_generation bigint NOT NULL DEFAULT 0,
    lease_owner varchar(120),
    lease_token uuid,
    lease_until timestamptz,
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    delivered_at timestamptz,
    last_error varchar(500),
    CONSTRAINT chk_ml_delivery_status CHECK(status IN ('PENDING','IN_FLIGHT','DELIVERED','DEAD_LETTER')),
    CONSTRAINT chk_ml_delivery_attempt CHECK(attempt_count>=0 AND claim_generation>=0),
    CONSTRAINT chk_ml_delivery_lease CHECK(
        (status='IN_FLIGHT' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR (status<>'IN_FLIGHT' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE INDEX idx_ml_delivery_pending ON ml_event_delivery(event_sequence,next_attempt_at)
    WHERE status='PENDING';
CREATE INDEX idx_ml_delivery_expired ON ml_event_delivery(lease_until)
    WHERE status='IN_FLIGHT';
CREATE INDEX idx_ml_delivery_demand_sequence ON ml_event_delivery(demand_id,event_sequence,status);

-- A consumer records its own durable, immutable application result, in a
-- transaction committed BEFORE queue ACK. A crash after sink commit is safe.
CREATE TABLE ml_event_projection_inbox (
    consumer_id varchar(120) NOT NULL,
    event_id uuid NOT NULL REFERENCES ml_trade_event(event_id),
    fingerprint char(64) NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY(consumer_id,event_id),
    CONSTRAINT chk_ml_inbox_fingerprint CHECK(fingerprint ~ '^[0-9a-f]{64}$')
);

-- Explicit buyer request sharing and seller in-app preference: MISSING = DENY.
-- Server APIs verify linked buyer ID and seller-admin membership; NEVER trust
-- a client submitted ownerId/sellerId as proof of ownership.
CREATE TABLE ml_buyer_demand_invitation_consent (
    demand_id uuid PRIMARY KEY REFERENCES buyer_demand_snapshot(id),
    buyer_id uuid NOT NULL,
    enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE ml_seller_invitation_preference (
    seller_id uuid PRIMARY KEY,
    in_app_enabled boolean NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- Delivery only to an INTERNAL seller inbox. Never interpret this as Push/SMS.
CREATE TABLE ml_seller_invitation_inbox (
    invitation_id uuid PRIMARY KEY REFERENCES ml_seller_invitation(id),
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    seller_id uuid NOT NULL,
    delivered_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_ml_seller_inbox_owner ON ml_seller_invitation_inbox(seller_id,delivered_at DESC);

CREATE FUNCTION ml_enqueue_event()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ml_event_delivery(event_id,demand_id)
    VALUES (NEW.event_id,NEW.demand_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_enqueue_event
AFTER INSERT ON ml_trade_event FOR EACH ROW
EXECUTE FUNCTION ml_enqueue_event();

-- Existing journal entries can be replayed internally; no external notification
-- is automatically sent by the migration. This is deliberately non-destructive.
INSERT INTO ml_event_delivery(event_id,demand_id)
SELECT event_id,demand_id
FROM ml_trade_event
ORDER BY ingested_at,event_id
ON CONFLICT(event_id) DO NOTHING;
