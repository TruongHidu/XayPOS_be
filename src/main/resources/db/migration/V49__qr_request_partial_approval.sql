-- Partial approval: staff may approve a QR request while leaving some of its lines out (for example
-- an item that ran out). A left-out line keeps its submitted quote and carries the reason the guest sees.
-- A line is either linked to the order line it became, or rejected with a reason, never both.
ALTER TABLE qr_order_request_items
    ADD COLUMN rejected_at timestamptz,
    ADD COLUMN rejection_reason varchar(500);

ALTER TABLE qr_order_request_items
    ADD CONSTRAINT ck_qr_request_item_outcome CHECK (
        (rejected_at IS NULL AND rejection_reason IS NULL)
        OR (rejected_at IS NOT NULL AND rejection_reason IS NOT NULL AND btrim(rejection_reason) <> ''
            AND approved_order_item_id IS NULL));
