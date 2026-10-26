INSERT INTO permissions (code, module, name)
VALUES ('MENU_AVAILABILITY_UPDATE', 'MENU', 'Update menu item availability')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL
  AND r.is_system
  AND r.code IN ('OWNER', 'SUPER_ADMIN')
  AND p.code = 'MENU_AVAILABILITY_UPDATE'
ON CONFLICT (role_id, permission_id) DO NOTHING;
