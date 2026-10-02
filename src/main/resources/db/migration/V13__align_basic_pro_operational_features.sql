-- Catalog-only upgrade: BASIC shares PRO's standard operational features.
-- PRO additionally includes recipes. Preserve prices, max_staff (including custom
-- values), existing mapping limits, PREMIUM, custom plans and all snapshots.
WITH additions(package_code, feature_code) AS (
    VALUES
        ('BASIC', 'STAFF_PERMISSION'),
        ('BASIC', 'KITCHEN_DISPLAY'),
        ('BASIC', 'DETAIL_REPORT'),
        ('BASIC', 'KITCHEN_TICKET_PRINT'),
        ('BASIC', 'KITCHEN_TICKET_REPRINT'),
        ('PRO', 'RECIPE_MANAGEMENT')
)
INSERT INTO package_features (package_id, feature_id)
SELECT p.id, f.id
FROM additions a
JOIN packages p ON p.code = a.package_code
JOIN features f ON f.code = a.feature_code
ON CONFLICT (package_id, feature_id) DO NOTHING;
