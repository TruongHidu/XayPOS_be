package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class QrPackageFeaturesMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void v26AddsOnlyMissingStandardQrMappingsAndPreservesExistingData() throws Exception {
        String schema = "it_qr_packages_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"" + schema + "\"");
            try {
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("25").load().migrate();
                sql.execute("UPDATE " + prefix + "packages SET max_staff=7,price_amount=123456 WHERE code='BASIC'");
                sql.execute("INSERT INTO " + prefix + "packages(code,name,price_amount,billing_cycle_months,max_staff)"
                    + " VALUES('HAPPY','Custom Happy',100,1,23),('CUSTOM','Custom without QR',200,1,NULL)");
                sql.execute("INSERT INTO " + prefix + "package_features(package_id,feature_id,limits)"
                    + " SELECT p.id,f.id,'{\"custom\":true}'::jsonb FROM " + prefix + "packages p CROSS JOIN "
                    + prefix + "features f WHERE (p.code='BASIC' AND f.code='QR_TABLE_ORDER')"
                    + " OR (p.code='HAPPY' AND f.code IN ('QR_TABLE_ORDER','QR_STATIC_ORDER'))"
                    + " OR (p.code='CUSTOM' AND f.code='QR_MENU_VIEW')");
                // A catalog migration must not silently reactivate a feature disabled by admin.
                sql.execute("UPDATE " + prefix + "features SET is_active=false WHERE code='QR_STATIC_ORDER'");
                sql.execute("INSERT INTO " + prefix + "restaurants(code,name,status) VALUES('OLD','Existing','ACTIVE')");
                sql.execute("INSERT INTO " + prefix + "restaurant_subscriptions"
                    + "(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot)"
                    + " SELECT r.id,p.id,'ACTIVE',now(),now()+interval '1 day',199000,'VND',"
                    + "'{\"schemaVersion\":2,\"maxStaff\":3,\"features\":[{\"code\":\"QR_MENU_VIEW\",\"limits\":{}}]}'::jsonb"
                    + " FROM " + prefix + "restaurants r CROSS JOIN " + prefix + "packages p WHERE p.code='BASIC'");
                sql.execute("INSERT INTO " + prefix + "restaurant_subscriptions"
                    + "(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code)"
                    + " SELECT r.id,p.id,'PENDING',now(),now()+interval '1 day',399000,'VND'"
                    + " FROM " + prefix + "restaurants r CROSS JOIN " + prefix + "packages p WHERE p.code='PRO'");

                var before = new LinkedHashMap<String, String>();
                for (String table : List.of("packages", "features", "restaurant_subscriptions", "restaurants",
                        "roles", "permissions", "role_permissions")) {
                    before.put(table, snapshot(sql, prefix, table));
                }
                String mappingsBefore = value(sql, "SELECT jsonb_object_agg(package_id::text||':'||feature_id::text,"
                    + "to_jsonb(pf))::text FROM " + prefix + "package_features pf");
                int countBefore = Integer.parseInt(value(sql, "SELECT count(*) FROM " + prefix + "package_features"));

                Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("26").load();
                assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
                upgrade.validate();

                for (var entry : before.entrySet()) {
                    assertThat(snapshot(sql, prefix, entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                }
                assertThat(value(sql, "SELECT count(*) FROM " + prefix + "package_features pf JOIN " + prefix
                    + "packages p ON p.id=pf.package_id JOIN " + prefix + "features f ON f.id=pf.feature_id"
                    + " WHERE p.code IN ('BASIC','PRO','PREMIUM') AND f.code IN ('QR_TABLE_ORDER','QR_STATIC_ORDER')"))
                    .isEqualTo("6");
                assertThat(Integer.parseInt(value(sql, "SELECT count(*) FROM " + prefix + "package_features")))
                    .isEqualTo(countBefore + 5);
                // Existing custom limits, timestamps and custom-package mappings are unchanged.
                try (var check = connection.prepareStatement("SELECT jsonb_object_agg(package_id::text||':'||feature_id::text,"
                        + "to_jsonb(pf)) @> ?::jsonb FROM " + prefix + "package_features pf")) {
                    check.setString(1, mappingsBefore);
                    try (var rows = check.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getBoolean(1)).isTrue();
                    }
                }
                String mappingsAfter = snapshot(sql, prefix, "package_features");
                String originalSchema = connection.getSchema();
                connection.setSchema(schema);
                try (var input = getClass().getResourceAsStream("/db/migration/V26__enable_qr_ordering_for_standard_packages.sql")) {
                    String migration = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
                    sql.execute(migration);
                    sql.execute(migration);
                } finally {
                    connection.setSchema(originalSchema);
                }
                assertThat(snapshot(sql, prefix, "package_features")).isEqualTo(mappingsAfter);
                assertThat(upgrade.migrate().migrationsExecuted).isZero();
            } finally {
                if (!schema.matches("it_qr_packages_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
                sql.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private static String snapshot(Statement sql, String prefix, String table) throws Exception {
        return value(sql, "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
            + prefix + table + " t");
    }

    private static String value(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
