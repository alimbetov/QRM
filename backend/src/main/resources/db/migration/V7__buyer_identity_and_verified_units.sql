-- Existing R2.0A snapshots have unknown unit; old rows are NOT eligible for checkout.
ALTER TABLE catalog_sku ADD COLUMN sales_unit varchar(20);
ALTER TABLE buyer_demand_line_snapshot ADD COLUMN unit varchar(20);
CREATE TABLE buyer_account_link (
 id uuid PRIMARY KEY,
 issuer varchar(255) NOT NULL,
 subject varchar(255) NOT NULL,
 buyer_id uuid NOT NULL,
 active boolean NOT NULL,
 version bigint,
 CONSTRAINT uk_buyer_issuer_subject UNIQUE (issuer,subject)
);
CREATE INDEX idx_buyer_account_id ON buyer_account_link(buyer_id);
CREATE TABLE seller_delivery_area (
 id uuid PRIMARY KEY,
 seller_id uuid NOT NULL,
 city varchar(160) NOT NULL,
 active boolean NOT NULL,
 version bigint,
 CONSTRAINT uk_seller_delivery_city UNIQUE (seller_id,city)
);
CREATE INDEX idx_delivery_area_seller ON seller_delivery_area(seller_id);
