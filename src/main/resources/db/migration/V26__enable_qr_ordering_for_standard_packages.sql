-- Catalog only: QR ordering is now included in the three standard packages.
-- Preserve custom packages, existing mapping limits and immutable subscription snapshots.
INSERT INTO package_features (package_id, feature_id)
SELECT p.id, f.id
FROM packages p CROSS JOIN features f
WHERE p.code IN ('BASIC', 'PRO', 'PREMIUM')
  AND f.code IN ('QR_TABLE_ORDER', 'QR_STATIC_ORDER')
ON CONFLICT (package_id, feature_id) DO NOTHING;
