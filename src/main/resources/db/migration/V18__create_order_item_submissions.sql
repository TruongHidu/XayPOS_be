-- A separate idempotency namespace for successful submitted additions.
-- Multiple orders per OPEN table session intentionally remain valid.
CREATE TABLE order_item_submissions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    order_id UUID NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_order_item_submission_tenant_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES orders(restaurant_id, id),
    CONSTRAINT uq_order_item_submission_key UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT ck_order_item_submission_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_order_item_submission_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_order_item_submission_order ON order_item_submissions(restaurant_id, order_id, created_at);
