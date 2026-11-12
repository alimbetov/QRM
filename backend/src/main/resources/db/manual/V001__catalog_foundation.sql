-- R1B PostgreSQL 17 review candidate: manual DDL, NOT auto-executed in prod.
-- This file must be applied via a controlled migration process before ddl-auto=validate.
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
