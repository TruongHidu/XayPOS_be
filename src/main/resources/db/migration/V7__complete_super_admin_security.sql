INSERT INTO permissions (code, module, name)
VALUES
    ('ADMIN_DASHBOARD_VIEW', 'ADMIN', 'View system administration dashboard'),
    ('RESTAURANT_VIEW', 'ADMIN', 'View restaurants'),
    ('RESTAURANT_MANAGE', 'ADMIN', 'Manage restaurant status'),
    ('AUDIT_VIEW', 'AUDIT', 'View audit logs')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT role.id, permission.id
FROM roles role
CROSS JOIN permissions permission
WHERE role.code = 'SUPER_ADMIN'
  AND role.restaurant_id IS NULL
  AND permission.code IN (
      'ADMIN_DASHBOARD_VIEW',
      'RESTAURANT_VIEW',
      'RESTAURANT_MANAGE',
      'AUDIT_VIEW'
  )
ON CONFLICT DO NOTHING;

CREATE INDEX IF NOT EXISTS ix_restaurants_admin_status_created
    ON restaurants (status, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_restaurants_admin_created
    ON restaurants (created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS ix_restaurant_subscriptions_restaurant_created
    ON restaurant_subscriptions (restaurant_id, created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS ix_restaurant_subscriptions_status_end
    ON restaurant_subscriptions (status, end_at);

CREATE INDEX IF NOT EXISTS ix_audit_logs_created
    ON audit_logs (created_at DESC, id DESC);

CREATE INDEX IF NOT EXISTS ix_audit_logs_action_created
    ON audit_logs (action_code, created_at DESC, id DESC);
