INSERT INTO permissions (code, module, name)
VALUES
    ('INGREDIENT_VIEW', 'INVENTORY', 'View ingredient catalog'),
    ('INGREDIENT_CREATE', 'INVENTORY', 'Create ingredient'),
    ('INGREDIENT_UPDATE', 'INVENTORY', 'Update ingredient'),
    ('INGREDIENT_DELETE', 'INVENTORY', 'Delete ingredient')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL
  AND r.is_system
  AND r.code IN ('OWNER', 'SUPER_ADMIN')
  AND p.code IN ('INGREDIENT_VIEW', 'INGREDIENT_CREATE', 'INGREDIENT_UPDATE', 'INGREDIENT_DELETE')
ON CONFLICT (role_id, permission_id) DO NOTHING;
