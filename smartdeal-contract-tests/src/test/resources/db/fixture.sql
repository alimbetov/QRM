-- ISOLATED PostgreSQL Testcontainers contract fixture, NOT production Liquibase migration.
-- Upstream User/Seller/Listing/Order contracts are intentionally NOT duplicated here.
CREATE TABLE buyer_demand (
    id UUID PRIMARY KEY,
    buyer_user_id UUID NOT NULL,
    category_id VARCHAR(120) NOT NULL,
    listing_type VARCHAR(16) NOT NULL DEFAULT 'PHYSICAL',
    quantity NUMERIC(19,3) NOT NULL CHECK (quantity > 0),
    unit_code VARCHAR(24) NOT NULL,
    city_code VARCHAR(64) NOT NULL,
    state VARCHAR(24) NOT NULL CHECK (state IN ('DRAFT','OPEN','SELECTED','ORDER_CREATED','CANCELLED','EXPIRED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    selected_bid_id UUID,
    linked_order_id UUID UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE seller_bid (
    id UUID PRIMARY KEY,
    demand_id UUID NOT NULL REFERENCES buyer_demand(id),
    seller_id UUID NOT NULL,
    listing_id UUID NOT NULL,
    bid_version INT NOT NULL CHECK (bid_version > 0),
    status VARCHAR(24) NOT NULL CHECK (status IN ('ACTIVE','SUPERSEDED','WITHDRAWN','EXPIRED','SELECTED','CONVERTED')),
    quantity NUMERIC(19,3) NOT NULL CHECK (quantity > 0),
    unit_code VARCHAR(24) NOT NULL,
    unit_price_kzt NUMERIC(19,2) NOT NULL CHECK (unit_price_kzt >= 0),
    shipping_kzt NUMERIC(19,2) CHECK (shipping_kzt >= 0),
    valid_until TIMESTAMPTZ NOT NULL,
    UNIQUE(demand_id, seller_id, bid_version),
    UNIQUE(demand_id, id)
);

-- One actionable current revision PER seller, but different sellers can bid.
CREATE UNIQUE INDEX seller_bid_one_current
    ON seller_bid(demand_id, seller_id) WHERE status IN ('ACTIVE','SELECTED');

-- Only one seller bid can be SELECTED for a whole demand.
CREATE UNIQUE INDEX seller_bid_one_selected
    ON seller_bid(demand_id) WHERE status = 'SELECTED';

-- Prevent cross-demand selected bid references even if an app bug supplies a foreign id.
ALTER TABLE buyer_demand ADD CONSTRAINT buyer_demand_selected_bid_same_demand
    FOREIGN KEY (id, selected_bid_id) REFERENCES seller_bid(demand_id, id)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE smartdeal_conversion (
    demand_id UUID PRIMARY KEY REFERENCES buyer_demand(id),
    accepted_bid_id UUID NOT NULL UNIQUE REFERENCES seller_bid(id),
    negotiation_id UUID NOT NULL UNIQUE,
    order_id UUID NOT NULL UNIQUE,
    converted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE match_invitation (
    id UUID PRIMARY KEY,
    demand_id UUID NOT NULL REFERENCES buyer_demand(id),
    seller_id UUID NOT NULL,
    listing_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(demand_id, seller_id)
);
