package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

class CashbookMigrationIntegrationTest {
    @Test
    void v39PreservesLegacyReceiptsAndProtectsExpenseHistory() throws Exception {
        String schema = "it_cashbook_migration_" + UUID.randomUUID().toString().replace("-", "");
        String base = required("DB_URL"), user = required("DB_USERNAME"), password = required("DB_PASSWORD");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + schema)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        String tenant = UUID.randomUUID().toString(), actor = UUID.randomUUID().toString();
        String warehouse = UUID.randomUUID().toString(), receipt = UUID.randomUUID().toString();
        String expense = UUID.randomUUID().toString();
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("38").load().migrate();
            String before;
            try (Connection c = DriverManager.getConnection(url,user,password); Statement s=c.createStatement()) {
                s.execute("INSERT INTO restaurants(id,code,name,status) VALUES('"+tenant+"','CBM_"+tenant+"','Cashbook migration','ACTIVE')");
                s.execute("INSERT INTO users(id,restaurant_id,role_id,name,email,password_hash) SELECT '"+actor+"','"+tenant+"',id,'Owner','"+actor+"@example.test','x' FROM roles WHERE restaurant_id IS NULL AND code='OWNER'");
                s.execute("INSERT INTO warehouses(id,restaurant_id,code,name,is_default,is_active,created_at,updated_at) VALUES('"+warehouse+"','"+tenant+"','MAIN','Main',true,true,now(),now())");
                s.execute("INSERT INTO stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,idempotency_key,creation_request_hash,total_cost,created_by,confirmed_by,confirmed_at,created_at,updated_at) VALUES('"+receipt+"','"+tenant+"','"+warehouse+"','OLD-RECEIPT','RECEIPT','CONFIRMED','old-key','"+"a".repeat(64)+"',100,'"+actor+"','"+actor+"',now(),now(),now())");
                before = value(s,"SELECT to_jsonb(d)::text FROM stock_documents d WHERE id='"+receipt+"'");
            }
            var migration=Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("39").load();
            assertThat(migration.migrate().migrationsExecuted).isEqualTo(1);
            migration.validate();
            try (Connection c=DriverManager.getConnection(url,user,password); Statement s=c.createStatement()) {
                assertThat(value(s,"SELECT (to_jsonb(d)-'currency_code')::text FROM stock_documents d WHERE id='"+receipt+"'")).isEqualTo(before);
                assertThat(value(s,"SELECT currency_code IS NULL FROM stock_documents WHERE id='"+receipt+"'")).isEqualTo("t");
                assertThat(value(s,"SELECT count(*) FROM cashbook_categories")).isEqualTo("7");
                assertThat(value(s,"SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id=rp.role_id JOIN permissions p ON p.id=rp.permission_id WHERE r.restaurant_id IS NULL AND r.code='OWNER' AND p.module='CASHBOOK'")).isEqualTo("3");
                assertThat(value(s,"SELECT count(*) FROM role_permissions rp JOIN roles r ON r.id=rp.role_id JOIN permissions p ON p.id=rp.permission_id WHERE r.code IN ('CASHIER','WAITER','KITCHEN') AND p.module='CASHBOOK'")).isEqualTo("0");
                s.execute("INSERT INTO cashbook_expenses(id,restaurant_id,expense_code,category_id,amount,currency_code,payment_method,expense_at,stock_document_id,source_document_code,idempotency_key,request_fingerprint,created_by,created_at,updated_at) SELECT '"+expense+"','"+tenant+"','EXP-1',id,10,'VND','CASH',now(),'"+receipt+"','OLD-RECEIPT','expense-key','"+"b".repeat(64)+"','"+actor+"',now(),now() FROM cashbook_categories WHERE code='INGREDIENT_PURCHASE'");
                assertThatThrownBy(()->s.execute("UPDATE cashbook_expenses SET amount=20 WHERE id='"+expense+"'")).hasMessageContaining("immutable");
                assertThatThrownBy(()->s.execute("DELETE FROM cashbook_expenses WHERE id='"+expense+"'")).hasMessageContaining("cannot be deleted");
                assertThatThrownBy(()->s.execute("UPDATE stock_documents SET currency_code='VND' WHERE id='"+receipt+"'")).hasMessageContaining("immutable");
                s.execute("UPDATE cashbook_expenses SET status='VOIDED',voided_by='"+actor+"',voided_at=now(),void_reason='Mistake',version=version+1 WHERE id='"+expense+"'");
                assertThat(value(s,"SELECT status FROM cashbook_expenses WHERE id='"+expense+"'")).isEqualTo("VOIDED");
                assertThatThrownBy(()->s.execute("UPDATE cashbook_expenses SET status='POSTED',voided_by=NULL,voided_at=NULL,void_reason=NULL WHERE id='"+expense+"'")).hasMessageContaining("transition");
            }
        } finally {
            if(!schema.matches("it_cashbook_migration_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected test schema");
            try(Connection c=DriverManager.getConnection(url,user,password);Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS \""+schema+"\" CASCADE");
            }
        }
    }
    private static String required(String key) {
        String value=System.getenv(key);
        if(value==null||value.isBlank()) throw new IllegalStateException(key+" must explicitly target a test database");
        return value;
    }
    private static String value(Statement s,String sql) throws Exception {
        try(var rows=s.executeQuery(sql)){rows.next();return rows.getString(1);}
    }
}
