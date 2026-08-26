CREATE INDEX IF NOT EXISTS ix_restaurants_status ON restaurants (status);
CREATE INDEX IF NOT EXISTS ix_restaurants_deleted_at ON restaurants (deleted_at) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_users_active ON users (is_active) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_permissions_module ON permissions (module);
CREATE INDEX IF NOT EXISTS ix_permissions_active ON permissions (is_active);
CREATE INDEX IF NOT EXISTS ix_user_permissions_created_by ON user_permissions (created_by);
CREATE INDEX IF NOT EXISTS ix_auth_refresh_tokens_active_user_expiry
    ON auth_refresh_tokens (user_id, expires_at) WHERE revoked_at IS NULL;
CREATE INDEX IF NOT EXISTS ix_audit_logs_action_code ON audit_logs (action_code);
