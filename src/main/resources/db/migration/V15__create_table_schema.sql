CREATE TABLE table_areas (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    description TEXT,
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    is_active BOOLEAN NOT NULL DEFAULT true,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_table_area_tenant_id UNIQUE (restaurant_id, id)
);
CREATE UNIQUE INDEX ux_table_area_name ON table_areas(restaurant_id, lower(name)) WHERE deleted_at IS NULL;

CREATE TABLE restaurant_tables (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    area_id UUID,
    code VARCHAR(50) NOT NULL CHECK (btrim(code) <> ''),
    name VARCHAR(100) NOT NULL CHECK (btrim(name) <> ''),
    capacity SMALLINT NOT NULL DEFAULT 4 CHECK (capacity > 0),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'INACTIVE')),
    qr_token VARCHAR(128) NOT NULL,
    display_order INTEGER NOT NULL DEFAULT 0 CHECK (display_order >= 0),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    CONSTRAINT uq_restaurant_table_code UNIQUE (restaurant_id, code),
    CONSTRAINT uq_restaurant_table_qr UNIQUE (qr_token),
    CONSTRAINT uq_restaurant_table_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_restaurant_table_area FOREIGN KEY (restaurant_id, area_id) REFERENCES table_areas(restaurant_id, id)
);
CREATE INDEX ix_restaurant_table_area ON restaurant_tables(restaurant_id, area_id);
CREATE INDEX ix_restaurant_table_listing ON restaurant_tables(restaurant_id, status, display_order, id) WHERE deleted_at IS NULL;

CREATE TABLE table_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    table_id UUID NOT NULL,
    session_code VARCHAR(50) NOT NULL CHECK (btrim(session_code) <> ''),
    status VARCHAR(20) NOT NULL CHECK (status IN ('OPEN', 'CLOSED', 'CANCELLED')),
    guest_count SMALLINT NOT NULL DEFAULT 1 CHECK (guest_count > 0),
    opened_by UUID NOT NULL REFERENCES users(id),
    closed_by UUID REFERENCES users(id),
    opened_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    note TEXT,
    cancel_reason TEXT,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_table_session_code UNIQUE (restaurant_id, session_code),
    CONSTRAINT uq_table_session_tenant_id UNIQUE (restaurant_id, id),
    CONSTRAINT fk_table_session_table FOREIGN KEY (restaurant_id, table_id) REFERENCES restaurant_tables(restaurant_id, id),
    CONSTRAINT ck_table_session_terminal CHECK (
        (status = 'OPEN' AND closed_at IS NULL AND closed_by IS NULL)
        OR (status IN ('CLOSED', 'CANCELLED') AND closed_at IS NOT NULL AND closed_by IS NOT NULL)
    ),
    CONSTRAINT ck_table_session_cancel_reason CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND btrim(cancel_reason) <> '')),
    CONSTRAINT ck_table_session_period CHECK (closed_at IS NULL OR closed_at >= opened_at)
);
CREATE UNIQUE INDEX ux_table_session_open ON table_sessions(table_id) WHERE status = 'OPEN';
CREATE INDEX ix_table_session_table ON table_sessions(restaurant_id, table_id, opened_at DESC);
CREATE INDEX ix_table_session_listing ON table_sessions(restaurant_id, status, opened_at DESC, id);
