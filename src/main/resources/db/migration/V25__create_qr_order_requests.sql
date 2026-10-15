-- QR requests are an approval ledger, not orders or reservations.
-- Credentials are never persisted in plaintext; generated tokens use application SecureRandom.
CREATE TABLE qr_table_access (
 table_id UUID PRIMARY KEY,
 restaurant_id UUID NOT NULL REFERENCES restaurants(id),
 epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT fk_qr_table_access_tenant FOREIGN KEY (restaurant_id,table_id) REFERENCES restaurant_tables(restaurant_id,id),
 UNIQUE (restaurant_id,table_id)
);
INSERT INTO qr_table_access(table_id,restaurant_id,epoch,updated_at)
 SELECT id,restaurant_id,0,updated_at FROM restaurant_tables;

CREATE TABLE qr_guest_authorizations (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 restaurant_id UUID NOT NULL REFERENCES restaurants(id),
 guest_id UUID NOT NULL,
 entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('TABLE','RESTAURANT')),
 table_id UUID,
 table_access_epoch BIGINT CHECK (table_access_epoch >= 0),
 credential_hash VARCHAR(64) NOT NULL CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
 token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
 expires_at TIMESTAMPTZ NOT NULL,
 revoked_at TIMESTAMPTZ,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT uq_qr_guest_tenant_id UNIQUE (restaurant_id,id),
 CONSTRAINT fk_qr_guest_table FOREIGN KEY (restaurant_id,table_id) REFERENCES restaurant_tables(restaurant_id,id),
 CONSTRAINT ck_qr_guest_scope CHECK (
   (entry_type='TABLE' AND table_id IS NOT NULL AND table_access_epoch IS NOT NULL)
   OR (entry_type='RESTAURANT' AND table_id IS NULL AND table_access_epoch IS NULL)),
 CONSTRAINT ck_qr_guest_period CHECK (expires_at > created_at)
);
CREATE INDEX ix_qr_guest_table_scope ON qr_guest_authorizations(restaurant_id,table_id) WHERE revoked_at IS NULL;
CREATE INDEX ix_qr_guest_restaurant_scope ON qr_guest_authorizations(restaurant_id,entry_type,credential_hash) WHERE revoked_at IS NULL;
CREATE INDEX ix_qr_guest_expiry ON qr_guest_authorizations(expires_at);

