package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ReportIndexesMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void v27AddsOnlyReportIndexesAndPreservesBusinessRows() throws Exception {
        String schema = "it_report_index_" + UUID.randomUUID().toString().replace("-", "");
        assertThat(schema).matches("it_report_index_[0-9a-f]{32}");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("26").load().migrate();
            String prefix = "\"" + schema + "\".";
            String before;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                before = snapshot(statement, prefix);
            }

            var upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("27").load();
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("27");

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThat(snapshot(statement, prefix)).isEqualTo(before);
                try (ResultSet rows = statement.executeQuery("SELECT string_agg(indexname,',' ORDER BY indexname) FROM pg_indexes "
                    + "WHERE schemaname='" + schema + "' AND indexname LIKE 'ix_report_%'")) {
                    rows.next();
                    assertThat(rows.getString(1)).isEqualTo(
                        "ix_report_order_cancelled,ix_report_order_completed,ix_report_order_created,ix_report_payment_success_paid"
                    );
                }
            }
        } finally {
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private static String snapshot(Statement statement, String prefix) throws Exception {
        String sql = "SELECT jsonb_build_object(" +
            "'restaurants',(SELECT count(*) FROM " + prefix + "restaurants)," +
            "'orders',(SELECT count(*) FROM " + prefix + "orders)," +
            "'payments',(SELECT count(*) FROM " + prefix + "payments)," +
            "'subscriptions',(SELECT count(*) FROM " + prefix + "restaurant_subscriptions)," +
            "'packageFeatures',(SELECT count(*) FROM " + prefix + "package_features))::text";
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
