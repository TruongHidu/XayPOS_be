-- DINE_IN completion closes its exact session atomically; existing TABLE_CLOSE guards still apply.
-- This grant also permits manual close/cancel where their own business rules allow it.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND r.code IN ('CASHIER','MANAGER') AND p.code='TABLE_CLOSE'
ON CONFLICT (role_id,permission_id) DO NOTHING;
