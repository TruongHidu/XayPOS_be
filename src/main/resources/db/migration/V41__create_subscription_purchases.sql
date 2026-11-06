-- Purchase/onboarding only. Attempts and inbox schema are ready for later phases;
-- this migration does not enable QR, provider ingestion, settlement or activation.
DO $$
BEGIN
    IF EXISTS (SELECT id, restaurant_id FROM users GROUP BY id, restaurant_id HAVING count(*) > 1)
       OR EXISTS (SELECT id, restaurant_id FROM restaurant_subscriptions GROUP BY id, restaurant_id HAVING count(*) > 1) THEN
        RAISE EXCEPTION 'V41: duplicate tenant identities; correct data before migration; no records were removed';
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_id_restaurant ON users (id, restaurant_id);
CREATE UNIQUE INDEX IF NOT EXISTS uq_subscription_id_restaurant ON restaurant_subscriptions (id, restaurant_id);

CREATE FUNCTION subscription_purchase_quote_valid(q JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE g JSONB;
BEGIN
    IF jsonb_typeof(q) IS DISTINCT FROM 'object'
       OR NOT q ?& ARRAY['schemaVersion','packageId','packageCode','packageName','priceAmount','currencyCode',
                         'billingCycleMonths','features','maxStaff','quotedAt','expiresAt','billingZoneId']
       OR q->>'schemaVersion' IS DISTINCT FROM '1'
       OR q->>'currencyCode' IS DISTINCT FROM 'VND'
       OR q->>'billingZoneId' IS DISTINCT FROM 'Asia/Ho_Chi_Minh'
       OR jsonb_typeof(q->'features') IS DISTINCT FROM 'array'
       OR jsonb_typeof(q->'priceAmount') IS DISTINCT FROM 'number'
       OR jsonb_typeof(q->'billingCycleMonths') IS DISTINCT FROM 'number'
       OR nullif(btrim(q->>'packageCode'), '') IS NULL
       OR nullif(btrim(q->>'packageName'), '') IS NULL THEN RETURN FALSE; END IF;
    IF (q->>'priceAmount')::NUMERIC <= 0 OR (q->>'priceAmount')::NUMERIC > 999999999999
       OR trunc((q->>'priceAmount')::NUMERIC) <> (q->>'priceAmount')::NUMERIC
       OR (q->>'billingCycleMonths')::NUMERIC NOT BETWEEN 1 AND 32767
       OR trunc((q->>'billingCycleMonths')::NUMERIC) <> (q->>'billingCycleMonths')::NUMERIC
       OR (q->>'expiresAt')::TIMESTAMPTZ <> (q->>'quotedAt')::TIMESTAMPTZ + INTERVAL '15 minutes'
       OR (q->>'packageId')::UUID IS NULL THEN RETURN FALSE; END IF;
    IF q->'maxStaff' <> 'null'::jsonb AND (
        jsonb_typeof(q->'maxStaff') <> 'number' OR (q->>'maxStaff')::NUMERIC <= 0
        OR (q->>'maxStaff')::NUMERIC > 9223372036854775807
        OR trunc((q->>'maxStaff')::NUMERIC) <> (q->>'maxStaff')::NUMERIC) THEN RETURN FALSE; END IF;
    FOR g IN SELECT value FROM jsonb_array_elements(q->'features') LOOP
        IF jsonb_typeof(g) IS DISTINCT FROM 'object' OR NOT g ?& ARRAY['code','limits']
           OR nullif(btrim(g->>'code'), '') IS NULL OR jsonb_typeof(g->'limits') IS DISTINCT FROM 'object'
           OR (g->'limits') ? 'maxStaff' THEN RETURN FALSE; END IF;
    END LOOP;
    IF (SELECT count(*) <> count(DISTINCT value->>'code') FROM jsonb_array_elements(q->'features')) THEN RETURN FALSE; END IF;
    RETURN TRUE;
EXCEPTION WHEN OTHERS THEN RETURN FALSE;
END $$;

CREATE TABLE subscription_purchases (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE RESTRICT,
    created_by UUID NOT NULL,
    package_id UUID NOT NULL REFERENCES packages(id) ON DELETE RESTRICT,
    status VARCHAR(24) NOT NULL CHECK (status IN ('PENDING_PAYMENT','PAID','ACTIVATED','EXPIRED','CANCELLED','REVIEW_REQUIRED','CLOSED')),
    quote JSONB NOT NULL CHECK (subscription_purchase_quote_valid(quote)),
    quote_schema_version SMALLINT NOT NULL DEFAULT 1 CHECK (quote_schema_version = 1),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0 AND trunc(amount) = amount),
    currency_code CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency_code = 'VND'),
    quoted_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL CHECK (expires_at = quoted_at + INTERVAL '15 minutes'),
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,100}$'),
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    creation_response JSONB NOT NULL CHECK (jsonb_typeof(creation_response) = 'object'),
    paid_at TIMESTAMPTZ,
    paid_event_id UUID,
    subscription_id UUID,
    activated_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    closed_at TIMESTAMPTZ,
    cancel_reason VARCHAR(500),
    review_reason VARCHAR(60),
    activation_retry_count INTEGER NOT NULL DEFAULT 0 CHECK (activation_retry_count >= 0),
    activation_next_at TIMESTAMPTZ,
    activation_lease_until TIMESTAMPTZ,
    activation_token UUID,
    activation_error_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_sp_tenant UNIQUE (id, restaurant_id),
    CONSTRAINT uq_sp_tenant_expiry UNIQUE (id, restaurant_id, expires_at),
    CONSTRAINT uq_sp_idempotency UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT fk_sp_actor FOREIGN KEY (created_by, restaurant_id) REFERENCES users(id, restaurant_id) ON DELETE RESTRICT,
    CONSTRAINT fk_sp_subscription FOREIGN KEY (subscription_id, restaurant_id)
        REFERENCES restaurant_subscriptions(id, restaurant_id) ON DELETE RESTRICT,
    CONSTRAINT ck_sp_quote_matches CHECK (
        (quote->>'packageId')::UUID = package_id AND (quote->>'priceAmount')::NUMERIC = amount
        AND quote->>'currencyCode' = currency_code AND (quote->>'quotedAt')::TIMESTAMPTZ = quoted_at
        AND (quote->>'expiresAt')::TIMESTAMPTZ = expires_at AND (quote->>'schemaVersion')::INTEGER = quote_schema_version),
    CONSTRAINT ck_sp_paid_evidence CHECK ((paid_at IS NULL) = (paid_event_id IS NULL)),
    CONSTRAINT ck_sp_paid_state CHECK (status NOT IN ('PAID','ACTIVATED') OR paid_event_id IS NOT NULL),
    CONSTRAINT ck_sp_unpaid_state CHECK (status NOT IN ('PENDING_PAYMENT','EXPIRED','CANCELLED') OR paid_event_id IS NULL),
    CONSTRAINT ck_sp_activated CHECK ((status = 'ACTIVATED') = (subscription_id IS NOT NULL AND activated_at IS NOT NULL)),
    CONSTRAINT ck_sp_no_partial_activation CHECK ((subscription_id IS NULL) = (activated_at IS NULL)),
    CONSTRAINT ck_sp_cancelled CHECK (status <> 'CANCELLED' OR cancelled_at IS NOT NULL),
    CONSTRAINT ck_sp_closed CHECK (status <> 'CLOSED' OR closed_at IS NOT NULL),
    CONSTRAINT ck_sp_review CHECK (status <> 'REVIEW_REQUIRED' OR review_reason IS NOT NULL)
);
CREATE UNIQUE INDEX uq_sp_open_restaurant ON subscription_purchases(restaurant_id)
    WHERE status IN ('PENDING_PAYMENT','PAID','REVIEW_REQUIRED');
