-- Read-only PostgreSQL qualification, independent of HTTP DTO.
-- psql -v order_id=<confirmed-uuid> -f deploy/staging/check-order.sql
\set ON_ERROR_STOP on
SELECT set_config('zakaz.staging_order_id', :'order_id', false);
DO $$
DECLARE
  oid uuid := current_setting('zakaz.staging_order_id')::uuid;
  order_amount numeric(19,2);
  order_status text;
  reservation_count integer;
  reservation_qty numeric(19,3);
  outbox_count integer;
  movement_count integer;
BEGIN
  SELECT o.total_amount,o.status INTO order_amount,order_status
    FROM customer_order o WHERE o.id=oid;
  IF order_status IS DISTINCT FROM 'CONFIRMED' OR order_amount<>125000.50 THEN
    RAISE EXCEPTION 'Order status or amount incorrect: % %',order_status,order_amount;
  END IF;
  SELECT count(*),coalesce(sum(quantity),0)
    INTO reservation_count,reservation_qty
    FROM inventory_reservation
    WHERE order_id=oid AND status='RESERVED';
  IF reservation_count<>1 OR reservation_qty<>1.000 THEN
    RAISE EXCEPTION 'Reservation cardinality/quantity incorrect';
  END IF;
  SELECT count(*) INTO outbox_count FROM order_outbox_event
    WHERE aggregate_id=oid AND event_type='ORDER_CONFIRMED';
  IF outbox_count<>1 THEN
    RAISE EXCEPTION 'Duplicate or missing confirmation outbox event';
  END IF;
  SELECT count(*) INTO movement_count FROM inventory_movement
    WHERE order_id=oid AND kind='RESERVATION';
  IF movement_count<>1 THEN
    RAISE EXCEPTION 'Missing or duplicated immutable reservation movement';
  END IF;
  IF EXISTS (SELECT 1 FROM inventory_balance
             WHERE on_hand<0 OR reserved<0 OR reserved>on_hand) THEN
    RAISE EXCEPTION 'Oversold or invalid balance found';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM inventory_reservation r
    JOIN inventory_balance b ON b.seller_id=r.seller_id
      AND b.location_id=r.location_id AND b.sku_id=r.sku_id
    WHERE r.order_id=oid AND b.reserved>=r.quantity
  ) THEN
    RAISE EXCEPTION 'No inventory balance matching reservation';
  END IF;
  RAISE NOTICE 'PASS: exact confirmed order, stock reservation, movement and outbox';
END $$;
