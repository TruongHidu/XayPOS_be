-- Owner-controlled switches for guest QR ordering, one per entry point. They sit on top of the
-- package entitlement (QR_TABLE_ORDER / QR_STATIC_ORDER): ordering is open only when both allow it.
-- Existing restaurants keep their current behaviour, so both start enabled.
ALTER TABLE restaurants
    ADD COLUMN qr_table_ordering_enabled boolean NOT NULL DEFAULT true,
    ADD COLUMN qr_takeaway_ordering_enabled boolean NOT NULL DEFAULT true;
