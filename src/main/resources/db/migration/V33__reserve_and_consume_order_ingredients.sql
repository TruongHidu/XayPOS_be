-- Physical stock keeps its original meaning. Reservations are maintained by locked,
-- synchronous commands in the same transaction as the owning ORDER mutation.
ALTER TABLE inventory_balances
    ADD COLUMN reserved_quantity NUMERIC(14,3) NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_inventory_balance_reserved_non_negative CHECK (reserved_quantity >= 0),
    ADD CONSTRAINT ck_inventory_balance_reserved_physical CHECK (reserved_quantity <= quantity);

ALTER TABLE order_items ADD CONSTRAINT uq_order_inventory_line_source UNIQUE (restaurant_id, order_id, id);
ALTER TABLE order_item_recipe_snapshots
    ADD CONSTRAINT uq_order_inventory_snapshot_source UNIQUE (restaurant_id, id, order_item_id);
ALTER TABLE order_item_recipe_usages
    ADD CONSTRAINT uq_inventory_reservation_usage_source UNIQUE (restaurant_id, id, recipe_snapshot_id, ingredient_id);

CREATE TABLE order_item_inventory_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    order_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    recipe_snapshot_id UUID,
    status VARCHAR(20) NOT NULL,
    skip_reason VARCHAR(40),
    warehouse_id UUID,
    order_quantity_snapshot NUMERIC(12,3) NOT NULL CHECK (order_quantity_snapshot > 0),
    order_unit_snapshot VARCHAR(30) NOT NULL CHECK (btrim(order_unit_snapshot) <> ''),
    reserved_at TIMESTAMPTZ,
    consumed_at TIMESTAMPTZ,
    released_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    creation_transaction_id BIGINT NOT NULL DEFAULT txid_current(),
    CONSTRAINT uq_order_inventory_item UNIQUE (order_item_id),
    CONSTRAINT uq_order_inventory_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_order_inventory_reservation_source UNIQUE (restaurant_id, id, recipe_snapshot_id, warehouse_id),
    CONSTRAINT fk_order_inventory_line FOREIGN KEY (restaurant_id, order_id, order_item_id)
        REFERENCES order_items(restaurant_id, order_id, id),
    CONSTRAINT fk_order_inventory_snapshot FOREIGN KEY (restaurant_id, recipe_snapshot_id, order_item_id)
        REFERENCES order_item_recipe_snapshots(restaurant_id, id, order_item_id),
    CONSTRAINT fk_order_inventory_warehouse FOREIGN KEY (restaurant_id, warehouse_id)
        REFERENCES warehouses(restaurant_id, id),
    CONSTRAINT ck_order_inventory_status CHECK (status IN ('RESERVED','CONSUMED','RELEASED','SKIPPED')),
    CONSTRAINT ck_order_inventory_skip_reason CHECK (skip_reason IS NULL OR skip_reason IN
        ('INVENTORY_FEATURE_DISABLED','SNAPSHOT_NO_RECIPE','SNAPSHOT_FEATURE_DISABLED',
         'NO_TRACKED_INGREDIENTS','LEGACY_UNAVAILABLE')),
    CONSTRAINT ck_order_inventory_snapshot_required CHECK (recipe_snapshot_id IS NOT NULL
        OR (status = 'SKIPPED' AND skip_reason = 'LEGACY_UNAVAILABLE')),
    CONSTRAINT ck_order_inventory_state CHECK (
        (status = 'SKIPPED' AND skip_reason IS NOT NULL AND warehouse_id IS NULL
            AND reserved_at IS NULL AND consumed_at IS NULL AND released_at IS NULL)
        OR
        (status = 'RESERVED' AND skip_reason IS NULL AND warehouse_id IS NOT NULL
            AND reserved_at IS NOT NULL AND consumed_at IS NULL AND released_at IS NULL)
        OR
        (status = 'CONSUMED' AND skip_reason IS NULL AND warehouse_id IS NOT NULL
            AND reserved_at IS NOT NULL AND consumed_at IS NOT NULL AND consumed_at >= reserved_at AND released_at IS NULL)
        OR
        (status = 'RELEASED' AND skip_reason IS NULL AND warehouse_id IS NOT NULL
            AND reserved_at IS NOT NULL AND released_at IS NOT NULL AND released_at >= reserved_at AND consumed_at IS NULL)
    )
);
CREATE INDEX ix_order_inventory_order ON order_item_inventory_allocations(restaurant_id, order_id, order_item_id);
CREATE INDEX ix_order_inventory_reserved_warehouse ON order_item_inventory_allocations(restaurant_id, warehouse_id, id)
    WHERE status = 'RESERVED';

