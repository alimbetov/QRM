-- R2.1 physical tracked stock: one row per seller/location/SKU.
-- Confirmation must update these rows in a SINGLE transaction with order and outbox.
CREATE TABLE inventory_balance (
 id uuid PRIMARY KEY,
 seller_id uuid NOT NULL,
 location_id uuid NOT NULL REFERENCES inventory_location(id),
 sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 on_hand numeric(19,3) NOT NULL,
 reserved numeric(19,3) NOT NULL DEFAULT 0,
 version bigint,
 CONSTRAINT uk_balance_seller_location_sku UNIQUE(seller_id,location_id,sku_id),
 CONSTRAINT chk_inventory_balance CHECK (on_hand>=0 AND reserved>=0 AND reserved<=on_hand)
);
CREATE INDEX idx_inventory_balance_owner ON inventory_balance(seller_id,sku_id);
CREATE TABLE inventory_reservation (
 id uuid PRIMARY KEY,
 version bigint,
 order_id uuid NOT NULL REFERENCES customer_order(id),
 order_line_id uuid NOT NULL REFERENCES order_line(id),
 seller_id uuid NOT NULL,
 location_id uuid NOT NULL REFERENCES inventory_location(id),
 sku_id uuid NOT NULL REFERENCES catalog_sku(id),
 quantity numeric(19,3) NOT NULL,
 status varchar(16) NOT NULL,
 CONSTRAINT uk_reservation_order_line UNIQUE(order_line_id),
 CONSTRAINT chk_reservation_quantity CHECK(quantity>0),
 CONSTRAINT chk_reservation_status CHECK(status IN ('RESERVED','RELEASED','FULFILLED'))
);
CREATE INDEX idx_inventory_reservation_order ON inventory_reservation(order_id);
CREATE TABLE order_outbox_event (
 id uuid PRIMARY KEY,
 version bigint,
 aggregate_id uuid NOT NULL REFERENCES customer_order(id),
 event_type varchar(64) NOT NULL,
 payload varchar(512) NOT NULL,
 occurred_at timestamptz NOT NULL,
 published_at timestamptz,
 CONSTRAINT uk_outbox_order_event UNIQUE(aggregate_id,event_type)
);
CREATE INDEX idx_outbox_unpublished ON order_outbox_event(occurred_at) WHERE published_at IS NULL;
