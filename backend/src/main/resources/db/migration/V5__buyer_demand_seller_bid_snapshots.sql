-- R2.0A: immutable demand / bid snapshots; no buyer acceptance or order reservation.
CREATE TABLE buyer_demand_snapshot (
    id uuid PRIMARY KEY,
    buyer_id uuid NOT NULL,
    city varchar(160) NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint
);
CREATE INDEX idx_buyer_demand_buyer ON buyer_demand_snapshot(buyer_id);
CREATE INDEX idx_buyer_demand_expiry ON buyer_demand_snapshot(expires_at);

CREATE TABLE buyer_demand_line_snapshot (
    id uuid PRIMARY KEY,
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    line_number integer NOT NULL,
    product_id uuid NOT NULL REFERENCES catalog_product(id),
    quantity numeric(19,3) NOT NULL,
    max_unit_price numeric(19,2),
    CONSTRAINT uk_demand_line_product UNIQUE(demand_id,product_id),
    CONSTRAINT uk_demand_line_position UNIQUE(demand_id,line_number),
    CONSTRAINT chk_demand_quantity_positive CHECK(quantity>0),
    CONSTRAINT chk_demand_max_unit_price CHECK(max_unit_price IS NULL OR max_unit_price>=0)
);
CREATE INDEX idx_demand_line_demand ON buyer_demand_line_snapshot(demand_id);

CREATE TABLE seller_bid_snapshot (
    id uuid PRIMARY KEY,
    demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
    seller_id uuid NOT NULL,
    revision integer NOT NULL,
    expires_at timestamptz NOT NULL,
    submitted_at timestamptz NOT NULL,
    version bigint,
    CONSTRAINT uk_bid_demand_seller_revision UNIQUE(demand_id,seller_id,revision),
    CONSTRAINT chk_bid_revision CHECK (revision>0)
);
CREATE INDEX idx_bid_demand ON seller_bid_snapshot(demand_id);

CREATE TABLE seller_bid_line_snapshot (
    id uuid PRIMARY KEY,
    bid_id uuid NOT NULL REFERENCES seller_bid_snapshot(id),
    line_number integer NOT NULL,
    product_id uuid NOT NULL REFERENCES catalog_product(id),
    sku_id uuid NOT NULL REFERENCES catalog_sku(id),
    quantity numeric(19,3) NOT NULL,
    unit_price numeric(19,2) NOT NULL,
    CONSTRAINT uk_bid_line_product UNIQUE(bid_id,product_id),
    CONSTRAINT uk_bid_line_position UNIQUE(bid_id,line_number),
    CONSTRAINT chk_bid_quantity_positive CHECK(quantity>0),
    CONSTRAINT chk_bid_unit_price CHECK(unit_price>=0)
);
CREATE INDEX idx_bid_line_bid ON seller_bid_line_snapshot(bid_id);
