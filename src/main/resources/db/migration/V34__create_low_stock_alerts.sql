CREATE TABLE low_stock_alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    warehouse_id UUID NOT NULL,
    item_id UUID NOT NULL,
    warehouse_code VARCHAR(50) NOT NULL,
    warehouse_name VARCHAR(100) NOT NULL,
    ingredient_sku VARCHAR(80),
    ingredient_name VARCHAR(150) NOT NULL,
    unit VARCHAR(30) NOT NULL,
    quantity_at_trigger NUMERIC(14,3) NOT NULL,
    threshold_quantity NUMERIC(14,3) NOT NULL,
    status VARCHAR(20) NOT NULL,
    triggered_at TIMESTAMPTZ NOT NULL,
    acknowledged_by UUID REFERENCES users(id),
    acknowledged_at TIMESTAMPTZ,
    resolved_at TIMESTAMPTZ,
    resolution_reason VARCHAR(40),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_low_stock_alert_warehouse FOREIGN KEY (restaurant_id,warehouse_id)
        REFERENCES warehouses(restaurant_id,id),
    CONSTRAINT fk_low_stock_alert_item FOREIGN KEY (restaurant_id,item_id)
        REFERENCES items(restaurant_id,id),
    CONSTRAINT ck_low_stock_alert_quantity CHECK (quantity_at_trigger >= 0 AND threshold_quantity > 0
        AND quantity_at_trigger <= threshold_quantity),
    CONSTRAINT ck_low_stock_alert_version CHECK (version >= 0),
    CONSTRAINT ck_low_stock_alert_ack_pair CHECK ((acknowledged_by IS NULL) = (acknowledged_at IS NULL)),
    CONSTRAINT ck_low_stock_alert_state CHECK (
        (status='OPEN' AND acknowledged_at IS NULL AND resolved_at IS NULL AND resolution_reason IS NULL)
        OR (status='ACKNOWLEDGED' AND acknowledged_at IS NOT NULL AND resolved_at IS NULL AND resolution_reason IS NULL)
        OR (status='RESOLVED' AND resolved_at IS NOT NULL AND resolution_reason IS NOT NULL AND resolution_reason IN (
            'STOCK_RECOVERED','THRESHOLD_DISABLED','WAREHOUSE_INACTIVE','WAREHOUSE_DELETED',
            'INGREDIENT_UNTRACKED','INGREDIENT_DELETED','INGREDIENT_UNIT_CHANGED'))),
    CONSTRAINT ck_low_stock_alert_time CHECK (created_at=triggered_at AND updated_at>=triggered_at
        AND (acknowledged_at IS NULL OR acknowledged_at>=triggered_at)
        AND (resolved_at IS NULL OR (resolved_at>=triggered_at
            AND (acknowledged_at IS NULL OR resolved_at>=acknowledged_at))))
);

CREATE UNIQUE INDEX ux_low_stock_alert_unresolved ON low_stock_alerts(restaurant_id,warehouse_id,item_id)
    WHERE status IN ('OPEN','ACKNOWLEDGED');
CREATE INDEX ix_low_stock_alert_status_time ON low_stock_alerts(restaurant_id,status,triggered_at DESC,id);
CREATE INDEX ix_low_stock_alert_warehouse_time ON low_stock_alerts(restaurant_id,warehouse_id,triggered_at DESC,id);
CREATE INDEX ix_low_stock_alert_item_time ON low_stock_alerts(restaurant_id,item_id,triggered_at DESC,id);

-- Baseline detection at upgrade time; never invent historic breach times or user actions.
INSERT INTO low_stock_alerts (restaurant_id,warehouse_id,item_id,warehouse_code,warehouse_name,
    ingredient_sku,ingredient_name,unit,quantity_at_trigger,threshold_quantity,status,
    triggered_at,created_at,updated_at)
SELECT b.restaurant_id,b.warehouse_id,b.item_id,w.code,w.name,i.sku,i.name,i.base_unit,
    b.quantity,b.min_quantity,'OPEN',transaction_timestamp(),transaction_timestamp(),transaction_timestamp()
FROM inventory_balances b
JOIN warehouses w ON w.restaurant_id=b.restaurant_id AND w.id=b.warehouse_id
JOIN items i ON i.restaurant_id=b.restaurant_id AND i.id=b.item_id
WHERE w.is_active AND w.deleted_at IS NULL AND i.item_type='INGREDIENT'
    AND i.track_inventory AND i.deleted_at IS NULL AND b.min_quantity>0 AND b.quantity<=b.min_quantity;

CREATE FUNCTION guard_low_stock_alert_history() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Low stock alert history cannot be deleted'
            USING ERRCODE='23514', CONSTRAINT='ck_low_stock_alert_identity';
    END IF;
    IF ROW(NEW.id,NEW.restaurant_id,NEW.warehouse_id,NEW.item_id,NEW.warehouse_code,NEW.warehouse_name,
        NEW.ingredient_sku,NEW.ingredient_name,NEW.unit,NEW.quantity_at_trigger,NEW.threshold_quantity,
        NEW.triggered_at,NEW.created_at) IS DISTINCT FROM
       ROW(OLD.id,OLD.restaurant_id,OLD.warehouse_id,OLD.item_id,OLD.warehouse_code,OLD.warehouse_name,
        OLD.ingredient_sku,OLD.ingredient_name,OLD.unit,OLD.quantity_at_trigger,OLD.threshold_quantity,
        OLD.triggered_at,OLD.created_at) THEN
        RAISE EXCEPTION 'Low stock alert trigger snapshot is immutable'
            USING ERRCODE='23514', CONSTRAINT='ck_low_stock_alert_identity';
    END IF;
    IF NOT ((OLD.status='OPEN' AND NEW.status IN ('ACKNOWLEDGED','RESOLVED'))
        OR (OLD.status='ACKNOWLEDGED' AND NEW.status='RESOLVED'))
        OR (OLD.acknowledged_at IS NOT NULL AND ROW(NEW.acknowledged_at,NEW.acknowledged_by)
            IS DISTINCT FROM ROW(OLD.acknowledged_at,OLD.acknowledged_by))
        OR (NEW.status='RESOLVED' AND ROW(NEW.acknowledged_at,NEW.acknowledged_by)
            IS DISTINCT FROM ROW(OLD.acknowledged_at,OLD.acknowledged_by)) THEN
        RAISE EXCEPTION 'Invalid low stock alert transition'
            USING ERRCODE='23514', CONSTRAINT='ck_low_stock_alert_transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER low_stock_alert_history_guard BEFORE UPDATE OR DELETE ON low_stock_alerts
    FOR EACH ROW EXECUTE FUNCTION guard_low_stock_alert_history();

INSERT INTO permissions(code,module,name)
VALUES ('INVENTORY_ALERT_ACKNOWLEDGE','INVENTORY','Acknowledge low stock alerts')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system AND r.code IN ('OWNER','SUPER_ADMIN')
    AND p.code='INVENTORY_ALERT_ACKNOWLEDGE'
ON CONFLICT (role_id,permission_id) DO NOTHING;
