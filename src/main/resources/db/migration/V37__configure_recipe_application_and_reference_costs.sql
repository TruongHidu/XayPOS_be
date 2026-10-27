-- Classify present catalogue configuration only. Never rewrite accepted order history.
ALTER TABLE items
    ADD COLUMN recipe_enabled BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN cost_configured BOOLEAN NOT NULL DEFAULT false;

UPDATE items SET cost_configured = true WHERE cost_price > 0;
UPDATE items i SET recipe_enabled = true
WHERE i.item_type = 'MENU_ITEM' AND EXISTS (
    SELECT 1 FROM recipes r
    WHERE r.restaurant_id = i.restaurant_id AND r.menu_item_id = i.id AND r.is_active = true
);

ALTER TABLE items
    ADD CONSTRAINT ck_item_recipe_application CHECK (item_type = 'MENU_ITEM' OR recipe_enabled = false),
    ADD CONSTRAINT ck_item_reference_cost_configuration CHECK (cost_configured OR cost_price = 0);

-- Null decision metadata denotes pre-V37 history. Do not guess it from today's menu.
ALTER TABLE order_item_recipe_snapshots
    ADD COLUMN recipe_enabled_snapshot BOOLEAN,
    ADD COLUMN cost_source_snapshot VARCHAR(20),
    DROP CONSTRAINT order_item_recipe_snapshots_snapshot_status_check;
ALTER TABLE order_item_recipe_snapshots
    ADD CONSTRAINT ck_order_recipe_snapshot_status CHECK (snapshot_status IN
        ('CAPTURED','RECIPE_DISABLED','NO_RECIPE','FEATURE_DISABLED','LEGACY_UNAVAILABLE')),
    ADD CONSTRAINT ck_order_recipe_application_decision CHECK (
        (recipe_enabled_snapshot IS NULL AND cost_source_snapshot IS NULL)
        OR (recipe_enabled_snapshot IS NOT NULL AND cost_source_snapshot IS NOT NULL AND (
            (recipe_enabled_snapshot = false AND cost_source_snapshot = 'MANUAL' AND snapshot_status = 'RECIPE_DISABLED')
            OR (recipe_enabled_snapshot = true AND cost_source_snapshot = 'RECIPE'
                AND snapshot_status IN ('CAPTURED','FEATURE_DISABLED','NO_RECIPE'))
        ))
    );

CREATE FUNCTION validate_order_recipe_application_insert() RETURNS trigger AS $$
BEGIN
    -- Preserve the explicit legacy representation used by migration/maintenance fixtures.
    IF NEW.snapshot_status = 'LEGACY_UNAVAILABLE'
            AND NEW.recipe_enabled_snapshot IS NULL AND NEW.cost_source_snapshot IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.recipe_enabled_snapshot IS NULL OR NEW.cost_source_snapshot IS NULL THEN
        RAISE EXCEPTION 'New recipe decisions require application/source metadata'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_recipe_application_decision';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER order_recipe_application_insert_guard BEFORE INSERT ON order_item_recipe_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_order_recipe_application_insert();

ALTER TABLE order_item_inventory_allocations DROP CONSTRAINT ck_order_inventory_skip_reason;
ALTER TABLE order_item_inventory_allocations ADD CONSTRAINT ck_order_inventory_skip_reason CHECK (
    skip_reason IS NULL OR skip_reason IN ('SNAPSHOT_RECIPE_DISABLED','INVENTORY_FEATURE_DISABLED',
        'SNAPSHOT_NO_RECIPE','SNAPSHOT_FEATURE_DISABLED','NO_TRACKED_INGREDIENTS','LEGACY_UNAVAILABLE')
);

-- V32/V33 immutable triggers, reservations, deferred history and the legacy null
-- unit_cost rule for RECIPE_USE are intentionally preserved in this phase.
