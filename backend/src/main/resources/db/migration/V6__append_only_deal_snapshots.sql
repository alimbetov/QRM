-- R2.0A: historical demand and bid snapshots are append-only.
-- Any future acceptance, expiration, cancellation or inventory action must use
-- separate state/event tables, never rewrite the captured economic terms.
CREATE OR REPLACE FUNCTION deny_deal_snapshot_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Historical deal snapshots are append-only; table %, operation %',
    TG_TABLE_NAME, TG_OP USING ERRCODE='23514';
END;
$$;
CREATE TRIGGER trg_demand_snapshot_immutable
BEFORE UPDATE OR DELETE ON buyer_demand_snapshot
FOR EACH ROW EXECUTE FUNCTION deny_deal_snapshot_change();
CREATE TRIGGER trg_demand_line_snapshot_immutable
BEFORE UPDATE OR DELETE ON buyer_demand_line_snapshot
FOR EACH ROW EXECUTE FUNCTION deny_deal_snapshot_change();
CREATE TRIGGER trg_bid_snapshot_immutable
BEFORE UPDATE OR DELETE ON seller_bid_snapshot
FOR EACH ROW EXECUTE FUNCTION deny_deal_snapshot_change();
CREATE TRIGGER trg_bid_line_snapshot_immutable
BEFORE UPDATE OR DELETE ON seller_bid_line_snapshot
FOR EACH ROW EXECUTE FUNCTION deny_deal_snapshot_change();