CREATE TABLE inventory_reservations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    allocation_id UUID NOT NULL,
    recipe_snapshot_id UUID NOT NULL,
    recipe_usage_id UUID NOT NULL,
    warehouse_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    quantity NUMERIC(14,3) NOT NULL,
    ingredient_sku_snapshot VARCHAR(80),
    ingredient_name_snapshot VARCHAR(150) NOT NULL,
    ingredient_unit_snapshot VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inventory_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT ck_inventory_reservation_name CHECK (btrim(ingredient_name_snapshot) <> ''),
    CONSTRAINT ck_inventory_reservation_unit CHECK (btrim(ingredient_unit_snapshot) <> ''),
    CONSTRAINT uq_inventory_reservation_usage UNIQUE (recipe_usage_id),
    CONSTRAINT uq_inventory_reservation_ingredient UNIQUE (allocation_id, ingredient_id),
    CONSTRAINT uq_inventory_reservation_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT uq_inventory_reservation_ledger_source UNIQUE (restaurant_id, id, recipe_usage_id, warehouse_id, ingredient_id),
    CONSTRAINT fk_inventory_reservation_allocation FOREIGN KEY (restaurant_id, allocation_id, recipe_snapshot_id, warehouse_id)
        REFERENCES order_item_inventory_allocations(restaurant_id, id, recipe_snapshot_id, warehouse_id),
    CONSTRAINT fk_inventory_reservation_usage FOREIGN KEY (restaurant_id, recipe_usage_id, recipe_snapshot_id, ingredient_id)
        REFERENCES order_item_recipe_usages(restaurant_id, id, recipe_snapshot_id, ingredient_id),
    CONSTRAINT fk_inventory_reservation_warehouse FOREIGN KEY (restaurant_id, warehouse_id)
        REFERENCES warehouses(restaurant_id, id),
    CONSTRAINT fk_inventory_reservation_ingredient FOREIGN KEY (restaurant_id, ingredient_id)
        REFERENCES items(restaurant_id, id)
);
CREATE INDEX ix_inventory_reservation_allocation ON inventory_reservations(restaurant_id, allocation_id, ingredient_id);
CREATE INDEX ix_inventory_reservation_balance ON inventory_reservations(restaurant_id, warehouse_id, ingredient_id, allocation_id);
CREATE INDEX ix_inventory_reservation_ingredient ON inventory_reservations(restaurant_id, ingredient_id, allocation_id);

-- ADD DEFAULT preserves old append-only rows without issuing an UPDATE or disabling their trigger.
ALTER TABLE inventory_transactions
    ADD COLUMN source_type VARCHAR(30) NOT NULL DEFAULT 'STOCK_DOCUMENT',
    ADD COLUMN order_id UUID,
    ADD COLUMN order_item_id UUID,
    ADD COLUMN reservation_id UUID,
    ADD COLUMN recipe_usage_id UUID,
    ALTER COLUMN stock_document_id DROP NOT NULL,
    ALTER COLUMN stock_document_item_id DROP NOT NULL,
    ALTER COLUMN unit_cost DROP NOT NULL,
    DROP CONSTRAINT ck_inventory_transaction_type;
ALTER TABLE inventory_transactions
    ADD CONSTRAINT ck_inventory_transaction_type CHECK (transaction_type IN
        ('RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE','RECIPE_USE')),
    ADD CONSTRAINT ck_inventory_transaction_source CHECK (
        (source_type = 'STOCK_DOCUMENT' AND transaction_type IN
            ('RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE')
            AND stock_document_id IS NOT NULL AND stock_document_item_id IS NOT NULL
            AND order_id IS NULL AND order_item_id IS NULL AND reservation_id IS NULL AND recipe_usage_id IS NULL
            AND unit_cost IS NOT NULL AND unit_cost >= 0)
        OR
        (source_type = 'ORDER_ITEM' AND transaction_type = 'RECIPE_USE'
            AND stock_document_id IS NULL AND stock_document_item_id IS NULL
            AND order_id IS NOT NULL AND order_item_id IS NOT NULL AND reservation_id IS NOT NULL AND recipe_usage_id IS NOT NULL
            AND quantity_change < 0 AND unit_cost IS NULL)
    ),
    ADD CONSTRAINT uq_recipe_use_reservation UNIQUE (reservation_id),
    ADD CONSTRAINT fk_inventory_recipe_use_line FOREIGN KEY (restaurant_id, order_id, order_item_id)
        REFERENCES order_items(restaurant_id, order_id, id),
    ADD CONSTRAINT fk_inventory_recipe_use_reservation
        FOREIGN KEY (restaurant_id, reservation_id, recipe_usage_id, warehouse_id, item_id)
        REFERENCES inventory_reservations(restaurant_id, id, recipe_usage_id, warehouse_id, ingredient_id);
