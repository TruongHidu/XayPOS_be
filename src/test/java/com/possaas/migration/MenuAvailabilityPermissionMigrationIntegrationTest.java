package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {
    "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MenuAvailabilityPermissionMigrationIntegrationTest {
    private static final String SCHEMA_PREFIX = "it_menu_availability_";
    private static final String CONTEXT_SCHEMA = newSchema();
    private static final String PERMISSION_CODE = "MENU_AVAILABILITY_UPDATE";
    private static final String MIGRATION = "/db/migration/V36__add_menu_availability_permission.sql";

    @Autowired
    private DataSource dataSource;

    @DynamicPropertySource
    static void isolatedContext(DynamicPropertyRegistry properties) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=")
                ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + CONTEXT_SCHEMA)
                : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + CONTEXT_SCHEMA;
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.schemas", () -> CONTEXT_SCHEMA);
        properties.add("spring.flyway.default-schema", () -> CONTEXT_SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> CONTEXT_SCHEMA);
    }

    @AfterAll
    void cleanupContextSchema() throws Exception {
        dropSchema(CONTEXT_SCHEMA);
    }

    @Test
    void v36AddsOnlyGlobalOwnerAndSuperAdminDefaultsWithoutChangingExistingAssignments() throws Exception {
        verifyUpgrade(false);
    }

    @Test
    void v36PreservesPreexistingInactivePermissionAndExplicitAssignments() throws Exception {
        verifyUpgrade(true);
    }

    private void verifyUpgrade(boolean preexistingPermission) throws Exception {
        String schema = newSchema();
        String prefix = quotedSchema(schema) + ".";
        createSchema(schema);
        try {
            migrations(schema, "35").migrate();
            Map<String, String> before = new LinkedHashMap<>();
            String oldPermissions;
            String oldRolePermissions;
            String existingPermission;
            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                seedFixture(sql, prefix, preexistingPermission);
                for (String table : List.of("roles", "users", "user_permissions", "restaurants", "items",
                        "packages", "features", "package_features", "restaurant_subscriptions", "audit_logs")) {
                    before.put(table, snapshot(sql, prefix, table));
                }
                oldPermissions = permissionsWithoutAvailability(sql, prefix);
                oldRolePermissions = unrelatedRolePermissions(sql, prefix);
                existingPermission = permissionSnapshot(sql, prefix);
            }

            Flyway upgrade = migrations(schema, "36");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(upgrade.info().current().getVersion().getVersion()).isEqualTo("36");

            try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
                for (var entry : before.entrySet()) {
                    assertThat(snapshot(sql, prefix, entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                }
                assertThat(permissionsWithoutAvailability(sql, prefix)).isEqualTo(oldPermissions);
                assertThat(unrelatedRolePermissions(sql, prefix)).isEqualTo(oldRolePermissions);
                assertThat(value(sql, "SELECT string_agg(r.code,',' ORDER BY r.code) FROM " + prefix
                    + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id JOIN " + prefix
                    + "permissions p ON p.id=rp.permission_id WHERE r.restaurant_id IS NULL AND r.is_system"
                    + " AND p.code='" + PERMISSION_CODE + "'"))
                    .isEqualTo("OWNER,SUPER_ADMIN");

                if (preexistingPermission) {
                    assertThat(permissionSnapshot(sql, prefix)).isEqualTo(existingPermission);
                } else {
                    try (ResultSet rows = sql.executeQuery("SELECT module,name,is_active FROM " + prefix
                            + "permissions WHERE code='" + PERMISSION_CODE + "'")) {
                        assertThat(rows.next()).isTrue();
                        assertThat(rows.getString("module")).isEqualTo("MENU");
                        assertThat(rows.getString("name")).isEqualTo("Update menu item availability");
                        assertThat(rows.getBoolean("is_active")).isTrue();
                        assertThat(rows.next()).isFalse();
                    }
                }

                String permissionRows = snapshot(sql, prefix, "permissions");
                String rolePermissionRows = snapshot(sql, prefix, "role_permissions");
                String previousSchema = connection.getSchema();
                connection.setSchema(schema);
                try (var input = getClass().getResourceAsStream(MIGRATION)) {
                    String migration = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
                    sql.execute(migration);
                    sql.execute(migration);
                } finally {
                    connection.setSchema(previousSchema);
                }
                assertThat(snapshot(sql, prefix, "permissions")).isEqualTo(permissionRows);
                assertThat(snapshot(sql, prefix, "role_permissions")).isEqualTo(rolePermissionRows);
                assertThat(snapshot(sql, prefix, "user_permissions")).isEqualTo(before.get("user_permissions"));
            }
        } finally {
            dropSchema(schema);
        }
    }

    private void seedFixture(Statement sql, String prefix, boolean preexistingPermission) throws Exception {
        String tenant = UUID.randomUUID().toString();
        String grantedUser = UUID.randomUUID().toString();
        String deniedUser = UUID.randomUUID().toString();
        String owner = UUID.randomUUID().toString();
        sql.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES('" + tenant
            + "','AVAIL_V36','Existing restaurant','ACTIVE')");
        // Tenant roles with built-in names must not receive system-role defaults.
        sql.execute("INSERT INTO " + prefix + "roles(restaurant_id,code,name,is_system) SELECT '" + tenant
            + "'::uuid,code,'Custom '||code,code='SUPER_ADMIN' FROM " + prefix
            + "roles WHERE restaurant_id IS NULL AND code IN ('OWNER','SUPER_ADMIN','CASHIER','KITCHEN')");
        sql.execute("INSERT INTO " + prefix + "role_permissions(role_id,permission_id) SELECT r.id,p.id FROM "
            + prefix + "roles r CROSS JOIN " + prefix + "permissions p WHERE r.restaurant_id='" + tenant
            + "' AND p.code='MENU_UPDATE'");
        sql.execute("INSERT INTO " + prefix + "users(id,restaurant_id,role_id,email,password_hash,name) SELECT '"
            + grantedUser + "','" + tenant + "',id,'grant@v36.test','fixture','Granted waiter' FROM " + prefix
            + "roles WHERE restaurant_id IS NULL AND code='WAITER'");
        sql.execute("INSERT INTO " + prefix + "users(id,restaurant_id,role_id,email,password_hash,name) SELECT '"
            + deniedUser + "','" + tenant + "',id,'deny@v36.test','fixture','Denied cashier' FROM " + prefix
            + "roles WHERE restaurant_id IS NULL AND code='CASHIER'");
        sql.execute("INSERT INTO " + prefix + "users(id,restaurant_id,role_id,email,password_hash,name) SELECT '"
            + owner + "','" + tenant + "',id,'owner@v36.test','fixture','Owner' FROM " + prefix
            + "roles WHERE restaurant_id IS NULL AND code='OWNER'");
        sql.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) SELECT '"
            + grantedUser + "',id,'GRANT' FROM " + prefix + "permissions WHERE code='MENU_UPDATE'");
        sql.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) SELECT u.id,p.id,'DENY'"
            + " FROM " + prefix + "users u CROSS JOIN " + prefix + "permissions p WHERE u.id IN ('"
            + deniedUser + "','" + owner + "') AND p.code='MENU_UPDATE'");
        sql.execute("INSERT INTO " + prefix + "items(restaurant_id,name,item_type,base_unit,sale_price,cost_price,"
            + "availability_status,created_at,updated_at) VALUES('" + tenant
            + "','Existing dish','MENU_ITEM','portion',100,10,'OUT_OF_STOCK',now(),now())");

        if (preexistingPermission) {
            sql.execute("INSERT INTO " + prefix + "permissions(id,code,module,name,description,is_active) VALUES('"
                + UUID.randomUUID() + "','" + PERMISSION_CODE
                + "','MENU','Custom availability label','Keep this disabled permission',false)");
            sql.execute("INSERT INTO " + prefix + "role_permissions(role_id,permission_id) SELECT r.id,p.id FROM "
                + prefix + "roles r CROSS JOIN " + prefix + "permissions p WHERE r.restaurant_id='" + tenant
                + "' AND r.code='OWNER' AND p.code='" + PERMISSION_CODE + "'");
            sql.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) SELECT '"
                + grantedUser + "',id,'GRANT' FROM " + prefix + "permissions WHERE code='" + PERMISSION_CODE + "'");
            sql.execute("INSERT INTO " + prefix + "user_permissions(user_id,permission_id,effect) SELECT '"
                + deniedUser + "',id,'DENY' FROM " + prefix + "permissions WHERE code='" + PERMISSION_CODE + "'");
        }
    }

    private Flyway migrations(String schema, String target) {
        quotedSchema(schema);
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").target(target).load();
    }

    private String permissionsWithoutAvailability(Statement sql, String prefix) throws Exception {
        return value(sql, "SELECT coalesce(jsonb_agg(to_jsonb(p) ORDER BY to_jsonb(p)::text),'[]'::jsonb)::text"
            + " FROM " + prefix + "permissions p WHERE p.code<>'" + PERMISSION_CODE + "'");
    }

    private String unrelatedRolePermissions(Statement sql, String prefix) throws Exception {
        return value(sql, "SELECT coalesce(jsonb_agg(to_jsonb(rp) ORDER BY to_jsonb(rp)::text),'[]'::jsonb)::text"
            + " FROM " + prefix + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id JOIN "
            + prefix + "permissions p ON p.id=rp.permission_id WHERE NOT (r.restaurant_id IS NULL AND r.is_system"
            + " AND r.code IN ('OWNER','SUPER_ADMIN') AND p.code='" + PERMISSION_CODE + "')");
    }

    private String permissionSnapshot(Statement sql, String prefix) throws Exception {
        return value(sql, "SELECT coalesce(jsonb_agg(to_jsonb(p)),'[]'::jsonb)::text FROM " + prefix
            + "permissions p WHERE p.code='" + PERMISSION_CODE + "'");
    }

    private String snapshot(Statement sql, String prefix, String table) throws Exception {
        return value(sql, "SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY to_jsonb(t)::text),'[]'::jsonb)::text FROM "
            + prefix + table + " t");
    }

    private String value(Statement sql, String query) throws Exception {
        try (ResultSet rows = sql.executeQuery(query)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }

    private static String newSchema() {
        return SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    private static String quotedSchema(String schema) {
        if (!schema.matches("it_menu_availability_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to access a non-test schema: " + schema);
        }
        return "\"" + schema + "\"";
    }

    private void createSchema(String schema) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + quotedSchema(schema));
        }
    }

    private void dropSchema(String schema) throws Exception {
        String quoted = quotedSchema(schema);
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA " + quoted + " CASCADE");
        }
    }
}
