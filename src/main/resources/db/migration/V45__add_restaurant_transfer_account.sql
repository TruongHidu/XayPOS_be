-- Bank account a restaurant receives customer transfers on. Shown as a VietQR code at checkout;
-- the code is display-only and staff still confirm each payment by hand.
ALTER TABLE restaurants
    ADD COLUMN transfer_bank_bin varchar(6),
    ADD COLUMN transfer_account_number varchar(19),
    ADD COLUMN transfer_account_name varchar(50);

ALTER TABLE restaurants
    ADD CONSTRAINT ck_restaurants_transfer_account_complete CHECK (
        (transfer_bank_bin IS NULL AND transfer_account_number IS NULL AND transfer_account_name IS NULL)
        OR (transfer_bank_bin IS NOT NULL AND transfer_account_number IS NOT NULL AND transfer_account_name IS NOT NULL)),
    ADD CONSTRAINT ck_restaurants_transfer_bank_bin CHECK (
        transfer_bank_bin IS NULL OR transfer_bank_bin ~ '^[0-9]{6}$'),
    ADD CONSTRAINT ck_restaurants_transfer_account_number CHECK (
        transfer_account_number IS NULL OR transfer_account_number ~ '^[0-9A-Za-z]{1,19}$'),
    ADD CONSTRAINT ck_restaurants_transfer_account_name CHECK (
        transfer_account_name IS NULL OR transfer_account_name ~ '^[A-Z0-9 ]{1,50}$');