CREATE UNIQUE INDEX uq_sp_subscription ON subscription_purchases(subscription_id) WHERE subscription_id IS NOT NULL;
CREATE UNIQUE INDEX uq_sp_paid_event ON subscription_purchases(paid_event_id) WHERE paid_event_id IS NOT NULL;
CREATE INDEX ix_sp_history ON subscription_purchases(restaurant_id, created_at DESC, id DESC);
CREATE INDEX ix_sp_expiry ON subscription_purchases(status, expires_at);
CREATE INDEX ix_sp_activation_job ON subscription_purchases(activation_next_at, id) WHERE status = 'PAID';

CREATE TABLE subscription_payment_attempts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    purchase_id UUID NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','SUCCESS','FAILED','EXPIRED','CANCELLED')),
    provider VARCHAR(20) NOT NULL DEFAULT 'SEPAY' CHECK (provider = 'SEPAY'),
    provider_account_key VARCHAR(100) NOT NULL,
    reference_code VARCHAR(40) NOT NULL UNIQUE CHECK (reference_code ~ '^PS[0-9A-F]{24}$'),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0 AND trunc(amount) = amount),
    currency_code CHAR(3) NOT NULL CHECK (currency_code = 'VND'),
    expires_at TIMESTAMPTZ NOT NULL,
    instructions JSONB NOT NULL CHECK (jsonb_typeof(instructions) = 'object'),
    idempotency_key VARCHAR(100) NOT NULL CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{8,100}$'),
    request_hash CHAR(64) NOT NULL CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    creation_response JSONB NOT NULL CHECK (jsonb_typeof(creation_response) = 'object'),
    successful_event_id UUID,
    paid_at TIMESTAMPTZ,
    failure_code VARCHAR(60),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_spa_tenant UNIQUE (id, restaurant_id, purchase_id),
    CONSTRAINT uq_spa_idempotency UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT fk_spa_purchase FOREIGN KEY (purchase_id, restaurant_id, expires_at)
        REFERENCES subscription_purchases(id, restaurant_id, expires_at) ON DELETE RESTRICT,
    CONSTRAINT ck_spa_period CHECK (expires_at > created_at),
    CONSTRAINT ck_spa_success CHECK ((status = 'SUCCESS') = (paid_at IS NOT NULL AND successful_event_id IS NOT NULL)),
    CONSTRAINT ck_spa_evidence CHECK ((paid_at IS NULL) = (successful_event_id IS NULL))
);
CREATE UNIQUE INDEX uq_spa_pending ON subscription_payment_attempts(purchase_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX uq_spa_success ON subscription_payment_attempts(purchase_id) WHERE status = 'SUCCESS';
CREATE UNIQUE INDEX uq_spa_event ON subscription_payment_attempts(successful_event_id) WHERE successful_event_id IS NOT NULL;
CREATE INDEX ix_spa_history ON subscription_payment_attempts(purchase_id, created_at DESC, id DESC);
CREATE INDEX ix_spa_expiry ON subscription_payment_attempts(status, expires_at);

CREATE TABLE subscription_payment_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider VARCHAR(20) NOT NULL CHECK (provider = 'SEPAY'),
    provider_account_key VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(180) NOT NULL,
    provider_webhook_id BIGINT,
    provider_api_transaction_id UUID,
    bank_reference VARCHAR(150),
    source VARCHAR(24) NOT NULL CHECK (source IN ('WEBHOOK','RECONCILIATION')),
    payload_hash CHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    payload JSONB NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    direction VARCHAR(3) NOT NULL CHECK (direction IN ('in','out')),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0 AND trunc(amount) = amount),
    currency_code CHAR(3) NOT NULL DEFAULT 'VND' CHECK (currency_code = 'VND'),
    receiver_account VARCHAR(100) NOT NULL,
    receiver_bank VARCHAR(100) NOT NULL,
    payment_reference VARCHAR(40),
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    restaurant_id UUID,
    purchase_id UUID,
    attempt_id UUID,
    state VARCHAR(24) NOT NULL DEFAULT 'RECEIVED'
        CHECK (state IN ('RECEIVED','PROCESSING','RETRY_PENDING','PROCESSED','REVIEW_REQUIRED','CLOSED')),
    review_reason VARCHAR(60),
    retry_count INTEGER NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    delivery_count INTEGER NOT NULL DEFAULT 1 CHECK (delivery_count >= 1),
    next_attempt_at TIMESTAMPTZ,
    lease_until TIMESTAMPTZ,
    processing_token UUID,
    last_received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error_code VARCHAR(60),
    reviewed_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    reviewed_at TIMESTAMPTZ,
    review_note VARCHAR(1000),
    resolution_reference VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0),
    CONSTRAINT uq_spe_identity UNIQUE (provider, provider_account_key, provider_transaction_id),
    CONSTRAINT uq_spe_purchase_tenant UNIQUE (id, restaurant_id, purchase_id),
    CONSTRAINT uq_spe_attempt_tenant UNIQUE (id, restaurant_id, purchase_id, attempt_id),
    CONSTRAINT fk_spe_attempt FOREIGN KEY (attempt_id, restaurant_id, purchase_id)
        REFERENCES subscription_payment_attempts(id, restaurant_id, purchase_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_spe_links CHECK ((restaurant_id IS NULL AND purchase_id IS NULL AND attempt_id IS NULL)
        OR (restaurant_id IS NOT NULL AND purchase_id IS NOT NULL AND attempt_id IS NOT NULL)),
    CONSTRAINT ck_spe_review CHECK (state <> 'REVIEW_REQUIRED' OR review_reason IS NOT NULL),
    CONSTRAINT ck_spe_closed CHECK (state <> 'CLOSED' OR (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL
        AND review_note IS NOT NULL AND resolution_reference IS NOT NULL
        AND length(btrim(review_note)) >= 10 AND length(btrim(resolution_reference)) >= 3)),
    CONSTRAINT ck_spe_webhook_id CHECK (provider_webhook_id IS NULL OR provider_webhook_id > 0)
);
CREATE UNIQUE INDEX uq_spe_webhook ON subscription_payment_events(provider, provider_account_key, provider_webhook_id)
    WHERE provider_webhook_id IS NOT NULL;
