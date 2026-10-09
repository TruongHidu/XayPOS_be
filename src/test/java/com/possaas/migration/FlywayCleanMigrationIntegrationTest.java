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

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("19");
            assertThat(countTables(schema)).isEqualTo(21);
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
