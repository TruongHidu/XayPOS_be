package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class FlywayCleanMigrationIntegrationTest {

    private static final String TEST_SCHEMA_PREFIX = "it_flyway_";

    @Autowired
    private DataSource dataSource;

    @Test
    void migratesAllVersionsOnAnEmptyPostgresqlSchema() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("it_flyway_[0-9a-f]{32}");

        createSchema(schema);
        try {
            Flyway flyway = Flyway.configure()
                    .dataSource(dataSource)
                    .schemas(schema)
                    .defaultSchema(schema)
                    .locations("classpath:db/migration")
                    .load();

            flyway.migrate();

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");
            assertThat(countTables(schema)).isEqualTo(27);
            assertThat(tableExists(schema, "qr_table_access")).isTrue();
            assertThat(tableExists(schema, "qr_guest_authorizations")).isTrue();
            assertThat(tableExists(schema, "qr_order_requests")).isTrue();
            assertThat(tableExists(schema, "qr_order_request_items")).isTrue();
            assertThat(tableExists(schema, "payments")).isTrue();
            assertThat(tableExists(schema, "table_session_transfers")).isTrue();
            assertThat(tableExists(schema, "order_item_submissions")).isTrue();
            assertThat(tableExists(schema, "orders")).isTrue();
            assertThat(tableExists(schema, "order_items")).isTrue();
            assertThat(tableExists(schema, "order_item_status_history")).isTrue();
            assertThat(tableExists(schema, "table_areas")).isTrue();
            assertThat(tableExists(schema, "restaurant_tables")).isTrue();
            assertThat(tableExists(schema, "table_sessions")).isTrue();
            assertThat(tableExists(schema, "items")).isTrue();
            assertThat(tableExists(schema, "item_groups")).isTrue();
            assertThat(tableExists(schema, "restaurants")).isTrue();
            assertThat(tableExists(schema, "restaurant_subscriptions")).isTrue();
            assertThat(tableExists(schema, "audit_logs")).isTrue();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(r.code,',' ORDER BY r.code) FROM \"" + schema
                     + "\".roles r JOIN \"" + schema + "\".role_permissions rp ON rp.role_id=r.id JOIN \""
                     + schema + "\".permissions p ON p.id=rp.permission_id WHERE p.code='ORDER_COMPLETE'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CASHIER,MANAGER,OWNER,WAITER");
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT p.code, p.max_staff FROM \"" + schema
                     + "\".packages p JOIN \"" + schema + "\".package_features pf ON pf.package_id=p.id JOIN \""
                     + schema + "\".features f ON f.id=pf.feature_id WHERE f.code='STAFF_MANAGEMENT' ORDER BY p.code")) {
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("BASIC"); assertThat(rows.getString(2)).isEqualTo("3");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PREMIUM"); assertThat(rows.getString(2)).isEqualTo("30");
                assertThat(rows.next()).isTrue(); assertThat(rows.getString(1)).isEqualTo("PRO"); assertThat(rows.getString(2)).isEqualTo("10");
                assertThat(rows.next()).isFalse();
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v8FailsClearlyOnDuplicatePendingWithoutDeletingHistory() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("7").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO \"" + schema + "\".restaurants (id, code, name, status) "
                    + "VALUES ('00000000-0000-0000-0000-000000000001', 'V8_DUPLICATE', 'V8 duplicate fixture', 'ACTIVE')");
                statement.execute("INSERT INTO \"" + schema + "\".restaurant_subscriptions "
                    + "(restaurant_id, package_id, status, start_at, end_at, price_amount, currency_code) "
                    + "SELECT '00000000-0000-0000-0000-000000000001'::uuid, p.id, 'PENDING', now(), "
                    + "now() + interval '1 day', 0, 'VND' FROM \"" + schema + "\".packages p "
                    + "CROSS JOIN generate_series(1, 2) WHERE p.code = 'BASIC'");
            }
            Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("V8: duplicate PENDING subscriptions");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + schema
                     + "\".restaurant_subscriptions WHERE status = 'PENDING'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(2);
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v11PreservesCustomLimitsAndHistoricalSnapshotsWhileMigratingEmptyDefaults() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        createSchema(schema);
        String prefix = "\"" + schema + "\".";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("10").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "package_features(package_id,feature_id,limits) "
                    + "SELECT p.id,f.id,'{\"maxStaff\":7,\"custom\":true}'::jsonb FROM " + prefix + "packages p," + prefix
                    + "features f WHERE p.code='BASIC' AND f.code='STAFF_MANAGEMENT'");
                statement.execute("UPDATE " + prefix + "package_features SET limits='{\"custom\":true}'::jsonb WHERE package_id="
                    + "(SELECT id FROM " + prefix + "packages WHERE code='PREMIUM') AND feature_id=(SELECT id FROM "
                    + prefix + "features WHERE code='STAFF_MANAGEMENT')");
                statement.execute("INSERT INTO " + prefix + "restaurants(code,name,status) VALUES('OLD','Old tenant','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "restaurant_subscriptions(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot) "
                    + "SELECT r.id,p.id,'ACTIVE',now(),now()+interval '1 day',0,'VND','{\"features\":[{\"code\":\"QR_STATIC_ORDER\",\"limits\":{}}]}'::jsonb "
                    + "FROM " + prefix + "restaurants r," + prefix + "packages p WHERE p.code='BASIC'");
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("11").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT p.code,pf.limits::text FROM " + prefix + "packages p JOIN " + prefix
                    + "package_features pf ON pf.package_id=p.id JOIN " + prefix + "features f ON f.id=pf.feature_id "
                    + "WHERE f.code='STAFF_MANAGEMENT' ORDER BY p.code")) {
                    rows.next(); assertThat(rows.getString(2)).contains("\"maxStaff\": 7", "\"custom\": true");
                    rows.next(); assertThat(rows.getString(2)).isEqualTo("{\"custom\": true}");
                    rows.next(); assertThat(rows.getString(2)).isEqualTo("{\"maxStaff\": 10}");
                }
                try (ResultSet rows = statement.executeQuery("SELECT feature_snapshot::text FROM " + prefix + "restaurant_subscriptions")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("{\"features\": [{\"code\": \"QR_STATIC_ORDER\", \"limits\": {}}]}");
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features pf JOIN "
                    + prefix + "features f ON f.id=pf.feature_id WHERE f.code IN ('QR_STATIC_ORDER','QR_TABLE_ORDER')")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "features WHERE code IN ('QR_STATIC_ORDER','QR_TABLE_ORDER')")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(2);
                }
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v12MovesCustomLimitButPreservesOtherLimitsAndHistoricalSnapshot() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("11").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("UPDATE " + prefix + "package_features SET limits='{\"maxStaff\":7,\"custom\":true}'::jsonb "
                    + "WHERE package_id=(SELECT id FROM " + prefix + "packages WHERE code='BASIC') "
                    + "AND feature_id=(SELECT id FROM " + prefix + "features WHERE code='STAFF_MANAGEMENT')");
                statement.execute("UPDATE " + prefix + "package_features SET limits='{}'::jsonb "
                    + "WHERE package_id=(SELECT id FROM " + prefix + "packages WHERE code='PREMIUM')");
                statement.execute("INSERT INTO " + prefix + "restaurants(code,name,status) VALUES('LEGACY','Legacy','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "restaurant_subscriptions(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot) "
                    + "SELECT r.id,p.id,'ACTIVE',now(),now()+interval '1 day',0,'VND',"
                    + "'{\"schemaVersion\":1,\"features\":[{\"code\":\"STAFF_MANAGEMENT\",\"limits\":{\"maxStaff\":5}}]}'::jsonb "
                    + "FROM " + prefix + "restaurants r," + prefix + "packages p WHERE p.code='BASIC'");
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT max_staff FROM " + prefix + "packages WHERE code='BASIC'")) {
                    rows.next(); assertThat(rows.getLong(1)).isEqualTo(7);
                }
                try (ResultSet rows = statement.executeQuery("SELECT max_staff FROM " + prefix + "packages WHERE code='PREMIUM'")) {
                    rows.next(); assertThat(rows.getObject(1)).isNull();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features WHERE limits ? 'maxStaff'")) {
                    rows.next(); assertThat(rows.getInt(1)).isZero();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features WHERE limits='{\"custom\":true}'::jsonb")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
                try (ResultSet rows = statement.executeQuery("SELECT feature_snapshot="
                    + "'{\"schemaVersion\":1,\"features\":[{\"code\":\"STAFF_MANAGEMENT\",\"limits\":{\"maxStaff\":5}}]}'::jsonb "
                    + "FROM " + prefix + "restaurant_subscriptions")) {
                    rows.next(); assertThat(rows.getBoolean(1)).isTrue();
                }
                assertThatThrownBy(() -> statement.execute("UPDATE " + prefix + "packages SET max_staff=0 WHERE code='BASIC'"))
                    .isInstanceOf(java.sql.SQLException.class).hasMessageContaining("ck_packages_max_staff");
            }
        } finally { dropTestSchema(schema); }
    }

    @Test
    void v12RejectsMalformedLegacyLimitWithoutDiscardingIt() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("11").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("UPDATE " + prefix + "package_features SET limits='{\"maxStaff\":\"bad\"}'::jsonb "
                    + "WHERE feature_id=(SELECT id FROM " + prefix + "features WHERE code='STAFF_MANAGEMENT')");
            }
            Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("V12: invalid legacy maxStaff");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features WHERE limits->>'maxStaff'='bad'")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(3);
            }
        } finally { dropTestSchema(schema); }
    }

    private void createSchema(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
    }

    @Test
    void v19RejectsDuplicateServingOrdersWithoutChangingData() throws Exception {
        String schema=TEST_SCHEMA_PREFIX+UUID.randomUUID().toString().replace("-","");
        createSchema(schema);
        String prefix="\""+schema+"\".";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("18").load().migrate();
            try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
                statement.execute("INSERT INTO "+prefix+"restaurants(id,code,name,status) VALUES('00000000-0000-0000-0000-000000000001','DUP19','Fixture','ACTIVE')");
                statement.execute("INSERT INTO "+prefix+"users(id,restaurant_id,role_id,email,password_hash,name) SELECT '00000000-0000-0000-0000-000000000004'::uuid,'00000000-0000-0000-0000-000000000001'::uuid,id,'v19@example.test','fixture','Fixture' FROM "+prefix+"roles WHERE code='OWNER' AND restaurant_id IS NULL");
                statement.execute("INSERT INTO "+prefix+"restaurant_tables(id,restaurant_id,code,name,status,qr_token,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','B1','Fixture','AVAILABLE','v19-test-token',now(),now())");
                statement.execute("INSERT INTO "+prefix+"table_sessions(id,restaurant_id,table_id,session_code,status,opened_by,opened_at,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','S1','OPEN','00000000-0000-0000-0000-000000000004',now(),now(),now())");
                statement.execute("INSERT INTO "+prefix+"orders(restaurant_id,table_session_id,order_code,service_type,source_channel,status,currency_code,created_at,updated_at,idempotency_key,request_hash) "
                    +"SELECT '00000000-0000-0000-0000-000000000001'::uuid,'00000000-0000-0000-0000-000000000003'::uuid,'DUP-'||i,'DINE_IN','WAITER','OPEN','VND',now(),now(),'key-'||i,repeat('a',64) FROM generate_series(1,2) i");
            }
            var upgrade=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("V19: duplicate serving orders per session");
            try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement();
                ResultSet rows=statement.executeQuery("SELECT count(*),count(*) FILTER(WHERE status='OPEN') FROM "+prefix+"orders")) {
                rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); assertThat(rows.getInt(2)).isEqualTo(2);
            }
        } finally { dropTestSchema(schema); }
    }

    @Test
    void v20PreservesV19SeatingOrdersQrAndPermissionOverrides() throws Exception {
        String schema=TEST_SCHEMA_PREFIX+UUID.randomUUID().toString().replace("-","");
        createSchema(schema);
        String prefix="\""+schema+"\".";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("19").load().migrate();
            java.util.Map<String,String> before=new java.util.LinkedHashMap<>();
            try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
                statement.execute("INSERT INTO "+prefix+"restaurants(id,code,name,status) VALUES('00000000-0000-0000-0000-000000000001','UP20','Fixture','ACTIVE')");
                statement.execute("INSERT INTO "+prefix+"users(id,restaurant_id,role_id,email,password_hash,name) SELECT '00000000-0000-0000-0000-000000000004'::uuid,'00000000-0000-0000-0000-000000000001'::uuid,id,'v20@example.test','fixture','Fixture' FROM "+prefix+"roles WHERE code='OWNER' AND restaurant_id IS NULL");
                statement.execute("INSERT INTO "+prefix+"restaurant_tables(id,restaurant_id,code,name,status,qr_token,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001','B1','Fixture','AVAILABLE','v20-preserved-token',now(),now())");
                statement.execute("INSERT INTO "+prefix+"table_sessions(id,restaurant_id,table_id,session_code,status,opened_by,opened_at,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000002','S1','OPEN','00000000-0000-0000-0000-000000000004',now(),now(),now())");
                statement.execute("INSERT INTO "+prefix+"orders(restaurant_id,table_session_id,order_code,service_type,source_channel,status,currency_code,created_at,updated_at,idempotency_key,request_hash) VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000003','O1','DINE_IN','WAITER','CONFIRMED','VND',now(),now(),'old-create-key',repeat('a',64))");
                // Existing custom permission/override must survive ON CONFLICT and default role grants.
                statement.execute("INSERT INTO "+prefix+"permissions(code,module,name) VALUES('TABLE_TRANSFER','TABLE','Existing custom permission')");
                statement.execute("INSERT INTO "+prefix+"user_permissions(user_id,permission_id,effect) SELECT '00000000-0000-0000-0000-000000000004'::uuid,id,'DENY' FROM "+prefix+"permissions WHERE code='TABLE_TRANSFER'");
                statement.execute("INSERT INTO "+prefix+"restaurant_subscriptions(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot) SELECT '00000000-0000-0000-0000-000000000001'::uuid,id,'ACTIVE',now(),now()+interval '1 day',0,'VND','{\"features\":[]}'::jsonb FROM "+prefix+"packages WHERE code='PRO'");
                for(String table:java.util.List.of("restaurant_tables","table_sessions","orders","user_permissions","packages","package_features","restaurant_subscriptions"))
                    before.put(table,tableSnapshot(statement,prefix,table));
            }
            var flyway=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            flyway.migrate(); flyway.validate();
            try(Connection connection=dataSource.getConnection(); Statement statement=connection.createStatement()) {
                for(var entry:before.entrySet()) assertThat(tableSnapshot(statement,prefix,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                try(ResultSet rows=statement.executeQuery("SELECT count(*) FROM "+prefix+"table_session_transfers")) { rows.next(); assertThat(rows.getLong(1)).isZero(); }
                try(ResultSet rows=statement.executeQuery("SELECT string_agg(r.code,',' ORDER BY r.code) FROM "+prefix+"roles r JOIN "+prefix+"role_permissions rp ON rp.role_id=r.id JOIN "+prefix+"permissions p ON p.id=rp.permission_id WHERE p.code='TABLE_TRANSFER'")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("MANAGER,OWNER,WAITER");
                }
            }
        } finally { dropTestSchema(schema); }
    }

    @Test
    void v23AddsOnlySystemBulkServingGrantsAndPreservesOverrides() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("22").load().migrate();
            var before = new java.util.LinkedHashMap<String, String>();
            String unrelatedGrants;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES "
                    + "('00000000-0000-0000-0000-000000000001','UP23','Fixture','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "roles(restaurant_id,code,name,is_system) "
                    + "SELECT '00000000-0000-0000-0000-000000000001'::uuid,code,'Custom '||code,false "
                    + "FROM " + prefix + "roles WHERE restaurant_id IS NULL AND code IN ('CASHIER','KITCHEN')");
                statement.execute("INSERT INTO " + prefix + "role_permissions(role_id,permission_id) "
                    + "SELECT r.id,p.id FROM " + prefix + "roles r CROSS JOIN " + prefix + "permissions p "
                    + "WHERE r.restaurant_id IS NOT NULL AND p.code='MENU_VIEW'");
                statement.execute("INSERT INTO " + prefix + "users(id,restaurant_id,role_id,email,password_hash,name) "
                    + "SELECT '00000000-0000-0000-0000-000000000004'::uuid,"
                    + "'00000000-0000-0000-0000-000000000001'::uuid,id,'v23@example.test','fixture','Fixture' "
                    + "FROM " + prefix + "roles WHERE restaurant_id IS NULL AND code='CASHIER'");
                statement.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) "
                    + "SELECT '00000000-0000-0000-0000-000000000004'::uuid,id,"
                    + "CASE WHEN code='KITCHEN_MARK_READY' THEN 'DENY' ELSE 'GRANT' END "
                    + "FROM " + prefix + "permissions WHERE code IN ('KITCHEN_MARK_READY','MENU_UPDATE')");
                for (String table : java.util.List.of("roles", "permissions", "users", "user_permissions",
                        "restaurants", "packages", "package_features", "restaurant_subscriptions"))
                    before.put(table, tableSnapshot(statement, prefix, table));
                unrelatedGrants = unrelatedServingGrants(statement, prefix);
            }
            var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("23").load();
            flyway.migrate();
            flyway.validate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (var entry : before.entrySet())
                    assertThat(tableSnapshot(statement, prefix, entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                assertThat(unrelatedServingGrants(statement, prefix)).isEqualTo(unrelatedGrants);
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "role_permissions rp "
                        + "JOIN " + prefix + "roles r ON r.id=rp.role_id JOIN " + prefix + "permissions p ON p.id=rp.permission_id "
                        + "WHERE r.restaurant_id IS NULL AND r.is_system AND r.code IN ('CASHIER','KITCHEN') "
                        + "AND p.code IN ('KITCHEN_MARK_READY','ORDER_MARK_SERVED')")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(4);
                }
                String allGrants = tableSnapshot(statement, prefix, "role_permissions");
                String originalSchema = connection.getSchema();
                connection.setSchema(schema);
                try (var input = getClass().getResourceAsStream("/db/migration/V23__grant_bulk_order_serving_permissions.sql")) {
                    String sql = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    statement.execute(sql);
                    statement.execute(sql);
                } finally { connection.setSchema(originalSchema); }
                assertThat(tableSnapshot(statement, prefix, "role_permissions")).isEqualTo(allGrants);
            }
        } finally { dropTestSchema(schema); }
    }

    @Test
    void v24GrantsOnlySystemCashierManagerClosureAndPreservesLegacyData() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("23").load().migrate();
            var before = new java.util.LinkedHashMap<String, String>();
            String unrelatedGrants;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES "
                    + "('00000000-0000-0000-0000-000000000001','UP24','Fixture','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "roles(restaurant_id,code,name,is_system) "
                    + "SELECT '00000000-0000-0000-0000-000000000001'::uuid,code,'Custom '||code,false "
                    + "FROM " + prefix + "roles WHERE restaurant_id IS NULL AND code IN ('CASHIER','MANAGER')");
                statement.execute("INSERT INTO " + prefix + "role_permissions(role_id,permission_id) "
                    + "SELECT r.id,p.id FROM " + prefix + "roles r CROSS JOIN " + prefix + "permissions p "
                    + "WHERE r.restaurant_id IS NOT NULL AND p.code='MENU_VIEW'");
                statement.execute("INSERT INTO " + prefix + "users(id,restaurant_id,role_id,email,password_hash,name) "
                    + "SELECT '00000000-0000-0000-0000-000000000004'::uuid,"
                    + "'00000000-0000-0000-0000-000000000001'::uuid,id,'v24@example.test','fixture','Fixture' "
                    + "FROM " + prefix + "roles WHERE restaurant_id IS NULL AND code='CASHIER'");
                statement.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) "
                    + "SELECT '00000000-0000-0000-0000-000000000004'::uuid,id,"
                    + "CASE WHEN code='TABLE_CLOSE' THEN 'DENY' ELSE 'GRANT' END "
                    + "FROM " + prefix + "permissions WHERE code IN ('TABLE_CLOSE','ORDER_MARK_SERVED')");
                statement.execute("INSERT INTO " + prefix + "restaurant_tables(id,restaurant_id,code,name,status,qr_token,created_at,updated_at) "
                    + "VALUES('00000000-0000-0000-0000-000000000002','00000000-0000-0000-0000-000000000001',"
                    + "'B1','Fixture','AVAILABLE','v24-preserved-token',now(),now())");
                statement.execute("INSERT INTO " + prefix + "table_sessions(id,restaurant_id,table_id,session_code,status,opened_by,opened_at,created_at,updated_at) "
                    + "VALUES('00000000-0000-0000-0000-000000000003','00000000-0000-0000-0000-000000000001',"
                    + "'00000000-0000-0000-0000-000000000002','S1','OPEN','00000000-0000-0000-0000-000000000004',now(),now(),now())");
                statement.execute("INSERT INTO " + prefix + "orders(id,restaurant_id,table_session_id,order_code,service_type,source_channel,status,"
                    + "payment_status,currency_code,subtotal_amount,total_amount,paid_amount,completed_at,created_at,updated_at,idempotency_key,request_hash) "
                    + "VALUES('00000000-0000-0000-0000-000000000005','00000000-0000-0000-0000-000000000001',"
                    + "'00000000-0000-0000-0000-000000000003','O1','DINE_IN','CASHIER','COMPLETED','PAID','VND',"
                    + "100,100,100,now(),now(),now(),'v24-old-key',repeat('a',64))");
                statement.execute("INSERT INTO " + prefix + "order_items(id,restaurant_id,order_id,item_name,unit,quantity,unit_price,line_total,status,"
                    + "line_number,sent_to_kitchen_at,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000006',"
                    + "'00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000005','Snapshot','bowl',"
                    + "1,100,100,'SERVED',1,now(),now(),now())");
                statement.execute("INSERT INTO " + prefix + "order_item_status_history(restaurant_id,order_item_id,from_status,to_status,changed_by,changed_at,change_sequence) "
                    + "VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000006','READY','SERVED',"
                    + "'00000000-0000-0000-0000-000000000004',now(),1)");
                statement.execute("INSERT INTO " + prefix + "audit_logs(restaurant_id,actor_user_id,action_code,entity_type,entity_id) "
                    + "VALUES('00000000-0000-0000-0000-000000000001','00000000-0000-0000-0000-000000000004','ORDER_COMPLETED',"
                    + "'orders','00000000-0000-0000-0000-000000000005')");
                for (String table : java.util.List.of("roles", "permissions", "users", "user_permissions", "restaurants",
                        "restaurant_tables", "table_sessions", "orders", "order_items", "order_item_status_history",
                        "audit_logs", "packages", "package_features", "restaurant_subscriptions"))
                    before.put(table, tableSnapshot(statement, prefix, table));
                unrelatedGrants = unrelatedCompletionGrants(statement, prefix);
            }
            var flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();
            flyway.migrate();
            flyway.validate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                for (var entry : before.entrySet())
                    assertThat(tableSnapshot(statement, prefix, entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                assertThat(unrelatedCompletionGrants(statement, prefix)).isEqualTo(unrelatedGrants);
                try (ResultSet rows = statement.executeQuery("SELECT string_agg(r.code,',' ORDER BY r.code) FROM " + prefix
                        + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id JOIN " + prefix
                        + "permissions p ON p.id=rp.permission_id WHERE r.restaurant_id IS NULL AND r.is_system "
                        + "AND r.code IN ('CASHIER','MANAGER') AND p.code='TABLE_CLOSE'")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo("CASHIER,MANAGER");
                }
                String allGrants = tableSnapshot(statement, prefix, "role_permissions");
                String originalSchema = connection.getSchema();
                connection.setSchema(schema);
                try (var input = getClass().getResourceAsStream("/db/migration/V24__grant_order_completion_table_close.sql")) {
                    String sql = new String(java.util.Objects.requireNonNull(input).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    statement.execute(sql);
                    statement.execute(sql);
                } finally { connection.setSchema(originalSchema); }
                assertThat(tableSnapshot(statement, prefix, "role_permissions")).isEqualTo(allGrants);
            }
        } finally { dropTestSchema(schema); }
    }

    private String unrelatedCompletionGrants(Statement statement, String prefix) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(rp) ORDER BY to_jsonb(rp)::text),'[]'::jsonb)::text "
                + "FROM " + prefix + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id "
                + "JOIN " + prefix + "permissions p ON p.id=rp.permission_id WHERE NOT "
                + "(r.restaurant_id IS NULL AND r.is_system AND r.code IN ('CASHIER','MANAGER') AND p.code='TABLE_CLOSE')")) {
            rows.next(); return rows.getString(1);
        }
    }

    private String unrelatedServingGrants(Statement statement, String prefix) throws Exception {
        try (ResultSet rows = statement.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(rp) ORDER BY to_jsonb(rp)::text),'[]'::jsonb)::text "
                + "FROM " + prefix + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id "
                + "JOIN " + prefix + "permissions p ON p.id=rp.permission_id WHERE NOT "
                + "(r.restaurant_id IS NULL AND r.is_system AND r.code IN ('CASHIER','KITCHEN') "
                + "AND p.code IN ('KITCHEN_MARK_READY','ORDER_MARK_SERVED'))")) {
            rows.next(); return rows.getString(1);
        }
    }

    private String tableSnapshot(Statement statement,String prefix,String table) throws Exception {
        try(ResultSet rows=statement.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "+prefix+table+" t")) {
            rows.next(); return rows.getString(1);
        }
    }

    private int countTables(String schema) throws Exception {
        String sql = "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_type = 'BASE TABLE' "
                + "AND table_name <> 'flyway_schema_history'";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1);
            }
        }
    }

    private boolean tableExists(String schema, String table) throws Exception {
        String sql = "SELECT EXISTS (SELECT 1 FROM information_schema.tables "
                + "WHERE table_schema = ? AND table_name = ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getBoolean(1);
            }
        }
    }

    private void dropTestSchema(String schema) throws Exception {
        if (!schema.startsWith(TEST_SCHEMA_PREFIX)
                || !schema.matches("it_flyway_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to drop a non-test schema: " + schema);
        }
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
        }
    }
}
