CREATE TABLE features (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(100) NOT NULL UNIQUE,
    name VARCHAR(150) NOT NULL,
    description TEXT,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE packages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    price_amount NUMERIC(14, 2) NOT NULL CHECK (price_amount >= 0),
    currency_code CHAR(3) NOT NULL DEFAULT 'VND',
    billing_cycle_months SMALLINT NOT NULL CHECK (billing_cycle_months > 0),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE package_features (
    package_id UUID NOT NULL REFERENCES packages(id) ON DELETE CASCADE,
    feature_id UUID NOT NULL REFERENCES features(id) ON DELETE CASCADE,
    limits JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (package_id, feature_id)
);

CREATE TABLE restaurant_subscriptions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    package_id UUID NOT NULL REFERENCES packages(id),
    status VARCHAR(20) NOT NULL,
    start_at TIMESTAMPTZ NOT NULL,
    end_at TIMESTAMPTZ NOT NULL,
    auto_renew BOOLEAN NOT NULL DEFAULT FALSE,
    price_amount NUMERIC(14, 2) NOT NULL CHECK (price_amount >= 0),
    currency_code CHAR(3) NOT NULL,
    feature_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb,
    activated_at TIMESTAMPTZ,
    cancelled_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_restaurant_subscriptions_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'EXPIRED', 'CANCELLED')),
    CONSTRAINT ck_restaurant_subscriptions_period CHECK (end_at > start_at)
);

CREATE UNIQUE INDEX uq_restaurant_subscriptions_one_active
    ON restaurant_subscriptions (restaurant_id)
    WHERE status = 'ACTIVE';

CREATE INDEX ix_features_active ON features (is_active);
CREATE INDEX ix_packages_active ON packages (is_active);
CREATE INDEX ix_package_features_package_id ON package_features (package_id);
CREATE INDEX ix_package_features_feature_id ON package_features (feature_id);
CREATE INDEX ix_restaurant_subscriptions_package_id ON restaurant_subscriptions (package_id);
CREATE INDEX ix_restaurant_subscriptions_restaurant_status
    ON restaurant_subscriptions (restaurant_id, status);
CREATE INDEX ix_restaurant_subscriptions_restaurant_period
    ON restaurant_subscriptions (restaurant_id, start_at, end_at);

CREATE TRIGGER features_set_updated_at
    BEFORE UPDATE ON features
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER packages_set_updated_at
    BEFORE UPDATE ON packages
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER restaurant_subscriptions_set_updated_at
    BEFORE UPDATE ON restaurant_subscriptions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