CREATE UNIQUE INDEX uq_spe_api ON subscription_payment_events(provider, provider_account_key, provider_api_transaction_id)
    WHERE provider_api_transaction_id IS NOT NULL;
CREATE UNIQUE INDEX uq_spe_bank ON subscription_payment_events(provider, provider_account_key, bank_reference)
    WHERE bank_reference IS NOT NULL;
CREATE INDEX ix_spe_job ON subscription_payment_events(state, next_attempt_at, id);
CREATE INDEX ix_spe_lease ON subscription_payment_events(state, lease_until);
CREATE INDEX ix_spe_received ON subscription_payment_events(received_at DESC, id DESC);
CREATE INDEX ix_spe_tenant ON subscription_payment_events(restaurant_id, received_at DESC);
CREATE INDEX ix_spe_purchase_state ON subscription_payment_events(purchase_id, state);
ALTER TABLE subscription_purchases ADD CONSTRAINT fk_sp_paid_event
    FOREIGN KEY (paid_event_id, restaurant_id, id) REFERENCES subscription_payment_events(id, restaurant_id, purchase_id)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE subscription_payment_attempts ADD CONSTRAINT fk_spa_success_event
    FOREIGN KEY (successful_event_id, restaurant_id, purchase_id, id)
    REFERENCES subscription_payment_events(id, restaurant_id, purchase_id, attempt_id)
    ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION subscription_attempt_matches_purchase() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM subscription_purchases p WHERE p.id=NEW.purchase_id
        AND p.restaurant_id=NEW.restaurant_id AND p.amount=NEW.amount AND p.currency_code=NEW.currency_code) THEN
        RAISE EXCEPTION 'Attempt monetary fields must match its purchase' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER subscription_attempt_purchase_amount AFTER INSERT OR UPDATE ON subscription_payment_attempts
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION subscription_attempt_matches_purchase();

