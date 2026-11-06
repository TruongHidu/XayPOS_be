DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM stock_documents WHERE upper(btrim(idempotency_key)) LIKE 'SYS\_STOCK\_AUDIT:%' ESCAPE '\') THEN
        RAISE EXCEPTION 'V35: manual stock document uses reserved SYS_STOCK_AUDIT: namespace; review data before migration';
    END IF;
END $$;

CREATE TABLE stock_audits (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    warehouse_id UUID NOT NULL,
    audit_code VARCHAR(50) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    note TEXT,
    idempotency_key VARCHAR(100) NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    started_by UUID REFERENCES users(id),
    confirmed_by UUID REFERENCES users(id),
    cancelled_by UUID REFERENCES users(id),
    started_at TIMESTAMPTZ,
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    count_revision BIGINT NOT NULL DEFAULT 0 CHECK (count_revision>=0),
    snapshot_transaction_id BIGINT,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_stock_audit_tenant_id UNIQUE(restaurant_id,id),
    CONSTRAINT uq_stock_audit_code UNIQUE(restaurant_id,audit_code),
    CONSTRAINT uq_stock_audit_idempotency UNIQUE(restaurant_id,idempotency_key),
    CONSTRAINT fk_stock_audit_warehouse FOREIGN KEY(restaurant_id,warehouse_id) REFERENCES warehouses(restaurant_id,id),
    CONSTRAINT ck_stock_audit_state CHECK (
        (status='DRAFT' AND started_at IS NULL AND confirmed_at IS NULL AND cancelled_at IS NULL)
        OR (status='COUNTING' AND started_at IS NOT NULL AND confirmed_at IS NULL AND cancelled_at IS NULL)
        OR (status='CONFIRMED' AND started_at IS NOT NULL AND confirmed_at IS NOT NULL AND cancelled_at IS NULL)
        OR (status='CANCELLED' AND confirmed_at IS NULL AND cancelled_at IS NOT NULL)),
    CONSTRAINT ck_stock_audit_actors CHECK ((started_by IS NULL)=(started_at IS NULL)
        AND (confirmed_by IS NULL)=(confirmed_at IS NULL) AND (cancelled_by IS NULL)=(cancelled_at IS NULL)),
    CONSTRAINT ck_stock_audit_time CHECK (updated_at>=created_at AND (started_at IS NULL OR started_at>=created_at)
        AND (confirmed_at IS NULL OR confirmed_at>=started_at) AND (cancelled_at IS NULL OR cancelled_at>=coalesce(started_at,created_at)))
);
CREATE UNIQUE INDEX ux_stock_audit_counting ON stock_audits(restaurant_id,warehouse_id) WHERE status='COUNTING';
CREATE INDEX ix_stock_audit_status_created ON stock_audits(restaurant_id,status,created_at DESC,id);
CREATE INDEX ix_stock_audit_warehouse_created ON stock_audits(restaurant_id,warehouse_id,created_at DESC,id);

CREATE TABLE stock_audit_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    stock_audit_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    ingredient_sku_snapshot VARCHAR(80),
    ingredient_name_snapshot VARCHAR(150) NOT NULL,
    unit_snapshot VARCHAR(30) NOT NULL,
    system_quantity NUMERIC(14,3) NOT NULL CHECK (system_quantity>=0),
    counted_quantity NUMERIC(14,3) CHECK (counted_quantity>=0),
    difference_quantity NUMERIC(14,3) GENERATED ALWAYS AS (counted_quantity-system_quantity) STORED,
    reference_unit_cost_snapshot NUMERIC(14,2) NOT NULL CHECK (reference_unit_cost_snapshot>=0),
    movement_count_snapshot BIGINT NOT NULL CHECK (movement_count_snapshot>=0),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_stock_audit_item UNIQUE(stock_audit_id,ingredient_id),
    CONSTRAINT fk_stock_audit_item_parent FOREIGN KEY(restaurant_id,stock_audit_id) REFERENCES stock_audits(restaurant_id,id),
    CONSTRAINT fk_stock_audit_item_ingredient FOREIGN KEY(restaurant_id,ingredient_id) REFERENCES items(restaurant_id,id)
);
CREATE INDEX ix_stock_audit_item_parent ON stock_audit_items(restaurant_id,stock_audit_id,id);
CREATE INDEX ix_inventory_transaction_audit_movement ON inventory_transactions(restaurant_id,warehouse_id,item_id);

