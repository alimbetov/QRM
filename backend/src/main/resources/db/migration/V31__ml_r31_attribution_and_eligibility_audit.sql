-- ML-R3.1: exact invitation -> qualified SellerBid lineage, and a
-- conservative, trigger-complete 24h eligibility mutation monitor.
-- No backfilled attribution, unverified negative labels or invented timestamps.

-- Explicit business attribution: not inferred from any post-view SellerBid.
CREATE UNIQUE INDEX uk_r31_view_lineage
    ON ml_seller_invitation_view(invitation_id,demand_id,seller_id);
CREATE UNIQUE INDEX uk_r31_bid_lineage
    ON seller_bid_snapshot(id,demand_id,seller_id);

CREATE TABLE ml_invitation_bid_attribution (
    bid_id uuid PRIMARY KEY,
    invitation_id uuid NOT NULL,
    demand_id uuid NOT NULL,
    seller_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT fk_r31_attribution_view FOREIGN KEY(invitation_id,demand_id,seller_id)
        REFERENCES ml_seller_invitation_view(invitation_id,demand_id,seller_id),
    CONSTRAINT fk_r31_attribution_bid FOREIGN KEY(bid_id,demand_id,seller_id)
        REFERENCES seller_bid_snapshot(id,demand_id,seller_id)
);
CREATE INDEX idx_r31_bid_invitation
    ON ml_invitation_bid_attribution(invitation_id,recorded_at,bid_id);

CREATE FUNCTION ml_r31_validate_attribution()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bid_record record; viewed timestamptz;
BEGIN
    SELECT b.revision,b.submitted_at INTO STRICT bid_record
    FROM seller_bid_snapshot b WHERE b.id=NEW.bid_id
      AND b.demand_id=NEW.demand_id AND b.seller_id=NEW.seller_id;
    SELECT v.viewed_at INTO STRICT viewed FROM ml_seller_invitation_view v
      WHERE v.invitation_id=NEW.invitation_id AND v.demand_id=NEW.demand_id
        AND v.seller_id=NEW.seller_id;
    IF bid_record.revision<>1 OR bid_record.submitted_at<viewed THEN
        RAISE EXCEPTION 'Only first qualified bid after exact view can be attributed'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_r31_attribution_valid
  BEFORE INSERT ON ml_invitation_bid_attribution
  FOR EACH ROW EXECUTE FUNCTION ml_r31_validate_attribution();
CREATE TRIGGER trg_r31_attribution_immutable
  BEFORE UPDATE OR DELETE ON ml_invitation_bid_attribution
  FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();

