DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM inventory_balances WHERE quantity < 0) THEN
        RAISE EXCEPTION 'V31 cannot add non-negative inventory constraint: negative balances exist';
    END IF;
END $$;

ALTER TABLE inventory_balances
    ADD CONSTRAINT ck_inventory_balance_quantity_non_negative CHECK (quantity >= 0);

CREATE TABLE stock_documents (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    warehouse_id UUID NOT NULL,
    document_code VARCHAR(50) NOT NULL,
    document_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    idempotency_key VARCHAR(100) NOT NULL,
    creation_request_hash VARCHAR(64) NOT NULL,
    note TEXT,
    line_count INTEGER NOT NULL DEFAULT 0 CHECK (line_count >= 0),
    total_quantity NUMERIC(16,3) NOT NULL DEFAULT 0 CHECK (total_quantity >= 0),
    total_cost NUMERIC(18,2) NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
    created_by UUID NOT NULL REFERENCES users(id),
    confirmed_by UUID REFERENCES users(id),
    cancelled_by UUID REFERENCES users(id),
    confirmed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_stock_document_type CHECK (document_type IN ('RECEIPT','ISSUE','ADJUSTMENT')),
    CONSTRAINT ck_stock_document_status CHECK (status IN ('DRAFT','CONFIRMED','CANCELLED')),
    CONSTRAINT uq_stock_document_code UNIQUE (restaurant_id, document_code),
    CONSTRAINT uq_stock_document_idempotency UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT uq_stock_document_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_stock_document_warehouse FOREIGN KEY (restaurant_id, warehouse_id)
        REFERENCES warehouses(restaurant_id, id)
);

CREATE INDEX ix_stock_document_status_created
    ON stock_documents(restaurant_id, status, created_at DESC, id);
CREATE INDEX ix_stock_document_type_created
    ON stock_documents(restaurant_id, document_type, created_at DESC, id);
CREATE INDEX ix_stock_document_warehouse_created
    ON stock_documents(restaurant_id, warehouse_id, created_at DESC, id);

CREATE TABLE stock_document_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    stock_document_id UUID NOT NULL,
    ingredient_id UUID NOT NULL,
    ingredient_sku VARCHAR(80),
    ingredient_name VARCHAR(150) NOT NULL,
    unit VARCHAR(30) NOT NULL,
    quantity NUMERIC(14,3) NOT NULL CHECK (quantity > 0),
    adjustment_direction VARCHAR(20),
    unit_cost NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    total_cost NUMERIC(16,2) NOT NULL DEFAULT 0 CHECK (total_cost >= 0),
    note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_stock_item_adjustment_direction
        CHECK (adjustment_direction IS NULL OR adjustment_direction IN ('INCREASE','DECREASE')),
    CONSTRAINT uq_stock_document_item UNIQUE (stock_document_id, ingredient_id),
    CONSTRAINT uq_stock_document_item_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_stock_item_document FOREIGN KEY (restaurant_id, stock_document_id)
        REFERENCES stock_documents(restaurant_id, id),
    CONSTRAINT fk_stock_item_ingredient FOREIGN KEY (restaurant_id, ingredient_id)
        REFERENCES items(restaurant_id, id)
);

CREATE INDEX ix_stock_document_item_document
    ON stock_document_items(restaurant_id, stock_document_id, id);

CREATE TABLE inventory_transactions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    warehouse_id UUID NOT NULL,
    item_id UUID NOT NULL,
    transaction_type VARCHAR(30) NOT NULL,
    stock_document_id UUID NOT NULL,
    stock_document_item_id UUID NOT NULL,
    quantity_change NUMERIC(14,3) NOT NULL CHECK (quantity_change <> 0),
    before_quantity NUMERIC(14,3) NOT NULL CHECK (before_quantity >= 0),
    after_quantity NUMERIC(14,3) NOT NULL CHECK (after_quantity >= 0),
    unit VARCHAR(30) NOT NULL,
    unit_cost NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (unit_cost >= 0),
    idempotency_key VARCHAR(150) NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_inventory_transaction_type CHECK (transaction_type IN (
        'RECEIPT','ISSUE','ADJUSTMENT_INCREASE','ADJUSTMENT_DECREASE'
    )),
    CONSTRAINT ck_inventory_transaction_equation
        CHECK (after_quantity = before_quantity + quantity_change),
    CONSTRAINT uq_inventory_transaction_document_item UNIQUE (stock_document_item_id),
    CONSTRAINT uq_inventory_transaction_idempotency UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT fk_inventory_transaction_warehouse FOREIGN KEY (restaurant_id, warehouse_id)
        REFERENCES warehouses(restaurant_id, id),
    CONSTRAINT fk_inventory_transaction_item FOREIGN KEY (restaurant_id, item_id)
        REFERENCES items(restaurant_id, id),
    CONSTRAINT fk_inventory_transaction_document FOREIGN KEY (restaurant_id, stock_document_id)
        REFERENCES stock_documents(restaurant_id, id),
    CONSTRAINT fk_inventory_transaction_document_item FOREIGN KEY (restaurant_id, stock_document_item_id)
        REFERENCES stock_document_items(restaurant_id, id)
);

CREATE INDEX ix_inventory_transaction_warehouse_created
    ON inventory_transactions(restaurant_id, warehouse_id, created_at DESC, id);
CREATE INDEX ix_inventory_transaction_item_created
    ON inventory_transactions(restaurant_id, item_id, created_at DESC, id);
CREATE INDEX ix_inventory_transaction_document
    ON inventory_transactions(restaurant_id, stock_document_id);

CREATE OR REPLACE FUNCTION reject_inventory_transaction_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'inventory_transactions is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_inventory_transactions_append_only
BEFORE UPDATE OR DELETE ON inventory_transactions
FOR EACH ROW EXECUTE FUNCTION reject_inventory_transaction_mutation();