CREATE FUNCTION protect_subscription_purchase_history() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE mutable_columns TEXT[];
BEGIN
    IF TG_OP = 'DELETE' THEN RAISE EXCEPTION '% is financial history; hard delete is forbidden', TG_TABLE_NAME USING ERRCODE='23514'; END IF;
    IF TG_TABLE_NAME = 'subscription_purchases' THEN
        mutable_columns := ARRAY['status','paid_at','paid_event_id','subscription_id','activated_at','cancelled_at','closed_at',
            'cancel_reason','review_reason','activation_retry_count','activation_next_at','activation_lease_until',
            'activation_token','activation_error_code','updated_at','version'];
        IF (OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at)
            OR (OLD.paid_event_id IS NOT NULL AND NEW.paid_event_id IS DISTINCT FROM OLD.paid_event_id)
            OR (OLD.subscription_id IS NOT NULL AND NEW.subscription_id IS DISTINCT FROM OLD.subscription_id)
            OR (OLD.activated_at IS NOT NULL AND NEW.activated_at IS DISTINCT FROM OLD.activated_at)
            OR (OLD.cancelled_at IS NOT NULL AND (NEW.cancelled_at IS DISTINCT FROM OLD.cancelled_at OR NEW.cancel_reason IS DISTINCT FROM OLD.cancel_reason))
            OR (OLD.closed_at IS NOT NULL AND NEW.closed_at IS DISTINCT FROM OLD.closed_at) THEN
            RAISE EXCEPTION 'Purchase historical decisions are immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
            (OLD.status='PENDING_PAYMENT' AND NEW.status IN ('PAID','EXPIRED','CANCELLED','REVIEW_REQUIRED'))
            OR (OLD.status='PAID' AND NEW.status IN ('ACTIVATED','REVIEW_REQUIRED'))
            OR (OLD.status='REVIEW_REQUIRED' AND NEW.status IN ('PAID','CLOSED'))
            OR (OLD.status='EXPIRED' AND NEW.status IN ('PAID','REVIEW_REQUIRED'))
            OR (OLD.status='CANCELLED' AND NEW.status='REVIEW_REQUIRED')) THEN
            RAISE EXCEPTION 'Invalid purchase transition' USING ERRCODE='23514';
        END IF;
    ELSIF TG_TABLE_NAME = 'subscription_payment_attempts' THEN
        mutable_columns := ARRAY['status','successful_event_id','paid_at','failure_code','updated_at','version'];
        IF (OLD.successful_event_id IS NOT NULL AND NEW.successful_event_id IS DISTINCT FROM OLD.successful_event_id)
           OR (OLD.paid_at IS NOT NULL AND NEW.paid_at IS DISTINCT FROM OLD.paid_at) THEN
            RAISE EXCEPTION 'Successful attempt evidence is immutable' USING ERRCODE='23514';
        END IF;
        IF NEW.status <> OLD.status AND NOT (
            (OLD.status='PENDING' AND NEW.status IN ('SUCCESS','FAILED','EXPIRED','CANCELLED'))
            OR (OLD.status IN ('EXPIRED','FAILED') AND NEW.status='SUCCESS')) THEN
            RAISE EXCEPTION 'Invalid payment attempt transition' USING ERRCODE='23514';
        END IF;
    ELSE
        mutable_columns := ARRAY['provider_webhook_id','provider_api_transaction_id','restaurant_id','purchase_id','attempt_id',
            'state','review_reason','retry_count','delivery_count','next_attempt_at','lease_until','processing_token',
            'last_received_at','processed_at','last_error_code','reviewed_by','reviewed_at','review_note',
            'resolution_reference','updated_at','version'];
        IF (OLD.restaurant_id IS NOT NULL AND (NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
              OR NEW.purchase_id IS DISTINCT FROM OLD.purchase_id OR NEW.attempt_id IS DISTINCT FROM OLD.attempt_id))
           OR (OLD.provider_webhook_id IS NOT NULL AND NEW.provider_webhook_id IS DISTINCT FROM OLD.provider_webhook_id)
           OR (OLD.provider_api_transaction_id IS NOT NULL AND NEW.provider_api_transaction_id IS DISTINCT FROM OLD.provider_api_transaction_id) THEN
            RAISE EXCEPTION 'Verified event identity cannot be reassigned' USING ERRCODE='23514';
        END IF;
        IF NEW.state <> OLD.state AND NOT (
            (OLD.state='RECEIVED' AND NEW.state IN ('PROCESSING','RETRY_PENDING'))
            OR (OLD.state='PROCESSING' AND NEW.state IN ('PROCESSED','RETRY_PENDING','REVIEW_REQUIRED'))
            OR (OLD.state='RETRY_PENDING' AND NEW.state IN ('PROCESSING','REVIEW_REQUIRED'))
            OR (OLD.state='PROCESSED' AND NEW.state='REVIEW_REQUIRED')
            OR (OLD.state='REVIEW_REQUIRED' AND NEW.state IN ('RETRY_PENDING','CLOSED'))) THEN
            RAISE EXCEPTION 'Invalid payment event transition' USING ERRCODE='23514';
        END IF;
    END IF;
    IF (to_jsonb(NEW) - mutable_columns) IS DISTINCT FROM (to_jsonb(OLD) - mutable_columns) THEN
        RAISE EXCEPTION '% financial snapshot is immutable', TG_TABLE_NAME USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER sp_protect_history BEFORE UPDATE OR DELETE ON subscription_purchases
    FOR EACH ROW EXECUTE FUNCTION protect_subscription_purchase_history();
