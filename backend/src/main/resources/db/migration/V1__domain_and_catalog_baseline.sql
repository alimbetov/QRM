-- Fresh PostgreSQL 17 baseline includes ALL existing JPA domain tables.
-- Existing pre-Flyway deployments require a reviewed, manual baseline/transition;
-- baseline-on-migrate is intentionally disabled.
CREATE TABLE customer_order (
    id uuid PRIMARY KEY,
    public_number varchar(12) NOT NULL,
    seller_id uuid NOT NULL,
    buyer_id uuid NOT NULL,
    status varchar(40) NOT NULL,
    total_amount numeric(19,2) NOT NULL,
    currency_code varchar(3) NOT NULL,
    created_at timestamptz NOT NULL,
    version bigint,
    CONSTRAINT uk_order_public_number UNIQUE(public_number)
);
CREATE INDEX idx_customer_order_seller ON customer_order(seller_id);
CREATE INDEX idx_customer_order_buyer ON customer_order(buyer_id);

CREATE TABLE order_line (
    id uuid PRIMARY KEY,
    order_id uuid NOT NULL REFERENCES customer_order(id),
    line_number integer NOT NULL,
    sku_id uuid NOT NULL,
    quantity numeric(19,3) NOT NULL,
    unit varchar(20) NOT NULL,
    unit_price numeric(19,2) NOT NULL,
    line_total numeric(19,2) NOT NULL,
    origin_city_snapshot varchar(160),
    origin_region_code_snapshot varchar(32),
    CONSTRAINT uk_order_line_number UNIQUE(order_id,line_number)
);
CREATE INDEX idx_order_line_order_id ON order_line(order_id);

CREATE TABLE inventory_location (
    id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    code varchar(40) NOT NULL,
    name varchar(160) NOT NULL,
    region_code varchar(32) NOT NULL,
    region_name varchar(160) NOT NULL,
    city varchar(160) NOT NULL,
    address_line varchar(400) NOT NULL,
    location_type varchar(30) NOT NULL,
    public_pickup_enabled boolean NOT NULL,
    latitude numeric(10,7),
    longitude numeric(10,7),
    active boolean NOT NULL,
    version bigint,
    CONSTRAINT uk_inventory_location_seller_code UNIQUE(seller_id,code),
    CONSTRAINT chk_inventory_geo_pair CHECK ((latitude IS NULL) = (longitude IS NULL))
);
CREATE INDEX idx_inventory_location_seller ON inventory_location(seller_id);

-- Initial versioned catalog schema for new PostgreSQL databases.

CREATE TABLE catalog_category (
    id uuid PRIMARY KEY,
    hcc_code varchar(20) NOT NULL,
    product_type varchar(16) NOT NULL,
    parent_id uuid REFERENCES catalog_category(id),
    label varchar(160) NOT NULL,
    active boolean NOT NULL,
    version bigint,
    CONSTRAINT uk_catalog_category_code UNIQUE(hcc_code),
    CONSTRAINT chk_hcc_digits CHECK (
        hcc_code ~ '^[0-9]{20}$'
        AND substring(hcc_code,1,2)<>'00'
        AND (substring(hcc_code,3,2)<>'00' OR substring(hcc_code,5,16)='0000000000000000')
        AND (substring(hcc_code,5,3)<>'000' OR substring(hcc_code,8,13)='0000000000000')
        AND (substring(hcc_code,8,5)<>'00000' OR substring(hcc_code,13,8)='00000000')
    ),
    CONSTRAINT chk_catalog_type CHECK (product_type IN ('PHYSICAL','SERVICE','DIGITAL'))
);
CREATE INDEX idx_catalog_category_parent ON catalog_category(parent_id);
CREATE TABLE catalog_attribute_definition (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    kind varchar(16) NOT NULL,
    canonical_unit varchar(16),
    active boolean NOT NULL,
    CONSTRAINT uk_attribute_code UNIQUE(code),
    CONSTRAINT chk_attribute_kind CHECK (kind IN ('ENUM','DECIMAL','BOOLEAN','TEXT'))
);
CREATE TABLE catalog_category_attribute_rule (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES catalog_category(id),
    attribute_id uuid NOT NULL REFERENCES catalog_attribute_definition(id),
    required boolean NOT NULL,
    filterable boolean NOT NULL,
    CONSTRAINT uk_category_attribute UNIQUE(category_id,attribute_id)
);
CREATE TABLE catalog_product (
    id uuid PRIMARY KEY,
    category_id uuid NOT NULL REFERENCES catalog_category(id),
    canonical_name varchar(200) NOT NULL
);
CREATE INDEX idx_catalog_product_category ON catalog_product(category_id);
CREATE TABLE catalog_listing (
    id uuid PRIMARY KEY,
    seller_id uuid NOT NULL,
    product_id uuid NOT NULL REFERENCES catalog_product(id),
    status varchar(16) NOT NULL,
    version bigint,
    CONSTRAINT chk_listing_status CHECK(status IN ('DRAFT','PUBLIC','UNLISTED','ARCHIVED'))
);
CREATE INDEX idx_catalog_listing_seller ON catalog_listing(seller_id);
CREATE INDEX idx_catalog_listing_product ON catalog_listing(product_id);
CREATE TABLE catalog_sku (
    id uuid PRIMARY KEY,
    listing_id uuid NOT NULL REFERENCES catalog_listing(id),
    seller_sku_code varchar(80) NOT NULL,
    tracking_mode varchar(16) NOT NULL,
    CONSTRAINT uk_sku_listing_code UNIQUE(listing_id,seller_sku_code),
    CONSTRAINT chk_tracking_mode CHECK(tracking_mode IN ('TRACKED','UNTRACKED'))
);
CREATE TABLE catalog_sku_attribute_value (
    id uuid PRIMARY KEY,
    sku_id uuid NOT NULL REFERENCES catalog_sku(id),
    attribute_id uuid NOT NULL REFERENCES catalog_attribute_definition(id),
    canonical_value varchar(256) NOT NULL,
    CONSTRAINT uk_sku_attribute UNIQUE(sku_id,attribute_id)
);
-- DB cannot yet enforce parent depth, enum vocabulary, category attribution and seller ACL;
-- authoritative transactional services and S2 schema review must cover these cases.
