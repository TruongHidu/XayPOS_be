CREATE TABLE payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    order_id UUID NOT NULL,
    payment_code VARCHAR(50) NOT NULL CHECK (btrim(payment_code) <> ''),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    currency_code CHAR(3) NOT NULL,
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CASH','TRANSFER')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED','CANCELLED')),
    confirmation_source VARCHAR(30) NOT NULL CHECK (confirmation_source IN ('STAFF_MANUAL','PROVIDER_WEBHOOK')),
    provider VARCHAR(50),
    provider_transaction_id VARCHAR(150),
    reference_no VARCHAR(100),
    idempotency_key VARCHAR(100) CHECK (idempotency_key IS NULL OR btrim(idempotency_key) <> ''),
    request_fingerprint CHAR(64),
    requested_at TIMESTAMPTZ NOT NULL,
    paid_at TIMESTAMPTZ,
    created_by UUID REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_payment_order FOREIGN KEY (restaurant_id,order_id) REFERENCES orders(restaurant_id,id),
    CONSTRAINT uq_payment_tenant_id UNIQUE (restaurant_id,id),
    CONSTRAINT uq_payment_code UNIQUE (restaurant_id,payment_code),
    CONSTRAINT uq_payment_idempotency UNIQUE (restaurant_id,idempotency_key),
    CONSTRAINT ck_payment_provider_transaction CHECK (provider_transaction_id IS NULL OR provider IS NOT NULL),
    CONSTRAINT ck_payment_paid_at CHECK (status <> 'SUCCESS' OR paid_at IS NOT NULL),
    CONSTRAINT ck_manual_payment_request CHECK (confirmation_source <> 'STAFF_MANUAL' OR
        (created_by IS NOT NULL AND idempotency_key IS NOT NULL AND request_fingerprint IS NOT NULL))
);
CREATE INDEX ix_payment_order_history ON payments(restaurant_id,order_id,created_at DESC,id DESC);
CREATE UNIQUE INDEX ux_payment_provider_transaction ON payments(provider,provider_transaction_id)
    WHERE provider IS NOT NULL AND provider_transaction_id IS NOT NULL;
