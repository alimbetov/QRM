-- R2.1 hardening. Reject cross-tenant stock and reservation associations,
-- even when normal application service validation is bypassed.
CREATE OR REPLACE FUNCTION protect_seller_reference()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.seller_id IS DISTINCT FROM OLD.seller_id THEN
        RAISE EXCEPTION 'Seller ownership cannot change' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_location_owner_immutable
BEFORE UPDATE ON inventory_location FOR EACH ROW EXECUTE FUNCTION protect_seller_reference();
CREATE TRIGGER trg_listing_owner_immutable
BEFORE UPDATE ON catalog_listing FOR EACH ROW EXECUTE FUNCTION protect_seller_reference();

CREATE OR REPLACE FUNCTION enforce_inventory_balance_ownership()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE location_seller uuid; sku_seller uuid;
BEGIN
    IF TG_OP='UPDATE' AND
       (NEW.seller_id IS DISTINCT FROM OLD.seller_id
        OR NEW.location_id IS DISTINCT FROM OLD.location_id
        OR NEW.sku_id IS DISTINCT FROM OLD.sku_id) THEN
        RAISE EXCEPTION 'Inventory balance identity cannot change' USING ERRCODE='23514';
    END IF;
    SELECT l.seller_id INTO location_seller
    FROM inventory_location l WHERE l.id=NEW.location_id;
    SELECT listing.seller_id INTO sku_seller
    FROM catalog_sku sku
    JOIN catalog_listing listing ON listing.id=sku.listing_id
    WHERE sku.id=NEW.sku_id;
    IF location_seller IS DISTINCT FROM NEW.seller_id
       OR sku_seller IS DISTINCT FROM NEW.seller_id THEN
        RAISE EXCEPTION 'Inventory balance references a foreign seller/location/SKU'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_inventory_balance_ownership
BEFORE INSERT OR UPDATE ON inventory_balance FOR EACH ROW
EXECUTE FUNCTION enforce_inventory_balance_ownership();

CREATE OR REPLACE FUNCTION enforce_inventory_reservation_links()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE order_seller uuid; line_order uuid; line_sku uuid; line_quantity numeric(19,3);
BEGIN
    IF TG_OP='UPDATE' AND
       (NEW.order_id IS DISTINCT FROM OLD.order_id
        OR NEW.order_line_id IS DISTINCT FROM OLD.order_line_id
        OR NEW.seller_id IS DISTINCT FROM OLD.seller_id
        OR NEW.location_id IS DISTINCT FROM OLD.location_id
        OR NEW.sku_id IS DISTINCT FROM OLD.sku_id
        OR NEW.quantity IS DISTINCT FROM OLD.quantity) THEN
        RAISE EXCEPTION 'Inventory reservation references are immutable' USING ERRCODE='23514';
    END IF;
    SELECT seller_id INTO order_seller FROM customer_order WHERE id=NEW.order_id;
    SELECT order_id,sku_id,quantity INTO line_order,line_sku,line_quantity
    FROM order_line WHERE id=NEW.order_line_id;
    IF order_seller IS DISTINCT FROM NEW.seller_id
       OR line_order IS DISTINCT FROM NEW.order_id
       OR line_sku IS DISTINCT FROM NEW.sku_id
       OR line_quantity IS DISTINCT FROM NEW.quantity THEN
        RAISE EXCEPTION 'Inventory reservation does not match order line and seller'
            USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM inventory_balance b
        WHERE b.seller_id=NEW.seller_id
          AND b.location_id=NEW.location_id AND b.sku_id=NEW.sku_id
    ) THEN
        RAISE EXCEPTION 'Inventory reservation has no matching balance'
            USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reservation_reference_guard
BEFORE INSERT OR UPDATE ON inventory_reservation FOR EACH ROW
EXECUTE FUNCTION enforce_inventory_reservation_links();
