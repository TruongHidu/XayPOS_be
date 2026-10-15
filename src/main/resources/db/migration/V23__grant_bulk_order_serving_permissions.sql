-- Bulk serving uses existing permissions; preserve custom roles and user GRANT/DENY.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND r.code IN ('CASHIER','KITCHEN')
  AND p.code IN ('KITCHEN_MARK_READY','ORDER_MARK_SERVED')
ON CONFLICT (role_id,permission_id) DO NOTHING;
