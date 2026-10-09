CREATE TABLE orders (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    order_code VARCHAR(50) NOT NULL CHECK (btrim(order_code) <> ''),
    table_session_id UUID,
    service_type VARCHAR(20) NOT NULL CHECK (service_type IN ('DINE_IN','TAKEAWAY')),
    source_channel VARCHAR(20) NOT NULL CHECK (source_channel IN ('CASHIER','WAITER','QR_STATIC','QR_TABLE')),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN','CONFIRMED','PREPARING','READY','SERVED','COMPLETED','CANCELLED')),
    payment_status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' CHECK (payment_status IN ('UNPAID','PARTIALLY_PAID','PAID','PARTIALLY_REFUNDED','REFUNDED')),
    customer_name VARCHAR(100),
    customer_phone VARCHAR(30),
    guest_count SMALLINT NOT NULL DEFAULT 1 CHECK (guest_count > 0),
    currency_code CHAR(3) NOT NULL,
    subtotal_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (subtotal_amount >= 0),
    discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    tax_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (tax_amount >= 0),
    service_charge_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (service_charge_amount >= 0),
    total_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    paid_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (paid_amount >= 0),
    note TEXT,
    cancel_reason TEXT,
    created_by UUID REFERENCES users(id),
    confirmed_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    -- A dirty scalar for child-only mutations even when totals/Clock do not change.
    mutation_sequence BIGINT NOT NULL DEFAULT 0 CHECK (mutation_sequence >= 0),
    idempotency_key VARCHAR(100) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_hash CHAR(64) NOT NULL,
    CONSTRAINT uq_order_tenant_id UNIQUE (restaurant_id,id),
    CONSTRAINT uq_order_code UNIQUE (restaurant_id,order_code),
    CONSTRAINT uq_order_idempotency UNIQUE (restaurant_id,idempotency_key),
    CONSTRAINT fk_order_session FOREIGN KEY (restaurant_id,table_session_id) REFERENCES table_sessions(restaurant_id,id),
    CONSTRAINT ck_order_serving_context CHECK (
        (service_type='DINE_IN' AND table_session_id IS NOT NULL)
        OR (service_type='TAKEAWAY' AND table_session_id IS NULL)
    ),
    CONSTRAINT ck_order_cancellation CHECK (status <> 'CANCELLED' OR
        (cancelled_at IS NOT NULL AND cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CONSTRAINT ck_order_total CHECK (total_amount = subtotal_amount - discount_amount + tax_amount + service_charge_amount)
);
CREATE INDEX ix_order_listing ON orders(restaurant_id,status,created_at DESC,id);
CREATE INDEX ix_order_session ON orders(restaurant_id,table_session_id);

CREATE TABLE order_items (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    order_id UUID NOT NULL,
    item_id UUID,
    item_name VARCHAR(150) NOT NULL CHECK (btrim(item_name) <> ''),
    unit VARCHAR(30) NOT NULL CHECK (btrim(unit) <> ''),
    quantity NUMERIC(12,3) NOT NULL CHECK (quantity > 0),
    unit_price NUMERIC(14,2) NOT NULL CHECK (unit_price >= 0),
    discount_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    line_total NUMERIC(14,2) NOT NULL CHECK (line_total >= 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','COOKING','READY','SERVED','CANCELLED')),
    note TEXT,
    sent_to_kitchen_at TIMESTAMPTZ,
    cancel_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Stable display order, independent of random UUIDs and equal timestamps.
    line_number INTEGER NOT NULL CHECK (line_number > 0),
    CONSTRAINT uq_order_item_tenant_id UNIQUE (restaurant_id,id),
    CONSTRAINT uq_order_line_number UNIQUE (order_id,line_number),
    CONSTRAINT fk_order_item_order FOREIGN KEY (restaurant_id,order_id) REFERENCES orders(restaurant_id,id),
    CONSTRAINT fk_order_item_menu FOREIGN KEY (restaurant_id,item_id) REFERENCES items(restaurant_id,id),
    CONSTRAINT ck_order_item_cancellation CHECK (status <> 'CANCELLED' OR
        (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> ''))
);
CREATE INDEX ix_order_item_order ON order_items(restaurant_id,order_id,line_number);

CREATE TABLE order_item_status_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    order_item_id UUID NOT NULL,
    from_status VARCHAR(20) CHECK (from_status IN ('PENDING','COOKING','READY','SERVED','CANCELLED')),
    to_status VARCHAR(20) NOT NULL CHECK (to_status IN ('PENDING','COOKING','READY','SERVED','CANCELLED')),
    changed_by UUID REFERENCES users(id),
    note TEXT,
    changed_at TIMESTAMPTZ NOT NULL,
    change_sequence BIGINT NOT NULL CHECK (change_sequence >= 0),
    CONSTRAINT uq_order_item_history_sequence UNIQUE (order_item_id,change_sequence),
    CONSTRAINT fk_order_history_item FOREIGN KEY (restaurant_id,order_item_id) REFERENCES order_items(restaurant_id,id)
);
CREATE INDEX ix_order_item_history ON order_item_status_history(restaurant_id,order_item_id,change_sequence);
CREATE FUNCTION prevent_order_item_history_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order_item_status_history is append-only';
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER order_item_history_no_mutation BEFORE UPDATE OR DELETE ON order_item_status_history
FOR EACH ROW EXECUTE FUNCTION prevent_order_item_history_mutation();
