INSERT INTO permissions (code, module, name)
VALUES
    ('PACKAGE_VIEW', 'SUBSCRIPTION', 'View packages'),
    ('PACKAGE_MANAGE', 'SUBSCRIPTION', 'Manage packages and features'),
    ('SUBSCRIPTION_VIEW', 'SUBSCRIPTION', 'View subscriptions'),
    ('SUBSCRIPTION_MANAGE', 'SUBSCRIPTION', 'Manage subscriptions')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SUPER_ADMIN'
  AND r.restaurant_id IS NULL
  AND p.code IN ('PACKAGE_VIEW', 'PACKAGE_MANAGE', 'SUBSCRIPTION_VIEW', 'SUBSCRIPTION_MANAGE')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'OWNER'
  AND r.restaurant_id IS NULL
  AND p.code IN ('PACKAGE_VIEW', 'SUBSCRIPTION_VIEW')
ON CONFLICT DO NOTHING;