-- Each seller-specific state mutation invalidates (conservatively) that
-- seller's 24h non-response proof; a global catalog-definition change
-- invalidates all in-flight observations. Even no-op UPDATE is recorded.
CREATE TABLE ml_r31_eligibility_change (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    seller_id uuid,
    source_table varchar(64) NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_r31_eligibility_seller_cutoff
  ON ml_r31_eligibility_change(seller_id,recorded_at,id);
CREATE INDEX idx_r31_eligibility_global_cutoff
  ON ml_r31_eligibility_change(recorded_at,id) WHERE seller_id IS NULL;
CREATE TRIGGER trg_r31_eligibility_history_immutable
  BEFORE UPDATE OR DELETE ON ml_r31_eligibility_change
  FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();

-- The same seller advisory key is taken by an actual view acknowledgement.
-- DML and view cannot cross without either the state being observed or an
-- invalidation stamped AFTER the view. Unrelated sellers do not block.
CREATE FUNCTION ml_r31_log_seller_change(p_seller uuid,p_table text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    IF p_seller IS NOT NULL THEN
        PERFORM pg_advisory_xact_lock(hashtextextended(p_seller::text,9021));
        INSERT INTO ml_r31_eligibility_change(seller_id,source_table)
        VALUES (p_seller,p_table);
    END IF;
END;
$$;
CREATE FUNCTION ml_r31_log_global_change(p_table text)
RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    -- View acknowledgements take SHARED mode of this lock.
    PERFORM pg_advisory_xact_lock(42931,1);
    INSERT INTO ml_r31_eligibility_change(seller_id,source_table)
    VALUES (NULL,p_table);
END;
$$;

CREATE FUNCTION ml_r31_audit_direct_seller()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE before_seller uuid; after_seller uuid;
BEGIN
    IF TG_OP<>'INSERT' THEN before_seller:=OLD.seller_id; END IF;
    IF TG_OP<>'DELETE' THEN after_seller:=NEW.seller_id; END IF;
    PERFORM ml_r31_log_seller_change(before_seller,TG_TABLE_NAME);
    IF after_seller IS DISTINCT FROM before_seller THEN
        PERFORM ml_r31_log_seller_change(after_seller,TG_TABLE_NAME);
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_r31_membership_change
  AFTER INSERT OR UPDATE OR DELETE ON seller_membership
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();
CREATE TRIGGER trg_r31_delivery_area_change
  AFTER INSERT OR UPDATE OR DELETE ON seller_delivery_area
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();
CREATE TRIGGER trg_r31_location_change
  AFTER INSERT OR UPDATE OR DELETE ON inventory_location
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();
CREATE TRIGGER trg_r31_balance_change
  AFTER INSERT OR UPDATE OR DELETE ON inventory_balance
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();
CREATE TRIGGER trg_r31_reservation_change
  AFTER INSERT OR UPDATE OR DELETE ON inventory_reservation
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();
CREATE TRIGGER trg_r31_listing_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_listing
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_direct_seller();

-- SKU identity/unit/stock policy and SKU attribute changes reference
-- the seller through the parent listing, not an untrusted client ID.
CREATE FUNCTION ml_r31_audit_sku_seller()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior_listing uuid; next_listing uuid; affected record;
BEGIN
    IF TG_TABLE_NAME='catalog_sku' THEN
        IF TG_OP<>'INSERT' THEN prior_listing:=OLD.listing_id; END IF;
        IF TG_OP<>'DELETE' THEN next_listing:=NEW.listing_id; END IF;
    ELSE
        IF TG_OP<>'INSERT' THEN
            SELECT listing_id INTO prior_listing FROM catalog_sku WHERE id=OLD.sku_id;
        END IF;
        IF TG_OP<>'DELETE' THEN
            SELECT listing_id INTO next_listing FROM catalog_sku WHERE id=NEW.sku_id;
        END IF;
    END IF;
    FOR affected IN
      SELECT DISTINCT l.seller_id FROM catalog_listing l
      WHERE l.id=prior_listing OR l.id=next_listing
      ORDER BY l.seller_id
    LOOP
        PERFORM ml_r31_log_seller_change(affected.seller_id,TG_TABLE_NAME);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_r31_sku_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_sku
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_sku_seller();
CREATE TRIGGER trg_r31_sku_attribute_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_sku_attribute_value
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_sku_seller();

CREATE FUNCTION ml_r31_audit_product_seller()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE old_id uuid; new_id uuid; affected record;
BEGIN
    IF TG_OP<>'INSERT' THEN old_id:=OLD.id; END IF;
    IF TG_OP<>'DELETE' THEN new_id:=NEW.id; END IF;
    FOR affected IN
        SELECT DISTINCT l.seller_id FROM catalog_listing l
        WHERE l.product_id=old_id OR l.product_id=new_id
        ORDER BY l.seller_id
    LOOP
        PERFORM ml_r31_log_seller_change(affected.seller_id,TG_TABLE_NAME);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_r31_product_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_product
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_product_seller();

CREATE FUNCTION ml_r31_audit_category_seller()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE previous_category uuid; next_category uuid; affected record;
BEGIN
    IF TG_TABLE_NAME='catalog_category' THEN
        IF TG_OP<>'INSERT' THEN previous_category:=OLD.id; END IF;
        IF TG_OP<>'DELETE' THEN next_category:=NEW.id; END IF;
    ELSE
        IF TG_OP<>'INSERT' THEN previous_category:=OLD.category_id; END IF;
        IF TG_OP<>'DELETE' THEN next_category:=NEW.category_id; END IF;
    END IF;
    FOR affected IN
        SELECT DISTINCT l.seller_id FROM catalog_listing l
        JOIN catalog_product p ON p.id=l.product_id
        WHERE p.category_id=previous_category OR p.category_id=next_category
        ORDER BY l.seller_id
    LOOP
        PERFORM ml_r31_log_seller_change(affected.seller_id,TG_TABLE_NAME);
    END LOOP;
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_r31_category_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_category
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_category_seller();
CREATE TRIGGER trg_r31_category_rule_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_category_attribute_rule
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_category_seller();

-- Definition revisions and global unit vocabularies can invalidate multiple
-- sellers. Mark GLOBAL instead of silently claiming we know the impact.
CREATE FUNCTION ml_r31_audit_global_schema()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    PERFORM ml_r31_log_global_change(TG_TABLE_NAME);
    RETURN NULL;
END;
$$;
CREATE TRIGGER trg_r31_attribute_definition_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_attribute_definition
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_global_schema();
CREATE TRIGGER trg_r31_attribute_revision_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_attribute_schema_revision
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_global_schema();
CREATE TRIGGER trg_r31_units_change
  AFTER INSERT OR UPDATE OR DELETE ON catalog_unit
  FOR EACH ROW EXECUTE FUNCTION ml_r31_audit_global_schema();

-- Enabled only after all monitored DML triggers are installed. A pre-V31
-- seller view, including V30's historical fixtures, can never become NEGATIVE.
CREATE TABLE ml_r31_monitor_epoch (
    singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
    started_at timestamptz NOT NULL
);
CREATE TRIGGER trg_r31_monitor_immutable
  BEFORE UPDATE OR DELETE ON ml_r31_monitor_epoch
  FOR EACH ROW EXECUTE FUNCTION ml_r2_deny_history_mutation();
INSERT INTO ml_r31_monitor_epoch(singleton,started_at)
VALUES (true,clock_timestamp());
