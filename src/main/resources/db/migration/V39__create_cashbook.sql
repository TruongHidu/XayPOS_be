-- Cashbook reads successful payment facts directly; only expense vouchers are persisted here.
-- A legacy receipt has no recoverable currency fact: leave every existing row NULL.
ALTER TABLE stock_documents ADD COLUMN currency_code CHAR(3);
ALTER TABLE stock_documents ADD CONSTRAINT ck_stock_document_currency
    CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$');

CREATE FUNCTION guard_stock_document_currency() RETURNS trigger AS $$
BEGIN
    IF NEW.currency_code IS DISTINCT FROM OLD.currency_code THEN
        RAISE EXCEPTION 'Stock document currency snapshot is immutable'
            USING ERRCODE='23514', CONSTRAINT='ck_stock_document_currency_immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER stock_document_currency_guard BEFORE UPDATE ON stock_documents
    FOR EACH ROW EXECUTE FUNCTION guard_stock_document_currency();
CREATE INDEX ix_stock_receipt_confirmed ON stock_documents(restaurant_id,confirmed_at DESC,id)
    WHERE document_type='RECEIPT' AND status='CONFIRMED';

CREATE TABLE cashbook_categories (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(50) NOT NULL UNIQUE CHECK (btrim(code) <> ''),
    name VARCHAR(150) NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('INCOME','EXPENSE')),
    active BOOLEAN NOT NULL DEFAULT true
);
INSERT INTO cashbook_categories(code,name,type) VALUES
    ('SALES_COLLECTION','Thu bán hàng','INCOME'),
    ('INGREDIENT_PURCHASE','Mua nguyên liệu','EXPENSE'),
    ('RENT','Thuê mặt bằng','EXPENSE'),
    ('SALARY','Lương','EXPENSE'),
    ('UTILITIES','Điện nước','EXPENSE'),
    ('MAINTENANCE','Bảo trì/sửa chữa','EXPENSE'),
    ('OTHER','Chi khác','EXPENSE');

