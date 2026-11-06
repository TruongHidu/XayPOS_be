CREATE TABLE recipes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    menu_item_id UUID NOT NULL,
    recipe_version INTEGER NOT NULL CHECK (recipe_version > 0),
    yield_quantity NUMERIC(12,3) NOT NULL CHECK (yield_quantity > 0),
    yield_unit VARCHAR(30) NOT NULL CHECK (btrim(yield_unit) <> ''),
    is_active BOOLEAN NOT NULL DEFAULT true,
    lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recipe_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_recipe_business_version UNIQUE (restaurant_id, menu_item_id, recipe_version),
    CONSTRAINT fk_recipe_menu_item FOREIGN KEY (restaurant_id, menu_item_id)
        REFERENCES items(restaurant_id, id)
);

CREATE UNIQUE INDEX ux_recipe_active
    ON recipes(restaurant_id, menu_item_id)
    WHERE is_active = true;

CREATE INDEX ix_recipe_history
    ON recipes(restaurant_id, menu_item_id, recipe_version DESC, id);

CREATE TABLE recipe_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    recipe_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    ingredient_name_snapshot VARCHAR(150) NOT NULL CHECK (btrim(ingredient_name_snapshot) <> ''),
    ingredient_sku_snapshot VARCHAR(80),
    ingredient_unit_snapshot VARCHAR(30) NOT NULL CHECK (btrim(ingredient_unit_snapshot) <> ''),
    quantity NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    waste_percent NUMERIC(5,2) NOT NULL DEFAULT 0 CHECK (waste_percent BETWEEN 0 AND 100),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_recipe_item_ingredient UNIQUE (recipe_id, ingredient_id),
    CONSTRAINT fk_recipe_item_recipe FOREIGN KEY (restaurant_id, recipe_id)
        REFERENCES recipes(restaurant_id, id),
    CONSTRAINT fk_recipe_item_ingredient FOREIGN KEY (restaurant_id, ingredient_id)
        REFERENCES items(restaurant_id, id)
);

CREATE INDEX ix_recipe_item_ingredient
    ON recipe_items(restaurant_id, ingredient_id);

CREATE INDEX ix_recipe_item_recipe
    ON recipe_items(restaurant_id, recipe_id, ingredient_id);

INSERT INTO permissions (code, module, name)
VALUES
    ('RECIPE_VIEW', 'MENU', 'View recipes'),
    ('RECIPE_CREATE', 'MENU', 'Create recipes'),
    ('RECIPE_UPDATE', 'MENU', 'Create recipe versions'),
    ('RECIPE_DELETE', 'MENU', 'Deactivate recipes')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL
  AND r.is_system
  AND r.code IN ('OWNER', 'SUPER_ADMIN')
  AND p.code IN ('RECIPE_VIEW', 'RECIPE_CREATE', 'RECIPE_UPDATE', 'RECIPE_DELETE')
ON CONFLICT (role_id, permission_id) DO NOTHING;
