-- A successful seating movement is both immutable history and its replay ledger.
CREATE TABLE table_session_transfers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    session_id UUID NOT NULL,
    from_table_id UUID NOT NULL,
    to_table_id UUID NOT NULL,
    from_table_code VARCHAR(50) NOT NULL,
    from_table_name VARCHAR(100) NOT NULL,
    to_table_code VARCHAR(50) NOT NULL,
    to_table_name VARCHAR(100) NOT NULL,
    actor_user_id UUID NOT NULL REFERENCES users(id),
    reason VARCHAR(1000),
    session_version_before BIGINT NOT NULL,
    session_version_after BIGINT NOT NULL,
    idempotency_key VARCHAR(100) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    transferred_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_table_transfer_session FOREIGN KEY (restaurant_id, session_id)
        REFERENCES table_sessions(restaurant_id, id),
    CONSTRAINT fk_table_transfer_source FOREIGN KEY (restaurant_id, from_table_id)
        REFERENCES restaurant_tables(restaurant_id, id),
    CONSTRAINT fk_table_transfer_target FOREIGN KEY (restaurant_id, to_table_id)
        REFERENCES restaurant_tables(restaurant_id, id),
    CONSTRAINT uq_table_transfer_key UNIQUE (restaurant_id, idempotency_key),
    CONSTRAINT uq_table_transfer_version UNIQUE (restaurant_id, session_id, session_version_after),
    CONSTRAINT ck_table_transfer_different_tables CHECK (from_table_id <> to_table_id),
    CONSTRAINT ck_table_transfer_versions CHECK (session_version_before >= 0 AND session_version_after > session_version_before),
    CONSTRAINT ck_table_transfer_key CHECK (length(btrim(idempotency_key)) > 0),
    CONSTRAINT ck_table_transfer_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_table_transfer_history
    ON table_session_transfers(restaurant_id, session_id, session_version_after DESC);

CREATE FUNCTION prevent_table_transfer_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'table_session_transfers is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER table_session_transfers_no_mutation
    BEFORE UPDATE OR DELETE ON table_session_transfers
    FOR EACH ROW EXECUTE FUNCTION prevent_table_transfer_mutation();

INSERT INTO permissions(code, module, name, description)
VALUES ('TABLE_TRANSFER', 'TABLE', 'Transfer table session', 'Move an open session to an unoccupied table')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions(role_id, permission_id)
SELECT r.id, p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system
  AND r.code IN ('OWNER', 'MANAGER', 'WAITER') AND p.code = 'TABLE_TRANSFER'
ON CONFLICT (role_id, permission_id) DO NOTHING;
