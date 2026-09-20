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

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
            assertThat(countTables(schema)).isEqualTo(12);
            assertThat(tableExists(schema, "restaurants")).isTrue();
            assertThat(tableExists(schema, "restaurant_subscriptions")).isTrue();
            assertThat(tableExists(schema, "audit_logs")).isTrue();
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

    private void createSchema(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
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
