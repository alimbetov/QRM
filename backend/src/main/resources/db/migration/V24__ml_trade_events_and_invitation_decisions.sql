-- ML-R2: immutable, privacy-minimized event journal and seller-invitation
-- eligibility decisions. This is NOT a notification outbox and never sends push.
-- Existing order_outbox_event is order-FK-only: do not reuse for demands.
-- Historical data is NOT backfilled with invented event-time timestamps.
CREATE TABLE ml_trade_event (
    event_id uuid PRIMARY KEY,
    aggregate_type varchar(16) NOT NULL,
    aggregate_id uuid NOT NULL,
    event_type varchar(48) NOT NULL,
    source_revision integer NOT NULL,
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    seller_id uuid,
    schema_version smallint NOT NULL DEFAULT 1,
    occurred_at timestamptz NOT NULL,
    ingested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_ml_event_aggregate CHECK(aggregate_type IN ('DEMAND','BID','ORDER','INVITATION')),
    CONSTRAINT ck_ml_event_type CHECK(event_type IN (
        'DEMAND_CREATED','BID_SUBMITTED','BID_REVISED','BID_ACCEPTED',
        'ORDER_CONFIRMED','SELLER_INVITATION_EVALUATED')),
    CONSTRAINT ck_ml_event_revision CHECK(source_revision BETWEEN 1 AND 1000000),
    CONSTRAINT ck_ml_event_schema CHECK(schema_version=1),
    CONSTRAINT uk_ml_business_event UNIQUE(aggregate_type,aggregate_id,event_type,source_revision)
);
CREATE INDEX idx_ml_trade_event_demand_time
    ON ml_trade_event(demand_id,occurred_at,event_id);
CREATE INDEX idx_ml_trade_event_type_ingested
    ON ml_trade_event(event_type,ingested_at,event_id);

CREATE TABLE ml_seller_invitation (
    id uuid PRIMARY KEY,
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    seller_id uuid NOT NULL,
    policy_version integer NOT NULL,
    status varchar(16) NOT NULL,
    reason_code varchar(32) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    CONSTRAINT uk_ml_invitation_demand_seller_policy
        UNIQUE(demand_id,seller_id,policy_version),
    CONSTRAINT ck_ml_invitation_policy CHECK(policy_version BETWEEN 1 AND 1000000),
    CONSTRAINT ck_ml_invitation_status CHECK(status IN ('ELIGIBLE','SUPPRESSED')),
    CONSTRAINT ck_ml_invitation_reason CHECK(reason_code IN (
        'MATCHED','EXPIRED','ALREADY_ACCEPTED','NO_ACTIVE_MEMBERSHIP',
        'NO_PUBLIC_COMPATIBLE_SKU','NO_FULFILLMENT_AREA')),
    CONSTRAINT ck_ml_invitation_consistency CHECK(
        (status='ELIGIBLE' AND reason_code='MATCHED')
        OR (status='SUPPRESSED' AND reason_code<>'MATCHED'))
);
CREATE INDEX idx_ml_invitation_seller ON ml_seller_invitation(seller_id,evaluated_at,id);

CREATE FUNCTION ml_r2_deny_history_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ML-R2 history is append-only' USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER trg_ml_trade_event_immutable
BEFORE UPDATE OR DELETE ON ml_trade_event
FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();
CREATE TRIGGER trg_ml_invitation_immutable
BEFORE UPDATE OR DELETE ON ml_seller_invitation
FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();

-- Every event is appended in the same transaction as the authoritative
-- business insert/transition. A failed commerce transaction emits nothing.
CREATE FUNCTION ml_r2_capture_demand()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
        source_revision,demand_id,occurred_at)
    VALUES (gen_random_uuid(),'DEMAND',NEW.id,'DEMAND_CREATED',
            1,NEW.id,NEW.created_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_capture_demand AFTER INSERT ON buyer_demand_snapshot
FOR EACH ROW EXECUTE FUNCTION ml_r2_capture_demand();

CREATE FUNCTION ml_r2_capture_bid()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
        source_revision,demand_id,seller_id,occurred_at)
    VALUES (gen_random_uuid(),'BID',NEW.id,
        CASE WHEN NEW.revision=1 THEN 'BID_SUBMITTED' ELSE 'BID_REVISED' END,
        NEW.revision,NEW.demand_id,NEW.seller_id,NEW.submitted_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_capture_bid AFTER INSERT ON seller_bid_snapshot
FOR EACH ROW EXECUTE FUNCTION ml_r2_capture_bid();

CREATE FUNCTION ml_r2_capture_acceptance()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE trusted_seller uuid;
BEGIN
    SELECT seller_id INTO STRICT trusted_seller FROM seller_bid_snapshot WHERE id=NEW.bid_id;
    INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
        source_revision,demand_id,seller_id,occurred_at)
    VALUES (gen_random_uuid(),'DEMAND',NEW.demand_id,'BID_ACCEPTED',
        1,NEW.demand_id,trusted_seller,clock_timestamp());
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_capture_acceptance AFTER INSERT ON bid_acceptance
FOR EACH ROW EXECUTE FUNCTION ml_r2_capture_acceptance();

CREATE FUNCTION ml_r2_capture_order_confirm()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE accepted_demand uuid;
BEGIN
    IF OLD.status IS DISTINCT FROM NEW.status AND NEW.status='CONFIRMED' THEN
        SELECT demand_id INTO accepted_demand FROM bid_acceptance WHERE order_id=NEW.id;
        IF accepted_demand IS NOT NULL THEN
            INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
                source_revision,demand_id,seller_id,occurred_at)
            VALUES (gen_random_uuid(),'ORDER',NEW.id,'ORDER_CONFIRMED',
                1,accepted_demand,NEW.seller_id,clock_timestamp());
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_capture_order_confirm AFTER UPDATE OF status ON customer_order
FOR EACH ROW EXECUTE FUNCTION ml_r2_capture_order_confirm();

CREATE FUNCTION ml_r2_capture_invitation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO ml_trade_event(event_id,aggregate_type,aggregate_id,event_type,
        source_revision,demand_id,seller_id,occurred_at)
    VALUES (gen_random_uuid(),'INVITATION',NEW.id,'SELLER_INVITATION_EVALUATED',
        NEW.policy_version,NEW.demand_id,NEW.seller_id,NEW.evaluated_at);
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_ml_capture_invitation AFTER INSERT ON ml_seller_invitation
FOR EACH ROW EXECUTE FUNCTION ml_r2_capture_invitation();
