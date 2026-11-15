-- Quote and acceptance history are append-only. New revisions create new bid IDs.
CREATE TRIGGER trg_bid_quote_immutable
BEFORE UPDATE OR DELETE ON bid_quote_snapshot FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
CREATE TRIGGER trg_bid_acceptance_immutable
BEFORE UPDATE OR DELETE ON bid_acceptance FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
