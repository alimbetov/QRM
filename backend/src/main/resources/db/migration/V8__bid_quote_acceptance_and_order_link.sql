-- R2.0B: exact seller quote, one accepted bid per buyer demand, no inventory reservation.
ALTER TABLE customer_order ADD COLUMN delivery_fee numeric(19,2) NOT NULL DEFAULT 0;
ALTER TABLE customer_order ADD COLUMN accepted_bid_id uuid REFERENCES seller_bid_snapshot(id);
ALTER TABLE customer_order ADD CONSTRAINT uk_customer_order_accepted_bid UNIQUE(accepted_bid_id);
ALTER TABLE customer_order ADD CONSTRAINT chk_order_delivery_fee CHECK (delivery_fee>=0);
CREATE TABLE bid_quote_snapshot (
 bid_id uuid PRIMARY KEY REFERENCES seller_bid_snapshot(id),
 version bigint,
 method varchar(16) NOT NULL,
 origin_location_id uuid NOT NULL REFERENCES inventory_location(id),
 origin_city varchar(160) NOT NULL,
 origin_region_code varchar(32) NOT NULL,
 destination_city varchar(160) NOT NULL,
 currency_code varchar(3) NOT NULL,
 delivery_fee numeric(19,2) NOT NULL,
 all_in_amount numeric(19,2) NOT NULL,
 CONSTRAINT chk_quote_method CHECK(method IN ('DELIVERY','PICKUP')),
 CONSTRAINT chk_quote_currency CHECK(currency_code='KZT'),
 CONSTRAINT chk_quote_delivery_fee CHECK(delivery_fee>=0 AND (method<>'PICKUP' OR delivery_fee=0)),
 CONSTRAINT chk_quote_amount CHECK(all_in_amount>=delivery_fee)
);
CREATE TABLE bid_acceptance (
 id uuid PRIMARY KEY,
 version bigint,
 demand_id uuid NOT NULL REFERENCES buyer_demand_snapshot(id),
 bid_id uuid NOT NULL REFERENCES seller_bid_snapshot(id),
 buyer_id uuid NOT NULL,
 idempotency_key varchar(80) NOT NULL,
 request_fingerprint varchar(64) NOT NULL,
 order_id uuid NOT NULL REFERENCES customer_order(id),
 CONSTRAINT uk_acceptance_demand UNIQUE(demand_id),
 CONSTRAINT uk_acceptance_buyer_key UNIQUE(buyer_id,idempotency_key),
 CONSTRAINT uk_acceptance_order UNIQUE(order_id)
);
CREATE INDEX idx_acceptance_bid ON bid_acceptance(bid_id);
