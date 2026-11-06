-- Historical reference costs are captured with a fresh acceptance decision. Existing
-- immutable snapshots and append-only ledger entries are deliberately not repriced.
ALTER TABLE order_item_recipe_snapshots
    ADD COLUMN cost_snapshot_version SMALLINT,
    ADD COLUMN cost_status VARCHAR(30),
    ADD COLUMN cost_unavailable_reason VARCHAR(60),
    ADD COLUMN unit_cost_snapshot NUMERIC(38,2),
    ADD COLUMN total_cost_snapshot NUMERIC(38,2);

ALTER TABLE order_item_recipe_usages
    ADD COLUMN cost_configured_snapshot BOOLEAN,
    ADD COLUMN unit_cost_snapshot NUMERIC(14,2),
    ADD COLUMN line_cost_snapshot NUMERIC(38,2);

ALTER TABLE order_item_recipe_snapshots
    ADD CONSTRAINT ck_order_reference_cost_snapshot CHECK (
        (cost_snapshot_version IS NULL AND cost_status IS NULL AND cost_unavailable_reason IS NULL
            AND unit_cost_snapshot IS NULL AND total_cost_snapshot IS NULL)
        OR
        (cost_snapshot_version IS NOT NULL AND cost_snapshot_version = 1
            AND cost_status IS NOT NULL AND recipe_enabled_snapshot IS NOT NULL
            AND cost_source_snapshot IS NOT NULL AND (
                (recipe_enabled_snapshot = false AND cost_source_snapshot = 'MANUAL'
                    AND snapshot_status = 'RECIPE_DISABLED' AND (
                        (cost_status = 'COMPLETE' AND cost_unavailable_reason IS NULL
                            AND unit_cost_snapshot IS NOT NULL AND unit_cost_snapshot >= 0
                            AND unit_cost_snapshot <> 'NaN'::numeric
                            AND total_cost_snapshot IS NOT NULL AND total_cost_snapshot >= 0
                            AND total_cost_snapshot <> 'NaN'::numeric
                            AND total_cost_snapshot = round(unit_cost_snapshot * order_quantity_snapshot, 2))
                        OR (cost_status = 'INCOMPLETE' AND cost_unavailable_reason IS NOT NULL
                            AND cost_unavailable_reason = 'MANUAL_COST_NOT_CONFIGURED'
                            AND unit_cost_snapshot IS NULL AND total_cost_snapshot IS NULL)
                    ))
                OR
                (recipe_enabled_snapshot = true AND cost_source_snapshot = 'RECIPE'
                    AND snapshot_status = 'CAPTURED' AND (
                        (cost_status = 'COMPLETE' AND cost_unavailable_reason IS NULL
                            AND unit_cost_snapshot IS NOT NULL AND unit_cost_snapshot >= 0
                            AND unit_cost_snapshot <> 'NaN'::numeric
                            AND total_cost_snapshot IS NOT NULL AND total_cost_snapshot >= 0
                            AND total_cost_snapshot <> 'NaN'::numeric)
                        OR (cost_status = 'INCOMPLETE' AND cost_unavailable_reason IS NOT NULL
                            AND cost_unavailable_reason = 'INGREDIENT_COST_NOT_CONFIGURED'
                            AND unit_cost_snapshot IS NULL AND total_cost_snapshot IS NULL)
                    ))
                OR
                (recipe_enabled_snapshot = true AND cost_source_snapshot = 'RECIPE'
                    AND snapshot_status = 'FEATURE_DISABLED' AND cost_status = 'UNAVAILABLE'
                    AND cost_unavailable_reason IS NOT NULL
                    AND cost_unavailable_reason = 'RECIPE_FEATURE_DISABLED'
                    AND unit_cost_snapshot IS NULL AND total_cost_snapshot IS NULL)
            ))
    );