CREATE TABLE cashbook_expenses (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL REFERENCES restaurants(id),
    expense_code VARCHAR(50) NOT NULL CHECK (btrim(expense_code) <> ''),
    category_id UUID NOT NULL REFERENCES cashbook_categories(id),
    amount NUMERIC(18,2) NOT NULL CHECK (amount > 0 AND amount <> 'NaN'::numeric),
    currency_code CHAR(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    payment_method VARCHAR(20) NOT NULL CHECK (payment_method IN ('CASH','TRANSFER')),
    expense_at TIMESTAMPTZ NOT NULL,
    stock_document_id UUID,
    source_document_code VARCHAR(50),
    source_currency_code CHAR(3) CHECK (source_currency_code IS NULL OR source_currency_code ~ '^[A-Z]{3}$'),
    reference_no VARCHAR(100),
    payee_name VARCHAR(150),
    note TEXT,
    status VARCHAR(10) NOT NULL DEFAULT 'POSTED' CHECK (status IN ('POSTED','VOIDED')),
    idempotency_key VARCHAR(100) NOT NULL CHECK (btrim(idempotency_key) <> ''),
    request_fingerprint CHAR(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    created_by UUID NOT NULL REFERENCES users(id),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    voided_by UUID REFERENCES users(id),
    voided_at TIMESTAMPTZ,
    void_reason VARCHAR(1000),
    CONSTRAINT uq_cashbook_expense_code UNIQUE(restaurant_id,expense_code),
    CONSTRAINT uq_cashbook_expense_idempotency UNIQUE(restaurant_id,idempotency_key),
    CONSTRAINT uq_cashbook_expense_tenant_id UNIQUE(restaurant_id,id),
    CONSTRAINT fk_cashbook_expense_receipt FOREIGN KEY(restaurant_id,stock_document_id)
        REFERENCES stock_documents(restaurant_id,id),
    CONSTRAINT ck_cashbook_expense_void CHECK (
        (status='POSTED' AND voided_by IS NULL AND voided_at IS NULL AND void_reason IS NULL)
        OR (status='VOIDED' AND voided_by IS NOT NULL AND voided_at IS NOT NULL
            AND void_reason IS NOT NULL AND btrim(void_reason) <> '')
    ),
    CONSTRAINT ck_cashbook_expense_source CHECK (
        (stock_document_id IS NULL AND source_document_code IS NULL AND source_currency_code IS NULL)
        OR (stock_document_id IS NOT NULL AND source_document_code IS NOT NULL)
    )
);
CREATE INDEX ix_cashbook_expense_history ON cashbook_expenses(restaurant_id,expense_at DESC,id);
CREATE INDEX ix_cashbook_expense_receipt ON cashbook_expenses(restaurant_id,stock_document_id)
    WHERE stock_document_id IS NOT NULL;

CREATE FUNCTION guard_cashbook_expense_history() RETURNS trigger AS $$
DECLARE category cashbook_categories%ROWTYPE;
DECLARE receipt stock_documents%ROWTYPE;
BEGIN
    IF TG_OP='DELETE' THEN
        RAISE EXCEPTION 'Cashbook expense history cannot be deleted'
            USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_immutable';
    END IF;
    IF TG_OP='INSERT' THEN
        IF NEW.status <> 'POSTED' THEN
            RAISE EXCEPTION 'A new expense must be POSTED'
                USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_transition';
        END IF;
        SELECT * INTO category FROM cashbook_categories WHERE id=NEW.category_id;
        IF NOT FOUND OR category.type <> 'EXPENSE' OR NOT category.active THEN
            RAISE EXCEPTION 'Expense category is invalid'
                USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_category';
        END IF;
        IF NEW.stock_document_id IS NOT NULL THEN
            SELECT * INTO receipt FROM stock_documents
                WHERE restaurant_id=NEW.restaurant_id AND id=NEW.stock_document_id;
            IF NOT FOUND OR receipt.document_type <> 'RECEIPT' OR receipt.status <> 'CONFIRMED'
                    OR category.code <> 'INGREDIENT_PURCHASE'
                    OR NEW.source_document_code IS DISTINCT FROM receipt.document_code
                    OR NEW.source_currency_code IS DISTINCT FROM receipt.currency_code
                    OR (receipt.currency_code IS NOT NULL AND NEW.currency_code <> receipt.currency_code) THEN
                RAISE EXCEPTION 'Expense receipt linkage is invalid'
                    USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_receipt';
            END IF;
        END IF;
        RETURN NEW;
    END IF;
    IF (to_jsonb(NEW) - ARRAY['status','voided_by','voided_at','void_reason','updated_at','version'])
            IS DISTINCT FROM
       (to_jsonb(OLD) - ARRAY['status','voided_by','voided_at','void_reason','updated_at','version']) THEN
        RAISE EXCEPTION 'Cashbook expense financial fields are immutable'
            USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_immutable';
    END IF;
    IF OLD.status <> 'POSTED' OR NEW.status <> 'VOIDED' THEN
        RAISE EXCEPTION 'Cashbook expenses only transition from POSTED to VOIDED'
            USING ERRCODE='23514', CONSTRAINT='ck_cashbook_expense_transition';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER cashbook_expense_history_guard BEFORE INSERT OR UPDATE OR DELETE ON cashbook_expenses
    FOR EACH ROW EXECUTE FUNCTION guard_cashbook_expense_history();

INSERT INTO permissions(code,module,name) VALUES
    ('CASHBOOK_VIEW','CASHBOOK','View cashbook'),
    ('CASHBOOK_CREATE','CASHBOOK','Record paid expenses'),
    ('CASHBOOK_VOID','CASHBOOK','Void incorrectly recorded expenses')
ON CONFLICT (code) DO NOTHING;
INSERT INTO role_permissions(role_id,permission_id)
SELECT r.id,p.id FROM roles r CROSS JOIN permissions p
WHERE r.restaurant_id IS NULL AND r.is_system AND r.code='OWNER'
    AND p.code IN ('CASHBOOK_VIEW','CASHBOOK_CREATE','CASHBOOK_VOID')
ON CONFLICT (role_id,permission_id) DO NOTHING;
