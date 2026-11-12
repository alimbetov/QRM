-- R1B.2 additional PostgreSQL catalog DDL, manually applied AFTER V001.
-- Not automatically enabled until the complete service schema and migration history are reviewed.
CREATE TABLE catalog_attribute_enum_option (
    id uuid PRIMARY KEY,
    attribute_id uuid NOT NULL REFERENCES catalog_attribute_definition(id),
    code varchar(64) NOT NULL,
    active boolean NOT NULL,
    CONSTRAINT uk_attribute_enum_option UNIQUE(attribute_id,code),
    CONSTRAINT chk_attribute_enum_code CHECK (code ~ '^[A-Z][A-Z0-9_]{0,63}$')
);
CREATE INDEX idx_catalog_enum_attribute_active ON catalog_attribute_enum_option(attribute_id,active);
