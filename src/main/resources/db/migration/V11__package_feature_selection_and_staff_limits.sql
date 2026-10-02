-- Catalog only: existing subscription snapshots are deliberately untouched.
INSERT INTO features (code, name, description)
VALUES ('QR_MENU_VIEW', 'Public QR menu', 'Read-only public menu; customer ordering is a separate feature')
ON CONFLICT (code) DO NOTHING;

INSERT INTO package_features (package_id, feature_id)
SELECT p.id, f.id
FROM packages p CROSS JOIN features f
WHERE (p.code IN ('BASIC', 'PRO', 'PREMIUM') AND f.code = 'QR_MENU_VIEW')
   OR (p.code = 'BASIC' AND f.code IN ('TABLE_MANAGEMENT', 'STAFF_MANAGEMENT'))
ON CONFLICT (package_id, feature_id) DO NOTHING;

-- Explicit upgrade rule: an empty limits object on the three standard plans is
-- treated as the old V5 default and receives 3/10/30. A deliberately unlimited
-- empty object cannot be distinguished from that seed. Nonempty custom objects
-- (including objects without maxStaff) are preserved exactly. Admin can restore
-- unlimited catalog limits via PUT; active subscriptions retain their old limits.
UPDATE package_features pf
SET limits = jsonb_build_object('maxStaff', CASE p.code WHEN 'BASIC' THEN 3 WHEN 'PRO' THEN 10 ELSE 30 END)
FROM packages p, features f
WHERE pf.package_id = p.id AND pf.feature_id = f.id
  AND p.code IN ('BASIC', 'PRO', 'PREMIUM') AND f.code = 'STAFF_MANAGEMENT'
  AND pf.limits = '{}'::jsonb;

-- Future activations of standard plans advertise menu viewing, not ordering.
-- Feature definitions and historical snapshots remain available for compatibility.
DELETE FROM package_features pf
USING packages p, features f
WHERE pf.package_id = p.id AND pf.feature_id = f.id
  AND p.code IN ('BASIC', 'PRO', 'PREMIUM')
  AND f.code IN ('QR_STATIC_ORDER', 'QR_TABLE_ORDER');
