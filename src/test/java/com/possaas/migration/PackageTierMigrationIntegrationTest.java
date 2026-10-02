package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class PackageTierMigrationIntegrationTest {
    @Autowired DataSource dataSource;

    @Test
    void v13AlignsCatalogWithoutChangingExistingConfigurationOrSubscriptions() throws Exception {
        String schema = "it_tiers_" + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"" + schema + "\"");
            try {
                Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("12").load().migrate();
                sql.execute("UPDATE " + prefix + "packages SET max_staff=7 WHERE code='BASIC'");
                sql.execute("INSERT INTO " + prefix + "packages(code,name,price_amount,billing_cycle_months,max_staff)"
                    + " VALUES('CUSTOM','Custom',100,1,23)");
                sql.execute("INSERT INTO " + prefix + "package_features(package_id,feature_id,limits)"
                    + " SELECT p.id,f.id,'{\"custom\":true}'::jsonb FROM " + prefix + "packages p,"
                    + prefix + "features f WHERE p.code IN ('BASIC','CUSTOM') AND f.code='STAFF_PERMISSION'");
                sql.execute("INSERT INTO " + prefix + "restaurants(code,name,status) VALUES('OLD','Old','ACTIVE')");
                sql.execute("INSERT INTO " + prefix + "restaurant_subscriptions"
                    + "(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot)"
                    + " SELECT r.id,p.id,'ACTIVE',now(),now()+interval '1 day',199000,'VND',"
                    + "'{\"schemaVersion\":2,\"maxStaff\":3,\"features\":[]}'::jsonb"
                    + " FROM " + prefix + "restaurants r," + prefix + "packages p WHERE p.code='BASIC'");
                sql.execute("INSERT INTO " + prefix + "restaurant_subscriptions"
                    + "(restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code)"
                    + " SELECT r.id,p.id,'PENDING',now(),now()+interval '1 day',399000,'VND'"
                    + " FROM " + prefix + "restaurants r," + prefix + "packages p WHERE p.code='PRO'");

                String packagesBefore = value(sql, "SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM " + prefix + "packages p");
                String subscriptionsBefore = value(sql, "SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM " + prefix + "restaurant_subscriptions s");
                String mappingsBefore = value(sql, "SELECT jsonb_object_agg(package_id::text||':'||feature_id::text,to_jsonb(pf))::text FROM " + prefix + "package_features pf");
                int countBefore = Integer.parseInt(value(sql, "SELECT count(*) FROM " + prefix + "package_features"));

                Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").load();
                upgrade.migrate();

                Set<String> basic = codes(sql, prefix, "BASIC");
                assertThat(basic).containsExactlyInAnyOrder(
                    "MENU_MANAGEMENT", "POS_QUICK_ORDER", "ORDER_MANAGEMENT", "PAYMENT_MANAGEMENT",
                    "DAILY_REVENUE", "RECEIPT_PRINT", "RECEIPT_REPRINT", "TABLE_MANAGEMENT",
                    "STAFF_MANAGEMENT", "QR_MENU_VIEW", "STAFF_PERMISSION", "KITCHEN_DISPLAY",
                    "DETAIL_REPORT", "KITCHEN_TICKET_PRINT", "KITCHEN_TICKET_REPRINT");
                Set<String> expectedPro = new HashSet<>(basic);
                expectedPro.add("RECIPE_MANAGEMENT");
                assertThat(codes(sql, prefix, "PRO")).isEqualTo(expectedPro);
                assertThat(codes(sql, prefix, "CUSTOM")).containsExactly("STAFF_PERMISSION");
                assertThat(value(sql, "SELECT jsonb_agg(to_jsonb(p) ORDER BY id)::text FROM " + prefix + "packages p"))
                    .isEqualTo(packagesBefore);
                assertThat(value(sql, "SELECT jsonb_agg(to_jsonb(s) ORDER BY id)::text FROM " + prefix + "restaurant_subscriptions s"))
                    .isEqualTo(subscriptionsBefore);
                // Every pre-existing mapping (including PREMIUM, limits and timestamps) is unchanged.
                try (var check = connection.prepareStatement("SELECT jsonb_object_agg(package_id::text||':'||feature_id::text,to_jsonb(pf)) @> ?::jsonb FROM " + prefix + "package_features pf")) {
                    check.setString(1, mappingsBefore);
                    try (var rows = check.executeQuery()) { rows.next(); assertThat(rows.getBoolean(1)).isTrue(); }
                }
                // Six additions, but BASIC's customized STAFF_PERMISSION already existed.
                assertThat(Integer.parseInt(value(sql, "SELECT count(*) FROM " + prefix + "package_features")))
                    .isEqualTo(countBefore + 5);
                assertThat(upgrade.migrate().migrationsExecuted).isZero();
            } finally {
                if (!schema.matches("it_tiers_[0-9a-f]{32}")) throw new IllegalStateException("Unsafe test schema");
                sql.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
            }
        }
    }

    private static String value(Statement sql, String query) throws Exception {
        try (var rows = sql.executeQuery(query)) { rows.next(); return rows.getString(1); }
    }

    private static Set<String> codes(Statement sql, String prefix, String code) throws Exception {
        var result = new HashSet<String>();
        try (var rows = sql.executeQuery("SELECT f.code FROM " + prefix + "package_features pf JOIN "
            + prefix + "packages p ON p.id=pf.package_id JOIN " + prefix
            + "features f ON f.id=pf.feature_id WHERE p.code='" + code + "'")) {
            while (rows.next()) result.add(rows.getString(1));
        }
        return result;
    }
}
