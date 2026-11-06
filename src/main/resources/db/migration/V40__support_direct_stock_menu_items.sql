-- DIRECT is an additional immutable inventory decision. No historical rows are repriced.
ALTER TABLE items DROP CONSTRAINT ck_menu_inventory;
ALTER TABLE items ADD CONSTRAINT ck_menu_inventory CHECK (NOT(track_inventory AND recipe_enabled));
ALTER TABLE order_item_recipe_snapshots ADD COLUMN direct_stock_snapshot BOOLEAN;
ALTER TABLE order_item_recipe_snapshots ADD CONSTRAINT ck_order_direct_snapshot CHECK
    (direct_stock_snapshot IS DISTINCT FROM true OR (recipe_enabled_snapshot=false AND snapshot_status='RECIPE_DISABLED'));
ALTER TABLE order_item_inventory_allocations ADD COLUMN direct_stock_snapshot BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE inventory_reservations ALTER COLUMN recipe_usage_id DROP NOT NULL;
ALTER TABLE stock_document_items ADD COLUMN item_type_snapshot VARCHAR(20) NOT NULL DEFAULT 'INGREDIENT'
    CHECK(item_type_snapshot IN ('INGREDIENT','MENU_ITEM'));
-- Independent FK: null recipe usage must never bypass reservation ownership.
ALTER TABLE inventory_reservations ADD CONSTRAINT uq_inventory_direct_ledger_source
    UNIQUE(restaurant_id,id,warehouse_id,ingredient_id);
ALTER TABLE inventory_transactions ADD CONSTRAINT fk_inventory_direct_reservation
    FOREIGN KEY(restaurant_id,reservation_id,warehouse_id,item_id)
    REFERENCES inventory_reservations(restaurant_id,id,warehouse_id,ingredient_id);
ALTER TABLE inventory_transactions DROP CONSTRAINT ck_inventory_transaction_type;
ALTER TABLE inventory_transactions ADD CONSTRAINT ck_inventory_transaction_type CHECK(transaction_type IN
    ('RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE','RECIPE_USE','DIRECT_USE'));
ALTER TABLE inventory_transactions DROP CONSTRAINT ck_inventory_transaction_source;
ALTER TABLE inventory_transactions ADD CONSTRAINT ck_inventory_transaction_source CHECK(
    (source_type='STOCK_DOCUMENT' AND transaction_type IN ('RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE')
      AND stock_document_id IS NOT NULL AND stock_document_item_id IS NOT NULL
      AND order_id IS NULL AND order_item_id IS NULL AND reservation_id IS NULL AND recipe_usage_id IS NULL AND unit_cost IS NOT NULL AND unit_cost>=0)
    OR (source_type='ORDER_ITEM' AND stock_document_id IS NULL AND stock_document_item_id IS NULL
      AND order_id IS NOT NULL AND order_item_id IS NOT NULL AND reservation_id IS NOT NULL AND quantity_change<0
      AND (unit_cost IS NULL OR unit_cost>=0)
      AND ((transaction_type='RECIPE_USE' AND recipe_usage_id IS NOT NULL) OR (transaction_type='DIRECT_USE' AND recipe_usage_id IS NULL)))
);

