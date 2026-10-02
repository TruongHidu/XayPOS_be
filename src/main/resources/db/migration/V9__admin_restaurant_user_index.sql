CREATE INDEX IF NOT EXISTS ix_users_admin_restaurant_created
    ON users (restaurant_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;
