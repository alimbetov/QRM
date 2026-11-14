-- R2 order economic snapshot: order_line values cannot be rewritten after placement.
-- Fulfillment/returns must use separate events, never mutate the accepted line.
CREATE TRIGGER trg_order_line_immutable
BEFORE UPDATE OR DELETE ON order_line FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
-- Existing order header immutable price, owner and bid linkage are protected
-- by JPA updatable=false, but also require DB-level hard stops.
CREATE OR REPLACE FUNCTION guard_order_economic_fields()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id IS DISTINCT FROM OLD.id OR
       NEW.public_number IS DISTINCT FROM OLD.public_number OR
       NEW.seller_id IS DISTINCT FROM OLD.seller_id OR
       NEW.buyer_id IS DISTINCT FROM OLD.buyer_id OR
       NEW.total_amount IS DISTINCT FROM OLD.total_amount OR
       NEW.delivery_fee IS DISTINCT FROM OLD.delivery_fee OR
       NEW.accepted_bid_id IS DISTINCT FROM OLD.accepted_bid_id OR
       NEW.currency_code IS DISTINCT FROM OLD.currency_code OR
       NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'Accepted order economic fields are immutable'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_order_economics_immutable
BEFORE UPDATE ON customer_order FOR EACH ROW
EXECUTE FUNCTION guard_order_economic_fields();