ALTER TABLE order_item_recipe_usages
    ADD CONSTRAINT ck_order_reference_cost_usage CHECK (
        (cost_configured_snapshot IS NULL AND unit_cost_snapshot IS NULL AND line_cost_snapshot IS NULL)
        OR
        (cost_configured_snapshot IS NOT NULL AND (
            (cost_configured_snapshot = true AND unit_cost_snapshot IS NOT NULL AND unit_cost_snapshot >= 0
                AND unit_cost_snapshot <> 'NaN'::numeric
                AND line_cost_snapshot IS NOT NULL AND line_cost_snapshot >= 0
                AND line_cost_snapshot <> 'NaN'::numeric
                AND line_cost_snapshot = round(required_quantity * unit_cost_snapshot, 2))
            OR (cost_configured_snapshot = false AND unit_cost_snapshot IS NULL AND line_cost_snapshot IS NULL)
        ))
    );

CREATE FUNCTION validate_order_reference_cost_insert() RETURNS trigger AS $$
BEGIN
    -- Keep the explicit maintenance representation for history. The application
    -- never writes it; ordinary fresh decisions must carry monetary version 1.
    IF NEW.snapshot_status = 'LEGACY_UNAVAILABLE' AND NEW.recipe_enabled_snapshot IS NULL
            AND NEW.cost_source_snapshot IS NULL AND NEW.cost_snapshot_version IS NULL
            AND NEW.cost_status IS NULL AND NEW.cost_unavailable_reason IS NULL
            AND NEW.unit_cost_snapshot IS NULL AND NEW.total_cost_snapshot IS NULL THEN
        RETURN NEW;
    END IF;
    IF NEW.cost_snapshot_version IS DISTINCT FROM 1 THEN
        RAISE EXCEPTION 'ck_order_reference_cost_snapshot: Fresh order snapshots require reference cost version 1'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_reference_cost_snapshot';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER order_reference_cost_insert_guard BEFORE INSERT ON order_item_recipe_snapshots
    FOR EACH ROW EXECUTE FUNCTION validate_order_reference_cost_insert();

CREATE OR REPLACE FUNCTION validate_order_recipe_usage_insert() RETURNS trigger AS $$
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
    IF (header.cost_snapshot_version = 1 AND NEW.cost_configured_snapshot IS NULL)
            OR (header.cost_snapshot_version IS NULL AND (NEW.cost_configured_snapshot IS NOT NULL
                OR NEW.unit_cost_snapshot IS NOT NULL OR NEW.line_cost_snapshot IS NOT NULL)) THEN
        RAISE EXCEPTION 'ck_order_reference_cost_usage: Recipe usage monetary metadata must match its header version'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_reference_cost_usage';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- The existing deferred header trigger runs after the immutable usage set has
-- been inserted. No header UPDATE is needed to calculate or validate its total.
CREATE OR REPLACE FUNCTION require_order_recipe_snapshot_usages() RETURNS trigger AS $$
DECLARE usage_count BIGINT;
DECLARE configured_count BIGINT;
DECLARE unknown_count BIGINT;
DECLARE total_cost NUMERIC;
BEGIN
    IF NEW.snapshot_status <> 'CAPTURED' THEN RETURN NULL; END IF;
    SELECT count(*), count(*) FILTER (WHERE cost_configured_snapshot = true),
           count(*) FILTER (WHERE cost_configured_snapshot = false), sum(line_cost_snapshot)
        INTO usage_count, configured_count, unknown_count, total_cost
        FROM order_item_recipe_usages
        WHERE restaurant_id = NEW.restaurant_id AND recipe_snapshot_id = NEW.id;
    IF usage_count = 0 THEN
        RAISE EXCEPTION 'Captured recipe snapshot requires ingredient usages' USING ERRCODE = '23514';
    END IF;
    IF NEW.cost_snapshot_version = 1 AND (
            (NEW.cost_status = 'COMPLETE' AND (configured_count <> usage_count
                OR NEW.total_cost_snapshot IS DISTINCT FROM total_cost
                OR NEW.unit_cost_snapshot IS DISTINCT FROM round(total_cost / NEW.order_quantity_snapshot, 2)))
            OR (NEW.cost_status = 'INCOMPLETE' AND unknown_count = 0)) THEN
        RAISE EXCEPTION 'ck_order_reference_cost_aggregate: Recipe reference cost must match its entire immutable usage set'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_reference_cost_aggregate';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