ALTER TABLE stock_documents ADD COLUMN stock_audit_id UUID,
    ADD CONSTRAINT fk_stock_document_stock_audit FOREIGN KEY(restaurant_id,stock_audit_id) REFERENCES stock_audits(restaurant_id,id),
    ADD CONSTRAINT ck_stock_document_audit_type CHECK (stock_audit_id IS NULL OR document_type='ADJUSTMENT');
CREATE UNIQUE INDEX ux_stock_document_stock_audit ON stock_documents(restaurant_id,stock_audit_id) WHERE stock_audit_id IS NOT NULL;

CREATE FUNCTION guard_stock_audit_history() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Stock audit history cannot be deleted' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_history';
    END IF;
    IF ROW(NEW.id,NEW.restaurant_id,NEW.warehouse_id,NEW.audit_code,NEW.idempotency_key,NEW.creation_request_hash,NEW.created_by,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.restaurant_id,OLD.warehouse_id,OLD.audit_code,OLD.idempotency_key,OLD.creation_request_hash,OLD.created_by,OLD.created_at)
        OR OLD.status IN ('CONFIRMED','CANCELLED') OR NOT (
            (OLD.status='DRAFT' AND NEW.status IN ('DRAFT','COUNTING','CANCELLED'))
            OR (OLD.status='COUNTING' AND NEW.status IN ('COUNTING','CONFIRMED','CANCELLED'))) THEN
        RAISE EXCEPTION 'Invalid stock audit mutation' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_history';
    END IF;
    IF OLD.status='DRAFT' AND NEW.status='COUNTING' THEN NEW.snapshot_transaction_id=txid_current();
    ELSIF ROW(NEW.started_at,NEW.started_by,NEW.snapshot_transaction_id) IS DISTINCT FROM ROW(OLD.started_at,OLD.started_by,OLD.snapshot_transaction_id) THEN
        RAISE EXCEPTION 'Stock audit snapshot identity is immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_history';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER stock_audit_history_guard BEFORE UPDATE OR DELETE ON stock_audits FOR EACH ROW EXECUTE FUNCTION guard_stock_audit_history();

CREATE FUNCTION guard_stock_audit_item() RETURNS trigger AS $$
DECLARE parent stock_audits%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Stock audit items cannot be deleted' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_item_history'; END IF;
    SELECT * INTO parent FROM stock_audits WHERE restaurant_id=NEW.restaurant_id AND id=NEW.stock_audit_id;
    IF parent.status IS DISTINCT FROM 'COUNTING' OR (TG_OP='INSERT' AND parent.snapshot_transaction_id IS DISTINCT FROM txid_current()) THEN
        RAISE EXCEPTION 'Stock audit scope is frozen; counts require COUNTING' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_item_history';
    END IF;
    IF TG_OP='UPDATE' AND ROW(NEW.id,NEW.restaurant_id,NEW.stock_audit_id,NEW.ingredient_id,NEW.ingredient_sku_snapshot,
        NEW.ingredient_name_snapshot,NEW.unit_snapshot,NEW.system_quantity,NEW.reference_unit_cost_snapshot,NEW.movement_count_snapshot,NEW.created_at)
        IS DISTINCT FROM ROW(OLD.id,OLD.restaurant_id,OLD.stock_audit_id,OLD.ingredient_id,OLD.ingredient_sku_snapshot,
        OLD.ingredient_name_snapshot,OLD.unit_snapshot,OLD.system_quantity,OLD.reference_unit_cost_snapshot,OLD.movement_count_snapshot,OLD.created_at) THEN
        RAISE EXCEPTION 'Stock audit item snapshot is immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_item_history';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER stock_audit_item_history_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_audit_items FOR EACH ROW EXECUTE FUNCTION guard_stock_audit_item();

CREATE FUNCTION guard_stock_audit_document() RETURNS trigger AS $$
BEGIN
    IF TG_OP='DELETE' THEN
        IF OLD.stock_audit_id IS NOT NULL THEN RAISE EXCEPTION 'Generated stock audit document is immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_document_history'; END IF;
        RETURN OLD;
    END IF;
    IF NEW.stock_audit_id IS DISTINCT FROM OLD.stock_audit_id OR (OLD.stock_audit_id IS NOT NULL AND OLD.status='CONFIRMED') THEN
        RAISE EXCEPTION 'Generated stock audit source is immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_document_history';
    END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER stock_audit_document_guard BEFORE UPDATE OR DELETE ON stock_documents FOR EACH ROW EXECUTE FUNCTION guard_stock_audit_document();