CREATE TABLE qr_order_requests (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 restaurant_id UUID NOT NULL REFERENCES restaurants(id),
 guest_authorization_id UUID NOT NULL,
 guest_id UUID NOT NULL,
 entry_type VARCHAR(20) NOT NULL CHECK (entry_type IN ('TABLE','RESTAURANT')),
 table_id UUID,
 table_access_epoch BIGINT CHECK (table_access_epoch >= 0),
 credential_hash VARCHAR(64) NOT NULL CHECK (credential_hash ~ '^[0-9a-f]{64}$'),
 observed_session_id UUID,
 idempotency_key VARCHAR(100) NOT NULL CHECK (btrim(idempotency_key) <> ''),
 payload_hash VARCHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
 customer_name VARCHAR(100),
 customer_phone VARCHAR(30),
 note VARCHAR(500),
 currency_code CHAR(3) NOT NULL,
 estimated_amount NUMERIC(14,2) NOT NULL CHECK (estimated_amount >= 0),
 expires_at TIMESTAMPTZ NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
 version BIGINT NOT NULL DEFAULT 0,
 approved_order_id UUID,
 approved_session_id UUID,
 approved_by UUID REFERENCES users(id),
 approved_at TIMESTAMPTZ,
 approved_amount NUMERIC(14,2) CHECK (approved_amount >= 0),
 approved_order_code VARCHAR(50),
 rejected_by UUID REFERENCES users(id),
 rejected_at TIMESTAMPTZ,
 rejection_reason VARCHAR(1000),
 cancelled_at TIMESTAMPTZ,
 cancellation_reason VARCHAR(100),
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT uq_qr_request_tenant_id UNIQUE (restaurant_id,id),
 CONSTRAINT uq_qr_request_idempotency UNIQUE (restaurant_id,idempotency_key),
 CONSTRAINT fk_qr_request_guest FOREIGN KEY (restaurant_id,guest_authorization_id) REFERENCES qr_guest_authorizations(restaurant_id,id),
 CONSTRAINT fk_qr_request_table FOREIGN KEY (restaurant_id,table_id) REFERENCES restaurant_tables(restaurant_id,id),
 CONSTRAINT fk_qr_request_observed_session FOREIGN KEY (restaurant_id,observed_session_id) REFERENCES table_sessions(restaurant_id,id),
 CONSTRAINT fk_qr_request_approved_order FOREIGN KEY (restaurant_id,approved_order_id) REFERENCES orders(restaurant_id,id),
 CONSTRAINT fk_qr_request_approved_session FOREIGN KEY (restaurant_id,approved_session_id) REFERENCES table_sessions(restaurant_id,id),
 CONSTRAINT ck_qr_request_scope CHECK (
   (entry_type='TABLE' AND table_id IS NOT NULL AND table_access_epoch IS NOT NULL)
   OR (entry_type='RESTAURANT' AND table_id IS NULL AND table_access_epoch IS NULL AND observed_session_id IS NULL AND approved_session_id IS NULL)),
 CONSTRAINT ck_qr_takeaway_customer CHECK (entry_type <> 'RESTAURANT' OR (customer_name IS NOT NULL AND btrim(customer_name) <> '')),
 CONSTRAINT ck_qr_request_period CHECK (expires_at > created_at),
 CONSTRAINT ck_qr_request_outcome CHECK (
   (status='PENDING' AND approved_order_id IS NULL AND approved_at IS NULL AND rejected_at IS NULL AND cancelled_at IS NULL)
   OR (status='APPROVED' AND approved_order_id IS NOT NULL AND approved_by IS NOT NULL AND approved_at IS NOT NULL AND approved_amount IS NOT NULL AND approved_order_code IS NOT NULL
       AND rejected_at IS NULL AND cancelled_at IS NULL AND (entry_type='RESTAURANT' OR approved_session_id IS NOT NULL))
   OR (status='REJECTED' AND rejected_by IS NOT NULL AND rejected_at IS NOT NULL AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> '' AND approved_order_id IS NULL AND cancelled_at IS NULL)
   OR (status='CANCELLED' AND cancelled_at IS NOT NULL AND cancellation_reason IS NOT NULL AND approved_order_id IS NULL AND rejected_at IS NULL))
);
-- Many TABLE requests may intentionally link to one serving order. No unique approved_order_id.
CREATE INDEX ix_qr_request_queue ON qr_order_requests(restaurant_id,status,created_at,id);
CREATE INDEX ix_qr_request_entry_queue ON qr_order_requests(restaurant_id,entry_type,status,expires_at,id);
CREATE INDEX ix_qr_request_table_queue ON qr_order_requests(restaurant_id,table_id,status);
CREATE INDEX ix_qr_request_session_queue ON qr_order_requests(restaurant_id,observed_session_id,status);
CREATE INDEX ix_qr_request_guest_receipt ON qr_order_requests(restaurant_id,guest_id,id);
CREATE INDEX ix_qr_request_credential_scope ON qr_order_requests(restaurant_id,entry_type,credential_hash,status);

CREATE TABLE qr_order_request_items (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 restaurant_id UUID NOT NULL,
 request_id UUID NOT NULL,
 item_id UUID NOT NULL,
 line_number INTEGER NOT NULL CHECK (line_number BETWEEN 1 AND 20),
 quantity NUMERIC(12,3) NOT NULL CHECK (quantity > 0 AND quantity <= 20),
 note VARCHAR(500),
 quoted_item_name VARCHAR(150) NOT NULL,
 quoted_unit VARCHAR(30) NOT NULL,
 quoted_unit_price NUMERIC(14,2) NOT NULL CHECK (quoted_unit_price >= 0),
 quoted_line_total NUMERIC(14,2) NOT NULL CHECK (quoted_line_total >= 0),
 approved_order_item_id UUID,
 created_at TIMESTAMPTZ NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL,
 CONSTRAINT uq_qr_request_item_line UNIQUE (request_id,line_number),
 CONSTRAINT fk_qr_request_item_request FOREIGN KEY (restaurant_id,request_id) REFERENCES qr_order_requests(restaurant_id,id),
 CONSTRAINT fk_qr_request_item_menu FOREIGN KEY (restaurant_id,item_id) REFERENCES items(restaurant_id,id),
 CONSTRAINT fk_qr_request_item_order_line FOREIGN KEY (restaurant_id,approved_order_item_id) REFERENCES order_items(restaurant_id,id)
);
CREATE INDEX ix_qr_request_items_batch ON qr_order_request_items(restaurant_id,request_id,line_number);
