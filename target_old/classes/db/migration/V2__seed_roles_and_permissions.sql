INSERT INTO roles (code, name, is_system)
VALUES
    ('SUPER_ADMIN', 'Super administrator', true),
    ('OWNER', 'Owner', true),
    ('MANAGER', 'Manager', true),
    ('WAITER', 'Waiter', true),
    ('KITCHEN', 'Kitchen staff', true),
    ('CASHIER', 'Cashier', true)
ON CONFLICT DO NOTHING;

INSERT INTO permissions (code, module, name)
VALUES
    ('MENU_VIEW', 'MENU', 'View menu'), ('MENU_CREATE', 'MENU', 'Create menu'), ('MENU_UPDATE', 'MENU', 'Update menu'), ('MENU_DELETE', 'MENU', 'Delete menu'),
    ('TABLE_VIEW', 'TABLE', 'View tables'), ('TABLE_CREATE', 'TABLE', 'Create table'), ('TABLE_UPDATE', 'TABLE', 'Update table'), ('TABLE_OPEN', 'TABLE', 'Open table'), ('TABLE_CLOSE', 'TABLE', 'Close table'),
    ('ORDER_VIEW', 'ORDER', 'View orders'), ('ORDER_CREATE', 'ORDER', 'Create order'), ('ORDER_UPDATE', 'ORDER', 'Update order'), ('ORDER_CANCEL', 'ORDER', 'Cancel order'), ('ORDER_MARK_SERVED', 'ORDER', 'Mark order served'),
    ('KITCHEN_VIEW', 'KITCHEN', 'View kitchen'), ('KITCHEN_START_COOKING', 'KITCHEN', 'Start cooking'), ('KITCHEN_MARK_READY', 'KITCHEN', 'Mark ready'),
    ('PAYMENT_VIEW', 'PAYMENT', 'View payments'), ('PAYMENT_CREATE', 'PAYMENT', 'Create payment'), ('PAYMENT_CONFIRM', 'PAYMENT', 'Confirm payment'), ('PAYMENT_REFUND', 'PAYMENT', 'Refund payment'),
    ('STAFF_VIEW', 'STAFF', 'View staff'), ('STAFF_CREATE', 'STAFF', 'Create staff'), ('STAFF_UPDATE', 'STAFF', 'Update staff'), ('STAFF_DISABLE', 'STAFF', 'Disable staff'), ('STAFF_PERMISSION_MANAGE', 'STAFF', 'Manage staff permissions'),
    ('INVENTORY_VIEW', 'INVENTORY', 'View inventory'), ('INVENTORY_RECEIVE', 'INVENTORY', 'Receive inventory'), ('INVENTORY_ISSUE', 'INVENTORY', 'Issue inventory'), ('INVENTORY_TRANSFER', 'INVENTORY', 'Transfer inventory'), ('INVENTORY_ADJUST', 'INVENTORY', 'Adjust inventory'), ('STOCK_AUDIT_CONFIRM', 'INVENTORY', 'Confirm stock audit'),
    ('REPORT_DAILY_VIEW', 'REPORT', 'View daily report'), ('REPORT_DETAIL_VIEW', 'REPORT', 'View detail report'), ('ANALYTICS_VIEW', 'REPORT', 'View analytics'), ('AI_FORECAST_VIEW', 'AI', 'View AI forecast')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.code IN ('SUPER_ADMIN', 'OWNER') AND r.restaurant_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p ON p.code IN (
    'MENU_VIEW', 'TABLE_VIEW', 'TABLE_OPEN', 'TABLE_CLOSE',
    'ORDER_VIEW', 'ORDER_CREATE', 'ORDER_UPDATE', 'ORDER_MARK_SERVED'
)
WHERE r.code = 'WAITER' AND r.restaurant_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p ON p.code IN ('ORDER_VIEW', 'KITCHEN_VIEW', 'KITCHEN_START_COOKING', 'KITCHEN_MARK_READY')
WHERE r.code = 'KITCHEN' AND r.restaurant_id IS NULL
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r JOIN permissions p ON p.code IN ('MENU_VIEW', 'ORDER_VIEW', 'PAYMENT_VIEW', 'PAYMENT_CREATE', 'PAYMENT_CONFIRM', 'REPORT_DAILY_VIEW')
WHERE r.code = 'CASHIER' AND r.restaurant_id IS NULL
ON CONFLICT DO NOTHING;