CREATE OR REPLACE FUNCTION guard_order_inventory_allocation() RETURNS trigger AS $$
DECLARE snapshot order_item_recipe_snapshots%ROWTYPE;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'ck_order_inventory_identity: Inventory allocation history cannot be deleted'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_identity';
    END IF;
    IF TG_OP = 'INSERT' THEN
        IF NEW.status NOT IN ('RESERVED','SKIPPED') OR NEW.recipe_snapshot_id IS NULL THEN
            RAISE EXCEPTION 'ck_order_inventory_identity: New inventory allocation requires a snapshot and an initial decision'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_identity';
        END IF;
        SELECT * INTO snapshot FROM order_item_recipe_snapshots
            WHERE restaurant_id = NEW.restaurant_id AND id = NEW.recipe_snapshot_id AND order_item_id = NEW.order_item_id;
        IF NOT FOUND OR snapshot.order_quantity_snapshot IS DISTINCT FROM NEW.order_quantity_snapshot
                OR snapshot.order_unit_snapshot IS DISTINCT FROM NEW.order_unit_snapshot
                OR NEW.direct_stock_snapshot IS DISTINCT FROM coalesce(snapshot.direct_stock_snapshot,false)
                OR (NEW.status = 'RESERVED' AND NOT NEW.direct_stock_snapshot AND snapshot.snapshot_status <> 'CAPTURED')
                OR (NEW.direct_stock_snapshot AND (snapshot.snapshot_status <> 'RECIPE_DISABLED' OR snapshot.recipe_enabled_snapshot IS DISTINCT FROM false)) THEN
            RAISE EXCEPTION 'ck_order_inventory_identity: Inventory allocation differs from its immutable recipe snapshot'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_identity';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.restaurant_id,NEW.order_id,NEW.order_item_id,NEW.recipe_snapshot_id,NEW.skip_reason,
           NEW.warehouse_id,NEW.order_quantity_snapshot,NEW.order_unit_snapshot,NEW.reserved_at,NEW.created_at,NEW.creation_transaction_id,NEW.direct_stock_snapshot)
        IS DISTINCT FROM
       ROW(OLD.id,OLD.restaurant_id,OLD.order_id,OLD.order_item_id,OLD.recipe_snapshot_id,OLD.skip_reason,
           OLD.warehouse_id,OLD.order_quantity_snapshot,OLD.order_unit_snapshot,OLD.reserved_at,OLD.created_at,OLD.creation_transaction_id,OLD.direct_stock_snapshot) THEN
        RAISE EXCEPTION 'ck_order_inventory_identity: Inventory allocation identity is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_identity';
    END IF;
    IF OLD.status <> 'RESERVED' OR NEW.status NOT IN ('CONSUMED','RELEASED') THEN
        RAISE EXCEPTION 'ck_order_inventory_transition: Invalid inventory allocation transition'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION guard_inventory_reservation() RETURNS trigger AS $$
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
DECLARE usage order_item_recipe_usages%ROWTYPE;
DECLARE snapshot order_item_recipe_snapshots%ROWTYPE;
DECLARE line order_items%ROWTYPE;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'ck_inventory_reservation_identity: Inventory reservation details are immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_reservation_identity';
    END IF;
    SELECT * INTO allocation FROM order_item_inventory_allocations
        WHERE restaurant_id = NEW.restaurant_id AND id = NEW.allocation_id;
    IF NOT FOUND OR allocation.status <> 'RESERVED' OR allocation.creation_transaction_id <> txid_current()
            OR allocation.recipe_snapshot_id IS DISTINCT FROM NEW.recipe_snapshot_id
            OR allocation.warehouse_id IS DISTINCT FROM NEW.warehouse_id THEN
        RAISE EXCEPTION 'ck_inventory_reservation_identity: Inventory reservation ingredient set is immutable'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_reservation_identity';
    END IF;
    IF allocation.direct_stock_snapshot THEN
        SELECT * INTO snapshot FROM order_item_recipe_snapshots WHERE restaurant_id=NEW.restaurant_id AND id=NEW.recipe_snapshot_id;
        SELECT * INTO line FROM order_items WHERE restaurant_id=NEW.restaurant_id AND id=allocation.order_item_id AND order_id=allocation.order_id;
        IF snapshot.direct_stock_snapshot IS DISTINCT FROM true OR NEW.recipe_usage_id IS NOT NULL
            OR NEW.ingredient_id IS DISTINCT FROM snapshot.menu_item_id_snapshot
            OR NEW.quantity IS DISTINCT FROM snapshot.order_quantity_snapshot
            OR NEW.ingredient_unit_snapshot IS DISTINCT FROM snapshot.order_unit_snapshot
            OR NEW.ingredient_name_snapshot IS DISTINCT FROM line.item_name
            OR NEW.ingredient_sku_snapshot IS NOT NULL THEN
            RAISE EXCEPTION 'DIRECT reservation differs from accepted item' USING ERRCODE='23514',CONSTRAINT='ck_inventory_reservation_identity';
        END IF;
        RETURN NEW;
    END IF;
    SELECT * INTO usage FROM order_item_recipe_usages
        WHERE restaurant_id = NEW.restaurant_id AND id = NEW.recipe_usage_id;
    IF NOT FOUND OR NOT usage.track_inventory_snapshot
            OR usage.recipe_snapshot_id IS DISTINCT FROM NEW.recipe_snapshot_id
            OR usage.ingredient_id IS DISTINCT FROM NEW.ingredient_id
            OR usage.required_quantity IS DISTINCT FROM NEW.quantity
            OR usage.ingredient_sku_snapshot IS DISTINCT FROM NEW.ingredient_sku_snapshot
            OR usage.ingredient_name_snapshot IS DISTINCT FROM NEW.ingredient_name_snapshot
            OR usage.ingredient_unit_snapshot IS DISTINCT FROM NEW.ingredient_unit_snapshot THEN
        RAISE EXCEPTION 'ck_inventory_reservation_identity: Inventory reservation must exactly match its tracked recipe usage'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_inventory_reservation_identity';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION validate_order_inventory_history() RETURNS trigger AS $$
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
DECLARE detail_count BIGINT;
DECLARE tracked_count BIGINT;
DECLARE ledger_count BIGINT;
BEGIN
    SELECT * INTO allocation FROM order_item_inventory_allocations WHERE id = NEW.id;
    SELECT count(*) INTO detail_count FROM inventory_reservations
        WHERE restaurant_id = allocation.restaurant_id AND allocation_id = allocation.id;
    IF allocation.status = 'SKIPPED' THEN
        IF detail_count <> 0 THEN
            RAISE EXCEPTION 'ck_order_inventory_history: Skipped inventory allocation cannot have reservation details'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_history';
        END IF;
        RETURN NULL;
    END IF;
    IF allocation.direct_stock_snapshot THEN tracked_count := 1; ELSE
    SELECT count(*) INTO tracked_count FROM order_item_recipe_usages
        WHERE restaurant_id = allocation.restaurant_id AND recipe_snapshot_id = allocation.recipe_snapshot_id
          AND track_inventory_snapshot = true;
    END IF;
    IF detail_count = 0 OR detail_count <> tracked_count THEN
        RAISE EXCEPTION 'ck_order_inventory_history: Inventory allocation must reserve its entire tracked recipe usage set'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_history';
    END IF;
    SELECT count(*) INTO ledger_count FROM inventory_transactions t
        JOIN inventory_reservations r ON r.restaurant_id=t.restaurant_id AND r.id=t.reservation_id
        WHERE r.restaurant_id = allocation.restaurant_id AND r.allocation_id = allocation.id;
    IF (allocation.status = 'CONSUMED' AND ledger_count <> detail_count)
            OR (allocation.status <> 'CONSUMED' AND ledger_count <> 0) THEN
        RAISE EXCEPTION 'ck_order_inventory_history: Inventory allocation terminal ledger is inconsistent'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_history';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE FUNCTION guard_inventory_recipe_use() RETURNS trigger AS $$
