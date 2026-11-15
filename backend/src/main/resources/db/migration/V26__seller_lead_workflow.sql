-- Seller Studio R0: terminal callback status is a separate immutable audit fact.
-- Existing encrypted guest lead records remain immutable; NEW is represented
-- by the absence of a transition row. CONTACTED means seller-marked, not phone verified.
CREATE TABLE qr_lead_workflow (
    lead_id uuid PRIMARY KEY REFERENCES qr_purchase_lead(id),
    seller_id uuid NOT NULL,
    status varchar(16) NOT NULL,
    actor_issuer varchar(255) NOT NULL,
    actor_subject varchar(255) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT chk_qr_lead_workflow_status CHECK(status IN ('CONTACTED','DECLINED'))
);
CREATE INDEX idx_qr_lead_workflow_seller ON qr_lead_workflow(seller_id,updated_at DESC);
CREATE TRIGGER trg_qr_lead_workflow_immutable
BEFORE UPDATE OR DELETE ON qr_lead_workflow FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
