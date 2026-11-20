-- MANAGER runs a full service shift: browse the menu, open tables, follow the kitchen,
-- take payment and read the day's revenue. Owner-only administration (packages,
-- staff permissions, menu/recipe editing, inventory, cashbook, detailed reports) is unchanged.
-- Only the global system role is touched; user GRANT/DENY overrides and tenant custom roles stay as they are.
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system AND r.code='MANAGER'
  AND p.is_active
  AND p.code IN (
      'MENU_VIEW','MENU_AVAILABILITY_UPDATE',
      'TABLE_OPEN',
      'ORDER_MARK_SERVED',
      'KITCHEN_VIEW','KITCHEN_START_COOKING','KITCHEN_MARK_READY',
      'PAYMENT_VIEW','PAYMENT_CREATE','PAYMENT_CONFIRM',
      'REPORT_DAILY_VIEW'
  )
ON CONFLICT (role_id,permission_id) DO NOTHING;