CREATE FUNCTION guard_stock_audit_document_item() RETURNS trigger AS $$
DECLARE parent stock_documents%ROWTYPE;
BEGIN
    IF TG_OP='UPDATE' AND EXISTS(SELECT 1 FROM stock_documents WHERE id=OLD.stock_document_id AND stock_audit_id IS NOT NULL AND status='CONFIRMED') THEN
        RAISE EXCEPTION 'Generated stock audit document items are immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_document_history';
    END IF;
    SELECT * INTO parent FROM stock_documents WHERE id=CASE WHEN TG_OP='DELETE' THEN OLD.stock_document_id ELSE NEW.stock_document_id END;
    IF parent.stock_audit_id IS NOT NULL AND parent.status='CONFIRMED' THEN
        RAISE EXCEPTION 'Generated stock audit document items are immutable' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_document_history';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER stock_audit_document_item_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_document_items FOR EACH ROW EXECUTE FUNCTION guard_stock_audit_document_item();

CREATE FUNCTION validate_stock_audit_commit() RETURNS trigger AS $$
DECLARE parent stock_audits%ROWTYPE; doc stock_documents%ROWTYPE; audit_id UUID; line_count BIGINT; changed_count BIGINT;
BEGIN
    IF TG_TABLE_NAME='stock_documents' THEN audit_id=NEW.stock_audit_id; ELSE audit_id=NEW.id; END IF;
    IF audit_id IS NULL THEN RETURN NULL; END IF;
    SELECT * INTO parent FROM stock_audits WHERE id=audit_id;
    SELECT count(*),count(*) FILTER(WHERE difference_quantity<>0) INTO line_count,changed_count FROM stock_audit_items WHERE stock_audit_id=audit_id;
    SELECT * INTO doc FROM stock_documents WHERE stock_audit_id=audit_id;
    IF (parent.status='DRAFT' AND line_count<>0) OR (parent.status IN ('COUNTING','CONFIRMED') AND line_count=0)
        OR (parent.status<>'CONFIRMED' AND doc.id IS NOT NULL) THEN
        RAISE EXCEPTION 'Invalid stock audit committed scope' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_commit';
    END IF;
    IF parent.status='CONFIRMED' THEN
        IF EXISTS(SELECT 1 FROM stock_audit_items WHERE stock_audit_id=audit_id AND counted_quantity IS NULL)
            OR (changed_count=0 AND doc.id IS NOT NULL) OR (changed_count>0 AND (doc.id IS NULL OR doc.status<>'CONFIRMED' OR doc.warehouse_id<>parent.warehouse_id)) THEN
            RAISE EXCEPTION 'Confirmed stock audit requires complete counts and matching adjustment' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_commit';
        END IF;
        IF changed_count>0 AND (
            (SELECT count(*) FROM stock_document_items WHERE stock_document_id=doc.id)<>changed_count
            OR (SELECT count(*) FROM inventory_transactions WHERE stock_document_id=doc.id)<>changed_count
            OR EXISTS(SELECT 1 FROM stock_audit_items a LEFT JOIN stock_document_items d ON d.stock_document_id=doc.id AND d.ingredient_id=a.ingredient_id
                WHERE a.stock_audit_id=audit_id AND a.difference_quantity<>0 AND (d.id IS NULL OR d.quantity<>abs(a.difference_quantity)
                    OR d.unit<>a.unit_snapshot OR d.unit_cost<>a.reference_unit_cost_snapshot
                    OR d.adjustment_direction<>CASE WHEN a.difference_quantity>0 THEN 'INCREASE' ELSE 'DECREASE' END))) THEN
            RAISE EXCEPTION 'Stock audit adjustment does not match immutable counts' USING ERRCODE='23514',CONSTRAINT='ck_stock_audit_commit';
        END IF;
    END IF;
    RETURN NULL;
END; $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER stock_audit_commit AFTER INSERT OR UPDATE ON stock_audits DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_stock_audit_commit();
CREATE CONSTRAINT TRIGGER stock_audit_document_commit AFTER INSERT OR UPDATE ON stock_documents DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_stock_audit_commit();

INSERT INTO permissions(code,module,name) VALUES('STOCK_AUDIT_MANAGE','INVENTORY','Manage stock audit sessions') ON CONFLICT(code) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p WHERE r.restaurant_id IS NULL AND r.is_system
    AND r.code IN ('OWNER','SUPER_ADMIN') AND p.code='STOCK_AUDIT_MANAGE' ON CONFLICT(role_id,permission_id) DO NOTHING;
