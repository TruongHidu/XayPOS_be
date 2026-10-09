-- Add only the operational grants needed by staff ORDER; preserve user GRANT/DENY and all snapshots.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND ((r.code='CASHIER' AND p.code IN ('ORDER_CREATE','ORDER_UPDATE','TABLE_VIEW'))
    OR (r.code='MANAGER' AND p.code IN ('ORDER_VIEW','ORDER_CREATE','ORDER_UPDATE','ORDER_CANCEL','TABLE_VIEW')))
ON CONFLICT (role_id,permission_id) DO NOTHING;
