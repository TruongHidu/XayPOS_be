-- Fail instead of choosing, merging, cancelling or deleting historical orders.
DO $$
DECLARE duplicate_sessions TEXT;
BEGIN
    SELECT string_agg(format('tenant=%s session=%s count=%s', restaurant_id, table_session_id, serving_count), '; ')
    INTO duplicate_sessions
    FROM (
        SELECT restaurant_id, table_session_id, count(*) AS serving_count
        FROM orders
        WHERE service_type = 'DINE_IN' AND table_session_id IS NOT NULL
          AND status IN ('OPEN','CONFIRMED','PREPARING','READY','SERVED')
        GROUP BY restaurant_id, table_session_id HAVING count(*) > 1
        ORDER BY restaurant_id, table_session_id LIMIT 20
    ) duplicates;
    IF duplicate_sessions IS NOT NULL THEN
        RAISE EXCEPTION 'V19: duplicate serving orders per session; no data changed. %', duplicate_sessions
            USING HINT = 'Run the identifier-only diagnostic in docs/order-serving-migration.md and obtain an explicit data resolution decision.';
    END IF;
END $$;

CREATE UNIQUE INDEX ux_order_serving_session
ON orders(restaurant_id, table_session_id)
WHERE service_type = 'DINE_IN' AND table_session_id IS NOT NULL
  AND status IN ('OPEN','CONFIRMED','PREPARING','READY','SERVED');
