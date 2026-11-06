CREATE TABLE warehouses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    code VARCHAR(50) NOT NULL CHECK (btrim(code) <> ''),
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    is_default BOOLEAN NOT NULL DEFAULT false,
    is_active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_warehouse_tenant_code UNIQUE (restaurant_id, code),
    CONSTRAINT uq_warehouse_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT ck_warehouse_default_active CHECK (NOT is_default OR (is_active AND deleted_at IS NULL))
);

CREATE UNIQUE INDEX ux_warehouse_default
    ON warehouses(restaurant_id)
    WHERE is_default = true AND deleted_at IS NULL;

CREATE INDEX ix_warehouse_list
    ON warehouses(restaurant_id, is_active, name, id)
    WHERE deleted_at IS NULL;

CREATE TABLE inventory_balances (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    warehouse_id UUID NOT NULL,
    item_id UUID NOT NULL,
    quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
    min_quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inventory_balance_warehouse_item UNIQUE (warehouse_id, item_id),
    CONSTRAINT ck_inventory_balance_min_quantity CHECK (min_quantity >= 0),
    CONSTRAINT fk_inventory_balance_warehouse FOREIGN KEY (restaurant_id, warehouse_id)
        REFERENCES warehouses(restaurant_id, id),
    CONSTRAINT fk_inventory_balance_item FOREIGN KEY (restaurant_id, item_id)
        REFERENCES items(restaurant_id, id)
);

CREATE INDEX ix_inventory_balance_tenant_warehouse_item
    ON inventory_balances(restaurant_id, warehouse_id, item_id);

CREATE INDEX ix_inventory_balance_tenant_item
    ON inventory_balances(restaurant_id, item_id);

INSERT INTO permissions (code, module, name)
VALUES
    ('WAREHOUSE_CREATE', 'INVENTORY', 'Create warehouse'),
    ('WAREHOUSE_UPDATE', 'INVENTORY', 'Update warehouse'),
    ('WAREHOUSE_DELETE', 'INVENTORY', 'Delete warehouse'),
    ('INVENTORY_THRESHOLD_UPDATE', 'INVENTORY', 'Update inventory threshold')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL
  AND r.is_system
  AND r.code IN ('OWNER', 'SUPER_ADMIN')
  AND p.code IN (
      'WAREHOUSE_CREATE',
      'WAREHOUSE_UPDATE',
      'WAREHOUSE_DELETE',
      'INVENTORY_THRESHOLD_UPDATE'
  )
ON CONFLICT (role_id, permission_id) DO NOTHING;
