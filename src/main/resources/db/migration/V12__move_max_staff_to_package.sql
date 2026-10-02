ALTER TABLE packages ADD COLUMN max_staff BIGINT;
ALTER TABLE packages ADD CONSTRAINT ck_packages_max_staff CHECK (max_staff IS NULL OR max_staff > 0);

-- Fail explicitly rather than turning an invalid legacy configuration into unlimited.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM package_features pf JOIN features f ON f.id = pf.feature_id
        WHERE pf.limits ? 'maxStaff' AND (
            f.code <> 'STAFF_MANAGEMENT' OR
            CASE WHEN jsonb_typeof(pf.limits->'maxStaff') = 'number' THEN
                (pf.limits->>'maxStaff')::numeric <= 0 OR
                (pf.limits->>'maxStaff')::numeric > 9223372036854775807 OR
                trunc((pf.limits->>'maxStaff')::numeric) <> (pf.limits->>'maxStaff')::numeric
            ELSE true END
        )
    ) THEN
        RAISE EXCEPTION 'V12: invalid legacy maxStaff; correct package feature limits before migrating';
    END IF;
END $$;

UPDATE packages p SET max_staff = (pf.limits->>'maxStaff')::numeric::bigint
FROM package_features pf JOIN features f ON f.id = pf.feature_id
WHERE pf.package_id = p.id AND f.code = 'STAFF_MANAGEMENT' AND pf.limits ? 'maxStaff';

UPDATE package_features SET limits = limits - 'maxStaff' WHERE limits ? 'maxStaff';
-- No UPDATE to restaurant_subscriptions: schemaVersion 1 snapshots remain immutable.
