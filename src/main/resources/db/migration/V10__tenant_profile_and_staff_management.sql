INSERT INTO permissions (code, module, name)
VALUES ('RESTAURANT_PROFILE_VIEW', 'RESTAURANT_PROFILE', 'View own restaurant profile'),
       ('RESTAURANT_PROFILE_UPDATE', 'RESTAURANT_PROFILE', 'Update own restaurant profile')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND ((r.code IN ('OWNER', 'MANAGER') AND p.code IN ('RESTAURANT_PROFILE_VIEW', 'RESTAURANT_PROFILE_UPDATE'))
    OR (r.code = 'MANAGER' AND p.code IN ('STAFF_VIEW', 'STAFF_CREATE', 'STAFF_UPDATE', 'STAFF_DISABLE')))
ON CONFLICT DO NOTHING;