CREATE TRIGGER spa_protect_history BEFORE UPDATE OR DELETE ON subscription_payment_attempts
    FOR EACH ROW EXECUTE FUNCTION protect_subscription_purchase_history();
CREATE TRIGGER spe_protect_history BEFORE UPDATE OR DELETE ON subscription_payment_events
    FOR EACH ROW EXECUTE FUNCTION protect_subscription_purchase_history();

INSERT INTO permissions(code, module, name) VALUES
    ('SUBSCRIPTION_PURCHASE_VIEW','SUBSCRIPTION','View own restaurant package purchases'),
    ('SUBSCRIPTION_PURCHASE_CREATE','SUBSCRIPTION','Create own restaurant package purchases'),
    ('SUBSCRIPTION_PURCHASE_CANCEL','SUBSCRIPTION','Cancel own restaurant unpaid purchases'),
    ('SUBSCRIPTION_PAYMENT_REVIEW','SUBSCRIPTION','Review SaaS subscription payments')
ON CONFLICT(code) DO NOTHING;
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.code='OWNER'
    AND p.code IN ('SUBSCRIPTION_PURCHASE_VIEW','SUBSCRIPTION_PURCHASE_CREATE','SUBSCRIPTION_PURCHASE_CANCEL')
ON CONFLICT DO NOTHING;
INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.code='SUPER_ADMIN' AND p.code='SUBSCRIPTION_PAYMENT_REVIEW'
ON CONFLICT DO NOTHING;
