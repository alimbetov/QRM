-- PostgreSQL-only integrity protection: no direct insert/update/delete of
-- SKU / SKU attributes once the parent listing is PUBLIC/UNLISTED/ARCHIVED.
-- Controlled migrations only. Uses row lock to serialize with publication.
CREATE OR REPLACE FUNCTION catalog_assert_draft_sku()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    checked_listing uuid;
    checked_sku uuid;
    listing_state varchar(16);
BEGIN
    IF TG_TABLE_NAME = 'catalog_sku' THEN
        IF TG_OP = 'DELETE' THEN
            checked_listing := OLD.listing_id;
        ELSE
            checked_listing := NEW.listing_id;
        END IF;
        IF TG_OP = 'UPDATE' AND OLD.listing_id IS DISTINCT FROM NEW.listing_id THEN
            RAISE EXCEPTION 'Changing SKU listing reference is not permitted';
        END IF;
    ELSE
        IF TG_OP = 'DELETE' THEN
            checked_sku := OLD.sku_id;
        ELSE
            checked_sku := NEW.sku_id;
        END IF;
        IF TG_OP = 'UPDATE' AND
           (OLD.sku_id IS DISTINCT FROM NEW.sku_id OR
            OLD.attribute_id IS DISTINCT FROM NEW.attribute_id) THEN
            RAISE EXCEPTION 'Changing SKU attribute identity is not permitted';
        END IF;
        SELECT s.listing_id INTO checked_listing FROM catalog_sku s WHERE s.id = checked_sku;
    END IF;

    SELECT l.status INTO listing_state
    FROM catalog_listing l WHERE l.id = checked_listing FOR UPDATE;
    IF listing_state IS DISTINCT FROM 'DRAFT' THEN
        RAISE EXCEPTION 'SKU facts can be modified only while listing is DRAFT';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
-- STATEMENT BREAK
CREATE TRIGGER trg_catalog_sku_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON catalog_sku
FOR EACH ROW EXECUTE FUNCTION catalog_assert_draft_sku();
-- STATEMENT BREAK
CREATE TRIGGER trg_catalog_sku_attr_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON catalog_sku_attribute_value
FOR EACH ROW EXECUTE FUNCTION catalog_assert_draft_sku();
