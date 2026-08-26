INSERT INTO features (code, name)
VALUES
    ('MENU_MANAGEMENT', 'Menu management'),
    ('POS_QUICK_ORDER', 'POS quick order'),
    ('ORDER_MANAGEMENT', 'Order management'),
    ('PAYMENT_MANAGEMENT', 'Payment management'),
    ('DAILY_REVENUE', 'Daily revenue'),
    ('QR_STATIC_ORDER', 'Static QR ordering'),
    ('RECEIPT_PRINT', 'Receipt printing'),
    ('RECEIPT_REPRINT', 'Receipt reprinting'),
    ('TABLE_MANAGEMENT', 'Table management'),
    ('STAFF_MANAGEMENT', 'Staff management'),
    ('STAFF_PERMISSION', 'Staff permission management'),
    ('KITCHEN_DISPLAY', 'Kitchen display'),
    ('QR_TABLE_ORDER', 'Table QR ordering'),
    ('DETAIL_REPORT', 'Detailed reports'),
    ('KITCHEN_TICKET_PRINT', 'Kitchen ticket printing'),
    ('KITCHEN_TICKET_REPRINT', 'Kitchen ticket reprinting'),
    ('INVENTORY_MANAGEMENT', 'Inventory management'),
    ('RECIPE_MANAGEMENT', 'Recipe management'),
    ('LOW_STOCK_ALERT', 'Low stock alerts'),
    ('STOCK_AUDIT', 'Stock audit'),
    ('ADVANCED_ANALYTICS', 'Advanced analytics'),
    ('AI_DEMAND_FORECAST', 'AI demand forecast'),
    ('AI_MENU_RECOMMENDATION', 'AI menu recommendation')
ON CONFLICT (code) DO NOTHING;

INSERT INTO packages (
    code,
    name,
    description,
    price_amount,
    currency_code,
    billing_cycle_months
)
VALUES
    ('BASIC', 'Basic', 'Core POS features for small restaurants', 199000, 'VND', 1),
    ('PRO', 'Pro', 'Operations and staff features for growing restaurants', 399000, 'VND', 1),
    ('PREMIUM', 'Premium', 'Inventory, analytics and AI features', 699000, 'VND', 1)
ON CONFLICT (code) DO NOTHING;

WITH package_feature_seed(package_code, feature_code) AS (
    VALUES
        ('BASIC', 'MENU_MANAGEMENT'),
        ('BASIC', 'POS_QUICK_ORDER'),
        ('BASIC', 'ORDER_MANAGEMENT'),
        ('BASIC', 'PAYMENT_MANAGEMENT'),
        ('BASIC', 'DAILY_REVENUE'),
        ('BASIC', 'QR_STATIC_ORDER'),
        ('BASIC', 'RECEIPT_PRINT'),
        ('BASIC', 'RECEIPT_REPRINT'),

        ('PRO', 'MENU_MANAGEMENT'),
        ('PRO', 'POS_QUICK_ORDER'),
        ('PRO', 'ORDER_MANAGEMENT'),
        ('PRO', 'PAYMENT_MANAGEMENT'),
        ('PRO', 'DAILY_REVENUE'),
        ('PRO', 'QR_STATIC_ORDER'),
        ('PRO', 'RECEIPT_PRINT'),
        ('PRO', 'RECEIPT_REPRINT'),
        ('PRO', 'TABLE_MANAGEMENT'),
        ('PRO', 'STAFF_MANAGEMENT'),
        ('PRO', 'STAFF_PERMISSION'),
        ('PRO', 'KITCHEN_DISPLAY'),
        ('PRO', 'QR_TABLE_ORDER'),
        ('PRO', 'DETAIL_REPORT'),
        ('PRO', 'KITCHEN_TICKET_PRINT'),
        ('PRO', 'KITCHEN_TICKET_REPRINT'),

        ('PREMIUM', 'MENU_MANAGEMENT'),
        ('PREMIUM', 'POS_QUICK_ORDER'),
        ('PREMIUM', 'ORDER_MANAGEMENT'),
        ('PREMIUM', 'PAYMENT_MANAGEMENT'),
        ('PREMIUM', 'DAILY_REVENUE'),
        ('PREMIUM', 'QR_STATIC_ORDER'),
        ('PREMIUM', 'RECEIPT_PRINT'),
        ('PREMIUM', 'RECEIPT_REPRINT'),
        ('PREMIUM', 'TABLE_MANAGEMENT'),
        ('PREMIUM', 'STAFF_MANAGEMENT'),
        ('PREMIUM', 'STAFF_PERMISSION'),
        ('PREMIUM', 'KITCHEN_DISPLAY'),
        ('PREMIUM', 'QR_TABLE_ORDER'),
        ('PREMIUM', 'DETAIL_REPORT'),
        ('PREMIUM', 'KITCHEN_TICKET_PRINT'),
        ('PREMIUM', 'KITCHEN_TICKET_REPRINT'),
        ('PREMIUM', 'INVENTORY_MANAGEMENT'),
        ('PREMIUM', 'RECIPE_MANAGEMENT'),
        ('PREMIUM', 'LOW_STOCK_ALERT'),
        ('PREMIUM', 'STOCK_AUDIT'),
        ('PREMIUM', 'ADVANCED_ANALYTICS'),
        ('PREMIUM', 'AI_DEMAND_FORECAST'),
        ('PREMIUM', 'AI_MENU_RECOMMENDATION')
)
INSERT INTO package_features (package_id, feature_id)
SELECT p.id, f.id
FROM package_feature_seed seed
JOIN packages p ON p.code = seed.package_code
JOIN features f ON f.code = seed.feature_code
ON CONFLICT (package_id, feature_id) DO NOTHING;
