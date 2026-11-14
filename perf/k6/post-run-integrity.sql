-- PostgreSQL read-only verification; run after the k6 test using a read-only DB role.
-- Replace :run_start/:run_end with driver parameters (TIMESTAMPTZ).
-- This query set is global by design; for isolated seeded DB all rows are test rows.
SELECT COUNT(*) AS invalid_balances FROM inventory_balance
 WHERE on_hand<0 OR reserved<0 OR reserved>on_hand;
SELECT COUNT(*) AS mismatched_reservations
  FROM inventory_reservation r
  JOIN order_line l ON l.id=r.order_line_id
  JOIN customer_order o ON o.id=r.order_id
 WHERE r.order_id<>l.order_id OR r.sku_id<>l.sku_id
    OR r.seller_id<>o.seller_id OR r.quantity<>l.quantity;
SELECT aggregate_id,event_type,COUNT(*) AS duplicates
  FROM order_outbox_event
 GROUP BY aggregate_id,event_type
HAVING COUNT(*)>1;
SELECT status,COUNT(*) FROM order_outbox_event GROUP BY status ORDER BY status;
SELECT status,COUNT(*) FROM customer_order GROUP BY status ORDER BY status;
SELECT COUNT(*) AS confirmed_without_reservation
  FROM customer_order o
 WHERE status='CONFIRMED'
   AND NOT EXISTS(SELECT 1 FROM inventory_reservation r WHERE r.order_id=o.id);
SELECT COUNT(*) AS wrong_reservation_count
  FROM customer_order o
 WHERE o.status='CONFIRMED'
   AND (SELECT COUNT(*) FROM inventory_reservation r WHERE r.order_id=o.id)
      <> (SELECT COUNT(*) FROM order_line l WHERE l.order_id=o.id);
SELECT COALESCE(MIN(occurred_at),now()) AS oldest_undelivered,
       COUNT(*) AS pending_event_count
  FROM order_outbox_event WHERE status IN ('PENDING','IN_FLIGHT','DEAD_LETTER');