CREATE INDEX ix_inventory_transaction_order ON inventory_transactions(restaurant_id, order_id, created_at DESC, id)
    WHERE order_id IS NOT NULL;
CREATE INDEX ix_inventory_transaction_order_item ON inventory_transactions(restaurant_id, order_item_id, created_at DESC, id)
    WHERE order_item_id IS NOT NULL;

-- Never reserve or consume history, even when Phase 4 had already captured its recipe.
-- A missing historic snapshot is represented by the durable legacy decision itself.
INSERT INTO order_item_inventory_allocations (restaurant_id, order_id, order_item_id, recipe_snapshot_id,
    status, skip_reason, order_quantity_snapshot, order_unit_snapshot, created_at, updated_at)
SELECT oi.restaurant_id, oi.order_id, oi.id, s.id, 'SKIPPED', 'LEGACY_UNAVAILABLE', oi.quantity, oi.unit,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM order_items oi
LEFT JOIN order_item_recipe_snapshots s ON s.restaurant_id = oi.restaurant_id AND s.order_item_id = oi.id
WHERE oi.sent_to_kitchen_at IS NOT NULL;

CREATE FUNCTION guard_order_inventory_allocation() RETURNS trigger AS $$
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
                OR (NEW.status = 'RESERVED' AND snapshot.snapshot_status <> 'CAPTURED') THEN
            RAISE EXCEPTION 'ck_order_inventory_identity: Inventory allocation differs from its immutable recipe snapshot'
                USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_identity';
        END IF;
        RETURN NEW;
    END IF;
    IF ROW(NEW.id,NEW.restaurant_id,NEW.order_id,NEW.order_item_id,NEW.recipe_snapshot_id,NEW.skip_reason,
           NEW.warehouse_id,NEW.order_quantity_snapshot,NEW.order_unit_snapshot,NEW.reserved_at,NEW.created_at,NEW.creation_transaction_id)
        IS DISTINCT FROM
       ROW(OLD.id,OLD.restaurant_id,OLD.order_id,OLD.order_item_id,OLD.recipe_snapshot_id,OLD.skip_reason,
           OLD.warehouse_id,OLD.order_quantity_snapshot,OLD.order_unit_snapshot,OLD.reserved_at,OLD.created_at,OLD.creation_transaction_id) THEN
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
CREATE TRIGGER order_inventory_allocation_guard BEFORE INSERT OR UPDATE OR DELETE ON order_item_inventory_allocations
    FOR EACH ROW EXECUTE FUNCTION guard_order_inventory_allocation();

CREATE FUNCTION guard_inventory_reservation() RETURNS trigger AS $$
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
DECLARE usage order_item_recipe_usages%ROWTYPE;
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
CREATE TRIGGER inventory_reservation_guard BEFORE INSERT OR UPDATE OR DELETE ON inventory_reservations
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_reservation();

CREATE FUNCTION guard_inventory_recipe_use() RETURNS trigger AS $$
DECLARE reservation inventory_reservations%ROWTYPE;
DECLARE allocation order_item_inventory_allocations%ROWTYPE;
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
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER inventory_recipe_use_guard BEFORE INSERT ON inventory_transactions
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_recipe_use();

-- One deferred check per header insert/transition verifies the complete immutable
-- detail set and terminal ledger. It never scans or locks unrelated balances.
CREATE FUNCTION validate_order_inventory_history() RETURNS trigger AS $$
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
    SELECT count(*) INTO tracked_count FROM order_item_recipe_usages
        WHERE restaurant_id = allocation.restaurant_id AND recipe_snapshot_id = allocation.recipe_snapshot_id
          AND track_inventory_snapshot = true;
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
CREATE CONSTRAINT TRIGGER order_inventory_allocation_history
    AFTER INSERT OR UPDATE ON order_item_inventory_allocations DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_order_inventory_history();

CREATE FUNCTION validate_inventory_recipe_use_commit() RETURNS trigger AS $$
DECLARE allocation_status VARCHAR(20);
BEGIN
    IF NEW.source_type <> 'ORDER_ITEM' THEN RETURN NULL; END IF;
    SELECT a.status INTO allocation_status FROM inventory_reservations r
        JOIN order_item_inventory_allocations a ON a.restaurant_id=r.restaurant_id AND a.id=r.allocation_id
        WHERE r.restaurant_id=NEW.restaurant_id AND r.id=NEW.reservation_id;
    IF allocation_status IS DISTINCT FROM 'CONSUMED' THEN
        RAISE EXCEPTION 'ck_order_inventory_history: Recipe use requires a consumed allocation at commit'
            USING ERRCODE = '23514', CONSTRAINT = 'ck_order_inventory_history';
    END IF;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER inventory_recipe_use_commit
    AFTER INSERT ON inventory_transactions DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION validate_inventory_recipe_use_commit();
