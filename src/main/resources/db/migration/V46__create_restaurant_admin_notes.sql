-- Internal support notes written by super admins about a restaurant; never visible to the tenant.
CREATE TABLE restaurant_admin_notes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id) ON DELETE RESTRICT,
    author_id UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    content VARCHAR(2000) NOT NULL CHECK (length(btrim(content)) BETWEEN 1 AND 2000),
    created_at TIMESTAMPTZ NOT NULL,
    deleted_at TIMESTAMPTZ,
    deleted_by UUID REFERENCES users(id) ON DELETE RESTRICT,
    CONSTRAINT ck_restaurant_admin_notes_deleted CHECK ((deleted_at IS NULL) = (deleted_by IS NULL))
);
CREATE INDEX ix_restaurant_admin_notes_restaurant ON restaurant_admin_notes (restaurant_id, created_at DESC)
    WHERE deleted_at IS NULL;