DECLARE reservation inventory_reservations%ROWTYPE;
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
DECLARE usage order_item_recipe_usages%ROWTYPE;
DECLARE snapshot_version SMALLINT;
DECLARE direct_cost order_item_recipe_snapshots%ROWTYPE;
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
    IF allocation.direct_stock_snapshot THEN
        SELECT * INTO direct_cost FROM order_item_recipe_snapshots WHERE restaurant_id=NEW.restaurant_id AND id=allocation.recipe_snapshot_id;
        IF NEW.transaction_type <> 'DIRECT_USE' OR NEW.recipe_usage_id IS NOT NULL
            OR direct_cost.direct_stock_snapshot IS DISTINCT FROM true THEN
            RAISE EXCEPTION 'Invalid DIRECT ledger source' USING ERRCODE='23514',CONSTRAINT='ck_inventory_transaction_source';
        END IF;
        expected_unit_cost := CASE WHEN direct_cost.cost_status='COMPLETE' THEN direct_cost.unit_cost_snapshot ELSE NULL END;
        IF NEW.unit_cost IS DISTINCT FROM expected_unit_cost THEN
            RAISE EXCEPTION 'DIRECT cost differs from frozen item cost' USING ERRCODE='23514',CONSTRAINT='ck_inventory_transaction_source';
        END IF;
        RETURN NEW;
    END IF;
    IF NEW.transaction_type <> 'RECIPE_USE' THEN
        RAISE EXCEPTION 'Invalid RECIPE ledger type' USING ERRCODE='23514',CONSTRAINT='ck_inventory_transaction_source';
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
ALTER TABLE low_stock_alerts ADD COLUMN item_type_snapshot VARCHAR(20) NOT NULL DEFAULT 'INGREDIENT' CHECK(item_type_snapshot IN ('INGREDIENT','MENU_ITEM'));
ALTER TABLE stock_audit_items ADD COLUMN item_type_snapshot VARCHAR(20) NOT NULL DEFAULT 'INGREDIENT' CHECK(item_type_snapshot IN ('INGREDIENT','MENU_ITEM'));

CREATE FUNCTION guard_stock_item_type_snapshot() RETURNS trigger AS $$
BEGIN
    IF NEW.item_type_snapshot IS DISTINCT FROM OLD.item_type_snapshot THEN
        RAISE EXCEPTION 'Stock item type snapshot is immutable' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER stock_document_item_type_immutable BEFORE UPDATE ON stock_document_items FOR EACH ROW EXECUTE FUNCTION guard_stock_item_type_snapshot();
CREATE TRIGGER stock_audit_item_type_immutable BEFORE UPDATE ON stock_audit_items FOR EACH ROW EXECUTE FUNCTION guard_stock_item_type_snapshot();
CREATE TRIGGER low_stock_alert_item_type_immutable BEFORE UPDATE ON low_stock_alerts FOR EACH ROW EXECUTE FUNCTION guard_stock_item_type_snapshot();
