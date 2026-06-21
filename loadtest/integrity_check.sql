-- Req 9 data-integrity assertions. Run after the stress test.
-- Every count below MUST be 0 for the system to have preserved integrity
-- under load (no data loss, no half-applied writes, no inconsistency).

\echo '--- 1. Negative stock (must be 0) ---'
SELECT count(*) AS negative_stock
FROM products
WHERE stock_quantity < 0;

\echo '--- 2. Orphaned orders: an order with no line items (must be 0) ---'
SELECT count(*) AS orphaned_orders
FROM orders o
WHERE NOT EXISTS (SELECT 1 FROM order_items oi WHERE oi.order_id = o.id);

\echo '--- 3. Order items referencing a missing product (must be 0) ---'
SELECT count(*) AS items_missing_product
FROM order_items oi
WHERE NOT EXISTS (SELECT 1 FROM products p WHERE p.id = oi.product_id);

\echo '--- 4. Orders whose stored total != sum(items) (must be 0) ---'
SELECT count(*) AS inconsistent_totals
FROM (
    SELECT o.id, o.total, COALESCE(SUM(oi.quantity * oi.unit_price), 0) AS computed
    FROM orders o
    LEFT JOIN order_items oi ON oi.order_id = o.id
    GROUP BY o.id, o.total
) t
WHERE abs(t.total - t.computed) > 0.001;
