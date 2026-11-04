-- VietQR purpose of transaction (EMVCo tag 62-08) holds at most 25 characters.
-- PS + 20 HEX (80-bit random) keeps the whole transfer reference scannable; SePay suffix length is configured as 20.
DO $$
DECLARE c TEXT;
BEGIN
    IF EXISTS (SELECT 1 FROM subscription_payment_attempts WHERE reference_code !~ '^PS[0-9A-F]{20}$') THEN
        RAISE EXCEPTION 'V42: payment attempts with the V41 reference format exist; no records were changed';
    END IF;
    FOR c IN SELECT conname FROM pg_constraint
        WHERE conrelid = 'subscription_payment_attempts'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%reference_code%'
    LOOP
        EXECUTE format('ALTER TABLE subscription_payment_attempts DROP CONSTRAINT %I', c);
    END LOOP;
END $$;

ALTER TABLE subscription_payment_attempts
    ADD CONSTRAINT ck_spa_reference_format CHECK (reference_code ~ '^PS[0-9A-F]{20}$');
