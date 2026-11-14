-- R2.1 hardening. One immutable order header declares its complete set of lines.
-- Historical orders are backfilled from their existing line sets. If an
-- incomplete historical order exists, validation fails instead of guessing.
ALTER TABLE customer_order ADD COLUMN expected_line_count integer;
UPDATE customer_order o SET expected_line_count=
    (SELECT COUNT(*) FROM order_line l WHERE l.order_id=o.id);
ALTER TABLE customer_order ALTER COLUMN expected_line_count SET NOT NULL;
ALTER TABLE customer_order ADD CONSTRAINT chk_expected_line_count
    CHECK (expected_line_count BETWEEN 1 AND 10000);

CREATE OR REPLACE FUNCTION guard_order_line_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    declared_count integer;
    current_count bigint;
    order_state varchar(40);
BEGIN
    -- Serialize all line inserts with order mutations and other insertions.
    SELECT expected_line_count,status INTO declared_count,order_state
    FROM customer_order WHERE id=NEW.order_id FOR UPDATE;
    IF NOT FOUND OR order_state <> 'PLACED' THEN
        RAISE EXCEPTION 'Order does not exist or is no longer PLACED' USING ERRCODE='23514';
    END IF;
    SELECT COUNT(*) INTO current_count FROM order_line WHERE order_id=NEW.order_id;
    IF current_count >= declared_count THEN
        RAISE EXCEPTION 'Order line snapshot already complete' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_order_line_insert_guard
BEFORE INSERT ON order_line FOR EACH ROW EXECUTE FUNCTION guard_order_line_insert();

CREATE OR REPLACE FUNCTION verify_sealed_order_economics()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    actual_lines bigint;
    actual_subtotal numeric(19,2);
BEGIN
    -- Deferred check observes all lines inserted during the order transaction.
    SELECT COUNT(*),COALESCE(SUM(line_total),0) INTO actual_lines,actual_subtotal
    FROM order_line WHERE order_id=NEW.id;
    IF actual_lines <> NEW.expected_line_count OR
       actual_subtotal + NEW.delivery_fee <> NEW.total_amount THEN
        RAISE EXCEPTION 'Incomplete order lines or total mismatch for order %',NEW.id
            USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_order_sealed_economics
AFTER INSERT OR UPDATE ON customer_order
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION verify_sealed_order_economics();

CREATE OR REPLACE FUNCTION forbid_order_line_count_change()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.expected_line_count IS DISTINCT FROM OLD.expected_line_count THEN
        RAISE EXCEPTION 'Order expected line count is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_order_line_count_immutable
BEFORE UPDATE ON customer_order FOR EACH ROW
EXECUTE FUNCTION forbid_order_line_count_change();
