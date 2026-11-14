-- R2.2: seller-declared stock intake and every new reservation/release
-- must produce an immutable inventory movement audit in the same transaction.
-- Existing reservations need explicit pre-adoption audit reconciliation.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM inventory_reservation LIMIT 1) THEN
  RAISE EXCEPTION 'Existing reservations require approved ledger backfill before V14';
 END IF;
END;
$$;
CREATE TABLE inventory_movement (
 id uuid PRIMARY KEY,
 version bigint,
 seller_id uuid NOT NULL,
 location_id uuid NOT NULL REFERENCES inventory_location(id),
 sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 order_id uuid REFERENCES customer_order(id),
 order_line_id uuid REFERENCES order_line(id),
 kind varchar(16) NOT NULL,
 source_key varchar(80) NOT NULL,
 delta_on_hand numeric(19,3) NOT NULL,
 delta_reserved numeric(19,3) NOT NULL,
 actor_issuer varchar(255) NOT NULL,
 actor_subject varchar(255) NOT NULL,
 occurred_at timestamptz NOT NULL,
 CONSTRAINT uk_inventory_movement_source UNIQUE(seller_id,kind,source_key),
 CONSTRAINT chk_inventory_movement_deltas CHECK (
    (kind='RECEIPT' AND order_id IS NULL AND order_line_id IS NULL
        AND delta_on_hand>0 AND delta_reserved=0)
 OR (kind='RESERVATION' AND order_id IS NOT NULL AND order_line_id IS NOT NULL
        AND delta_on_hand=0 AND delta_reserved>0)
 OR (kind='RELEASE' AND order_id IS NOT NULL AND order_line_id IS NOT NULL
        AND delta_on_hand=0 AND delta_reserved<0)
 )
);
CREATE INDEX idx_inventory_movement_order ON inventory_movement(order_id);
CREATE INDEX idx_inventory_movement_seller_time ON inventory_movement(seller_id,occurred_at);
CREATE TRIGGER trg_inventory_movement_immutable
BEFORE UPDATE OR DELETE ON inventory_movement FOR EACH ROW
EXECUTE FUNCTION deny_deal_snapshot_change();