-- Preserve the stock-document branch and every order/reservation identity rule.
ALTER TABLE inventory_transactions DROP CONSTRAINT ck_inventory_transaction_source;
ALTER TABLE inventory_transactions ADD CONSTRAINT ck_inventory_transaction_source CHECK (
    (source_type = 'STOCK_DOCUMENT' AND transaction_type IN
        ('RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE')
        AND stock_document_id IS NOT NULL AND stock_document_item_id IS NOT NULL
        AND order_id IS NULL AND order_item_id IS NULL AND reservation_id IS NULL AND recipe_usage_id IS NULL
        AND unit_cost IS NOT NULL AND unit_cost >= 0)
    OR
    (source_type = 'ORDER_ITEM' AND transaction_type = 'RECIPE_USE'
        AND stock_document_id IS NULL AND stock_document_item_id IS NULL
        AND order_id IS NOT NULL AND order_item_id IS NOT NULL AND reservation_id IS NOT NULL AND recipe_usage_id IS NOT NULL
        AND quantity_change < 0 AND (unit_cost IS NULL OR unit_cost >= 0))
);

CREATE OR REPLACE FUNCTION guard_inventory_recipe_use() RETURNS trigger AS $$
DECLARE reservation inventory_reservations%ROWTYPE;
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
DECLARE usage order_item_recipe_usages%ROWTYPE;
DECLARE snapshot_version SMALLINT;
DECLARE expected_unit_cost NUMERIC(14,2);
BEGIN
    IF NEW.source_type <> 'ORDER_ITEM' THEN RETURN NEW; END IF;
    SELECT * INTO reservation FROM inventory_reservations
        WHERE restaurant_id = NEW.restaurant_id AND id = NEW.reservation_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'fk_inventory_recipe_use_reservation: Recipe use requires a reservation'
            USING ERRCODE = '23503', CONSTRAINT = 'fk_inventory_recipe_use_reservation';
    END IF;
    SELECT * INTO allocation FROM order_item_inventory_allocations
        WHERE restaurant_id = reservation.restaurant_id AND id = reservation.allocation_id;
    IF allocation.status NOT IN ('RESERVED','CONSUMED')
            OR allocation.order_id IS DISTINCT FROM NEW.order_id OR allocation.order_item_id IS DISTINCT FROM NEW.order_item_id
            OR reservation.recipe_usage_id IS DISTINCT FROM NEW.recipe_usage_id
            OR reservation.warehouse_id IS DISTINCT FROM NEW.warehouse_id
            OR reservation.ingredient_id IS DISTINCT FROM NEW.item_id
            OR reservation.quantity <> -NEW.quantity_change
            OR reservation.ingredient_unit_snapshot IS DISTINCT FROM NEW.unit THEN
        RAISE EXCEPTION 'ck_inventory_transaction_source: Recipe use must exactly match its reservation'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_transaction_source';
    END IF;
    SELECT * INTO usage FROM order_item_recipe_usages
        WHERE restaurant_id = NEW.restaurant_id AND id = NEW.recipe_usage_id;
    IF NOT FOUND OR usage.recipe_snapshot_id IS DISTINCT FROM reservation.recipe_snapshot_id
            OR usage.ingredient_id IS DISTINCT FROM reservation.ingredient_id
            OR usage.ingredient_unit_snapshot IS DISTINCT FROM reservation.ingredient_unit_snapshot
            OR usage.required_quantity IS DISTINCT FROM reservation.quantity THEN
        RAISE EXCEPTION 'ck_inventory_transaction_source: Recipe use must match its immutable usage'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_transaction_source';
    END IF;
    SELECT cost_snapshot_version INTO snapshot_version FROM order_item_recipe_snapshots
        WHERE restaurant_id = NEW.restaurant_id AND id = usage.recipe_snapshot_id;
    IF snapshot_version = 1 AND usage.cost_configured_snapshot = true THEN
        expected_unit_cost := usage.unit_cost_snapshot;
    ELSE
        expected_unit_cost := NULL;
    END IF;
    IF NEW.unit_cost IS DISTINCT FROM expected_unit_cost THEN
        RAISE EXCEPTION 'ck_inventory_transaction_source: Recipe use unit cost must equal its frozen usage cost'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_transaction_source';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
