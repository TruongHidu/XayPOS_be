-- Read-only operational reports filter each fact table by tenant, currency and
-- its own business timestamp. Partial indexes keep terminal-status indexes small.
CREATE INDEX ix_report_payment_success_paid
    ON payments (restaurant_id, currency_code, paid_at, id, order_id)
    WHERE status = 'SUCCESS';

CREATE INDEX ix_report_order_completed
    ON orders (restaurant_id, currency_code, completed_at, id)
    WHERE status = 'COMPLETED';

CREATE INDEX ix_report_order_cancelled
    ON orders (restaurant_id, currency_code, cancelled_at, id)
    WHERE status = 'CANCELLED';

CREATE INDEX ix_report_order_created
    ON orders (restaurant_id, currency_code, created_at, id);
