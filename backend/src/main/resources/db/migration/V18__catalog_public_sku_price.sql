-- R2.3B: optional seller-published unit price in KZT.
-- Existing PUBLIC listings remain readable without an invented price.
-- V3 trigger catalog_assert_draft_sku() already guards ALL catalog_sku UPDATEs
-- with the parent listing row lock, including changes to this new column.
ALTER TABLE catalog_sku
    ADD COLUMN public_unit_price_kzt numeric(19,2);
ALTER TABLE catalog_sku
    ADD CONSTRAINT chk_catalog_sku_public_price
        CHECK (public_unit_price_kzt IS NULL OR public_unit_price_kzt > 0);
