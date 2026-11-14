-- Every seller disclosure of an encrypted contact creates append-only evidence.
CREATE TABLE qr_lead_read_audit (
    id uuid PRIMARY KEY,
    version bigint,
    lead_id uuid NOT NULL REFERENCES qr_purchase_lead(id),
    seller_id uuid NOT NULL,
    actor_issuer varchar(255) NOT NULL,
    actor_subject varchar(255) NOT NULL,
    read_at timestamptz NOT NULL
);
CREATE INDEX idx_qr_lead_audit_seller_time ON qr_lead_read_audit(seller_id,read_at);
CREATE TRIGGER trg_qr_lead_read_audit_immutable
BEFORE UPDATE OR DELETE ON qr_lead_read_audit FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
