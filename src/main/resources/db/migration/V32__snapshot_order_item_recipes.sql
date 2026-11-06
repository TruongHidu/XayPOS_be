CREATE TABLE order_item_recipe_snapshots (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    menu_item_id_snapshot UUID,
    snapshot_status VARCHAR(30) NOT NULL CHECK (snapshot_status IN
        ('CAPTURED', 'NO_RECIPE', 'FEATURE_DISABLED', 'LEGACY_UNAVAILABLE')),
    recipe_id UUID,
    recipe_version INTEGER,
    yield_quantity NUMERIC(12,3),
    yield_unit VARCHAR(30),
    order_quantity_snapshot NUMERIC(12,3) NOT NULL CHECK (order_quantity_snapshot > 0),
    order_unit_snapshot VARCHAR(30) NOT NULL CHECK (btrim(order_unit_snapshot) <> ''),
    captured_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    -- Usages may only be inserted while their immutable header is being created.
    creation_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    CONSTRAINT uq_order_recipe_snapshot_line UNIQUE (order_item_id),
    CONSTRAINT uq_order_recipe_snapshot_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_order_recipe_snapshot_line FOREIGN KEY (restaurant_id, order_item_id)
        REFERENCES order_items(restaurant_id, id),
    CONSTRAINT fk_order_recipe_snapshot_menu FOREIGN KEY (restaurant_id, menu_item_id_snapshot)
        REFERENCES items(restaurant_id, id),
    CONSTRAINT fk_order_recipe_snapshot_recipe FOREIGN KEY (restaurant_id, recipe_id)
        REFERENCES recipes(restaurant_id, id),
    CONSTRAINT ck_order_recipe_snapshot_data CHECK (
        (snapshot_status = 'CAPTURED' AND menu_item_id_snapshot IS NOT NULL
            AND recipe_id IS NOT NULL AND recipe_version IS NOT NULL AND recipe_version > 0
            AND yield_quantity IS NOT NULL AND yield_quantity > 0
            AND yield_unit IS NOT NULL AND btrim(yield_unit) <> '' AND captured_at IS NOT NULL)
        OR
        (snapshot_status <> 'CAPTURED' AND recipe_id IS NULL AND recipe_version IS NULL
            AND yield_quantity IS NULL AND yield_unit IS NULL
            AND ((snapshot_status = 'LEGACY_UNAVAILABLE' AND captured_at IS NULL)
                OR (snapshot_status <> 'LEGACY_UNAVAILABLE' AND captured_at IS NOT NULL)))
    )
);
CREATE INDEX ix_order_recipe_snapshot_tenant_line ON order_item_recipe_snapshots(restaurant_id, order_item_id);
CREATE INDEX ix_order_recipe_snapshot_recipe ON order_item_recipe_snapshots(restaurant_id, recipe_id)
    WHERE recipe_id IS NOT NULL;

CREATE TABLE order_item_recipe_usages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    recipe_snapshot_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    ingredient_name_snapshot VARCHAR(150) NOT NULL CHECK (btrim(ingredient_name_snapshot) <> ''),
    ingredient_sku_snapshot VARCHAR(80),
    ingredient_unit_snapshot VARCHAR(30) NOT NULL CHECK (btrim(ingredient_unit_snapshot) <> ''),
    recipe_quantity_snapshot NUMERIC(12,3) NOT NULL CHECK (recipe_quantity_snapshot > 0),
    waste_percent_snapshot NUMERIC(5,2) NOT NULL CHECK (waste_percent_snapshot BETWEEN 0 AND 100),
    track_inventory_snapshot BOOLEAN NOT NULL,
    required_quantity NUMERIC(14,3) NOT NULL CHECK (required_quantity > 0),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_order_recipe_usage_ingredient UNIQUE (recipe_snapshot_id, ingredient_id),
    CONSTRAINT fk_order_recipe_usage_snapshot FOREIGN KEY (restaurant_id, recipe_snapshot_id)
        REFERENCES order_item_recipe_snapshots(restaurant_id, id),
    CONSTRAINT fk_order_recipe_usage_ingredient FOREIGN KEY (restaurant_id, ingredient_id)
        REFERENCES items(restaurant_id, id)
);
CREATE INDEX ix_order_recipe_usage_snapshot ON order_item_recipe_usages(restaurant_id, recipe_snapshot_id, ingredient_id);
CREATE INDEX ix_order_recipe_usage_ingredient ON order_item_recipe_usages(restaurant_id, ingredient_id);

CREATE FUNCTION prevent_order_recipe_snapshot_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Order recipe snapshots and usages are immutable' USING ERRCODE = '23514';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER order_recipe_snapshot_no_mutation BEFORE UPDATE OR DELETE ON order_item_recipe_snapshots
    FOR EACH ROW EXECUTE FUNCTION prevent_order_recipe_snapshot_mutation();
CREATE TRIGGER order_recipe_usage_no_mutation BEFORE UPDATE OR DELETE ON order_item_recipe_usages
    FOR EACH ROW EXECUTE FUNCTION prevent_order_recipe_snapshot_mutation();

CREATE FUNCTION validate_order_recipe_usage_insert() RETURNS trigger AS $$
DECLARE header order_item_recipe_snapshots%ROWTYPE;
BEGIN
    SELECT * INTO header FROM order_item_recipe_snapshots
        WHERE restaurant_id = NEW.restaurant_id AND id = NEW.recipe_snapshot_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Recipe snapshot not found in tenant' USING ERRCODE = '23503';
    END IF;
    IF header.snapshot_status <> 'CAPTURED' OR header.creation_transaction_id <> txid_current() THEN
        RAISE EXCEPTION 'Order recipe snapshot ingredient set is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER order_recipe_usage_insert_guard BEFORE INSERT ON order_item_recipe_usages
    FOR EACH ROW EXECUTE FUNCTION validate_order_recipe_usage_insert();

CREATE FUNCTION require_order_recipe_snapshot_usages() RETURNS trigger AS $$
BEGIN
    IF NEW.snapshot_status = 'CAPTURED' AND NOT EXISTS (
        SELECT 1 FROM order_item_recipe_usages WHERE restaurant_id = NEW.restaurant_id AND recipe_snapshot_id = NEW.id
    ) THEN
        RAISE EXCEPTION 'Captured recipe snapshot requires ingredient usages' USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER order_recipe_snapshot_requires_usages
    AFTER INSERT ON order_item_recipe_snapshots DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION require_order_recipe_snapshot_usages();

-- Never guess historic recipes. Draft and never-submitted cancelled lines remain untouched.
INSERT INTO order_item_recipe_snapshots (restaurant_id, order_item_id, menu_item_id_snapshot,
    snapshot_status, order_quantity_snapshot, order_unit_snapshot, captured_at, created_at)
SELECT restaurant_id, id, item_id, 'LEGACY_UNAVAILABLE', quantity, unit, NULL, CURRENT_TIMESTAMP
FROM order_items WHERE sent_to_kitchen_at IS NOT NULL;
