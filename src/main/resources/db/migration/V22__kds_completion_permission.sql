-- KDS uses existing permissions. Completion is a separate responsibility from editing or serving.
INSERT INTO permissions (code,module,name) VALUES
    ('ORDER_COMPLETE','ORDER','Complete paid and served order')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND r.code IN ('OWNER','MANAGER','WAITER','CASHIER') AND p.code='ORDER_COMPLETE'
ON CONFLICT (role_id,permission_id) DO NOTHING;

-- Matches tenant/status filtering and the stable coalesced queue order. Old submitted
-- lines with sent_to_kitchen_at NULL remain eligible via created_at fallback.
CREATE INDEX ix_order_item_kitchen_queue
    ON order_items (restaurant_id,status,(coalesce(sent_to_kitchen_at,created_at)),id);
