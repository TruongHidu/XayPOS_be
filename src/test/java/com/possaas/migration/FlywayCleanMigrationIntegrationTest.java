package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.Map;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FlywayCleanMigrationIntegrationTest {

    private static final String TEST_SCHEMA_PREFIX = "it_flyway_";
    private static final String CONTEXT_SCHEMA = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");

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

    @Autowired
    private DataSource dataSource;

    @AfterAll
    void cleanupContextSchema() throws Exception { dropTestSchema(CONTEXT_SCHEMA); }

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

            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("35");
            assertThat(countTables(schema)).isEqualTo(41);
            assertThat(tableExists(schema, "stock_audits")).isTrue();
            assertThat(tableExists(schema, "stock_audit_items")).isTrue();
            assertThat(indexExists(schema, "ux_stock_audit_counting")).isTrue();
            assertThat(indexExists(schema, "ux_stock_document_stock_audit")).isTrue();
            assertThat(tableExists(schema, "low_stock_alerts")).isTrue();
            assertThat(indexExists(schema, "ux_low_stock_alert_unresolved")).isTrue();
            assertThat(tableExists(schema, "order_item_inventory_allocations")).isTrue();
            assertThat(tableExists(schema, "inventory_reservations")).isTrue();
            assertThat(tableExists(schema, "order_item_recipe_snapshots")).isTrue();
            assertThat(tableExists(schema, "order_item_recipe_usages")).isTrue();
            assertThat(tableExists(schema, "stock_documents")).isTrue();
            assertThat(tableExists(schema, "stock_document_items")).isTrue();
            assertThat(tableExists(schema, "inventory_transactions")).isTrue();
            assertThat(indexExists(schema, "ix_stock_document_status_created")).isTrue();
            assertThat(indexExists(schema, "ix_inventory_transaction_item_created")).isTrue();
            assertThat(tableExists(schema, "warehouses")).isTrue();
            assertThat(tableExists(schema, "inventory_balances")).isTrue();
            assertThat(indexExists(schema, "ux_warehouse_default")).isTrue();
            assertThat(indexExists(schema, "ix_inventory_balance_tenant_warehouse_item")).isTrue();
            assertThat(tableExists(schema, "recipes")).isTrue();
            assertThat(tableExists(schema, "recipe_items")).isTrue();
            assertThat(indexExists(schema, "ux_recipe_active")).isTrue();
            assertThat(indexExists(schema, "ix_recipe_history")).isTrue();
            assertThat(indexExists(schema, "ix_recipe_item_ingredient")).isTrue();
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
            assertThat(indexExists(schema, "ix_report_payment_success_paid")).isTrue();
            assertThat(indexExists(schema, "ix_report_order_completed")).isTrue();
            assertThat(indexExists(schema, "ix_report_order_cancelled")).isTrue();
            assertThat(indexExists(schema, "ix_report_order_created")).isTrue();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + schema
                     + "\".packages p JOIN \"" + schema + "\".package_features pf ON pf.package_id=p.id JOIN \""
                     + schema + "\".features f ON f.id=pf.feature_id WHERE p.code IN ('BASIC','PRO','PREMIUM')"
                     + " AND f.code IN ('QR_TABLE_ORDER','QR_STATIC_ORDER')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(6);
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(r.code,',' ORDER BY r.code) FROM \"" + schema
                     + "\".roles r JOIN \"" + schema + "\".role_permissions rp ON rp.role_id=r.id JOIN \""
                     + schema + "\".permissions p ON p.id=rp.permission_id WHERE p.code='ORDER_COMPLETE'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("CASHIER,MANAGER,OWNER,WAITER");
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + schema
                     + "\".permissions WHERE module='INVENTORY' AND code IN "
                     + "('INGREDIENT_VIEW','INGREDIENT_CREATE','INGREDIENT_UPDATE','INGREDIENT_DELETE')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(DISTINCT r.code,',' ORDER BY r.code) FROM \""
                     + schema + "\".roles r JOIN \"" + schema + "\".role_permissions rp ON rp.role_id=r.id JOIN \""
                     + schema + "\".permissions p ON p.id=rp.permission_id WHERE p.code LIKE 'INGREDIENT_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("OWNER,SUPER_ADMIN");
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + schema
                     + "\".permissions WHERE module='INVENTORY' AND code IN "
                     + "('WAREHOUSE_CREATE','WAREHOUSE_UPDATE','WAREHOUSE_DELETE','INVENTORY_THRESHOLD_UPDATE')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(DISTINCT r.code,',' ORDER BY r.code) FROM \""
                     + schema + "\".roles r JOIN \"" + schema + "\".role_permissions rp ON rp.role_id=r.id JOIN \""
                     + schema + "\".permissions p ON p.id=rp.permission_id WHERE p.code IN "
                     + "('WAREHOUSE_CREATE','WAREHOUSE_UPDATE','WAREHOUSE_DELETE','INVENTORY_THRESHOLD_UPDATE')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("OWNER,SUPER_ADMIN");
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT count(*) FROM \"" + schema
                     + "\".permissions WHERE module='MENU' AND code IN "
                     + "('RECIPE_VIEW','RECIPE_CREATE','RECIPE_UPDATE','RECIPE_DELETE')")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getInt(1)).isEqualTo(4);
            }
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT string_agg(DISTINCT r.code,',' ORDER BY r.code) FROM \""
                     + schema + "\".roles r JOIN \"" + schema + "\".role_permissions rp ON rp.role_id=r.id JOIN \""
                     + schema + "\".permissions p ON p.id=rp.permission_id WHERE p.code LIKE 'RECIPE_%'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("OWNER,SUPER_ADMIN");
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
    void v28OnlyAddsIngredientPermissionsAndPreservesCatalogPackagesAndSnapshots() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("27").load().migrate();
            String restaurantId = "00000000-0000-0000-0000-000000000028";
            String snapshot = "{\"schemaVersion\":1,\"packageCode\":\"PRO\",\"features\":[{\"code\":\"RECIPE_MANAGEMENT\",\"limits\":{}}]}";
            int packageFeatures;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES('" + restaurantId
                    + "','V28_KEEP','V28 keep','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "items(restaurant_id,sku,name,item_type,base_unit,sale_price,"
                    + "cost_price,track_inventory,availability_status,is_active,metadata,created_at,updated_at) VALUES('"
                    + restaurantId + "','KEEP','Keep ingredient','INGREDIENT','g',0,12.50,true,'AVAILABLE',true,"
                    + "'{}'::jsonb,now(),now())");
                statement.execute("INSERT INTO " + prefix + "restaurant_subscriptions(restaurant_id,package_id,status,"
                    + "start_at,end_at,price_amount,currency_code,feature_snapshot) SELECT '" + restaurantId
                    + "',id,'ACTIVE',now(),now()+interval '1 day',0,'VND','" + snapshot
                    + "'::jsonb FROM " + prefix + "packages WHERE code='PRO'");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    rows.next(); packageFeatures = rows.getInt(1);
                }
            }
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("28").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                try (ResultSet rows = statement.executeQuery("SELECT sku,item_type,cost_price,track_inventory FROM "
                    + prefix + "items WHERE restaurant_id='" + restaurantId + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("KEEP");
                    assertThat(rows.getString(2)).isEqualTo("INGREDIENT");
                    assertThat(rows.getBigDecimal(3)).isEqualByComparingTo("12.50");
                    assertThat(rows.getBoolean(4)).isTrue();
                }
                try (ResultSet rows = statement.executeQuery("SELECT feature_snapshot='" + snapshot
                    + "'::jsonb FROM " + prefix + "restaurant_subscriptions WHERE restaurant_id='" + restaurantId + "'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getBoolean(1)).isTrue();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(packageFeatures);
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix
                    + "role_permissions rp JOIN " + prefix + "roles r ON r.id=rp.role_id JOIN " + prefix
                    + "permissions p ON p.id=rp.permission_id WHERE p.code LIKE 'INGREDIENT_%' "
                    + "AND r.code NOT IN ('OWNER','SUPER_ADMIN')")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v29CreatesRecipeSchemaAndPreservesV28DataPackagesAndSnapshots() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("28").load().migrate();
            String restaurantId = "00000000-0000-0000-0000-000000000029";
            String snapshot = "{\"schemaVersion\":1,\"packageCode\":\"PRO\",\"features\":[{\"code\":\"RECIPE_MANAGEMENT\",\"limits\":{}}]}";
            int packageFeatures;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES('" + restaurantId
                        + "','V29_KEEP','V29 keep','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "items(restaurant_id,sku,name,item_type,base_unit,sale_price,"
                        + "cost_price,track_inventory,availability_status,is_active,metadata,created_at,updated_at) VALUES "
                        + "('" + restaurantId + "','MENU-KEEP','Keep dish','MENU_ITEM','portion',10,2,false,'AVAILABLE',true,'{}'::jsonb,now(),now()),"
                        + "('" + restaurantId + "','ING-KEEP','Keep ingredient','INGREDIENT','g',0,2,true,'AVAILABLE',true,'{}'::jsonb,now(),now())");
                statement.execute("INSERT INTO " + prefix + "restaurant_subscriptions(restaurant_id,package_id,status,"
                        + "start_at,end_at,price_amount,currency_code,feature_snapshot) SELECT '" + restaurantId
                        + "',id,'ACTIVE',now(),now()+interval '1 day',0,'VND','" + snapshot
                        + "'::jsonb FROM " + prefix + "packages WHERE code='PRO'");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    rows.next();
                    packageFeatures = rows.getInt(1);
                }
            }

            Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("29").load();
            flyway.migrate();
            flyway.validate();

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThat(tableExists(schema, "recipes")).isTrue();
                assertThat(tableExists(schema, "recipe_items")).isTrue();
                assertThat(indexExists(schema, "ux_recipe_active")).isTrue();
                try (ResultSet rows = statement.executeQuery("SELECT string_agg(sku,',' ORDER BY sku) FROM " + prefix
                        + "items WHERE restaurant_id='" + restaurantId + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("ING-KEEP,MENU-KEEP");
                }
                try (ResultSet rows = statement.executeQuery("SELECT feature_snapshot='" + snapshot
                        + "'::jsonb FROM " + prefix + "restaurant_subscriptions WHERE restaurant_id='" + restaurantId + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(packageFeatures);
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "recipes")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isZero();
                }
                try (ResultSet rows = statement.executeQuery("SELECT string_agg(DISTINCT r.code,',' ORDER BY r.code) FROM "
                        + prefix + "roles r JOIN " + prefix + "role_permissions rp ON rp.role_id=r.id JOIN " + prefix
                        + "permissions p ON p.id=rp.permission_id WHERE p.code LIKE 'RECIPE_%'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo("OWNER,SUPER_ADMIN");
                }
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v30CreatesInventoryFoundationAndPreservesV29DataPackagesAndSnapshots() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("29").load().migrate();
            String restaurantId = "00000000-0000-0000-0000-000000000030";
            String itemId = "00000000-0000-0000-0000-000000000031";
            String snapshot = "{\"schemaVersion\":1,\"packageCode\":\"PREMIUM\",\"features\":[{\"code\":\"INVENTORY_MANAGEMENT\",\"limits\":{}}]}";
            int packageFeatures;
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES('" + restaurantId
                        + "','V30_KEEP','V30 keep','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "items(id,restaurant_id,sku,name,item_type,base_unit,sale_price,"
                        + "cost_price,track_inventory,availability_status,is_active,metadata,created_at,updated_at) VALUES('"
                        + itemId + "','" + restaurantId + "','ING-V30','Keep ingredient','INGREDIENT','g',0,2,true,"
                        + "'AVAILABLE',true,'{}'::jsonb,now(),now())");
                statement.execute("INSERT INTO " + prefix + "restaurant_subscriptions(restaurant_id,package_id,status,"
                        + "start_at,end_at,price_amount,currency_code,feature_snapshot) SELECT '" + restaurantId
                        + "',id,'ACTIVE',now(),now()+interval '1 day',0,'VND','" + snapshot
                        + "'::jsonb FROM " + prefix + "packages WHERE code='PREMIUM'");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    rows.next();
                    packageFeatures = rows.getInt(1);
                }
            }

            Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("30").load();
            flyway.migrate();
            flyway.validate();

            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                assertThat(tableExists(schema, "warehouses")).isTrue();
                assertThat(tableExists(schema, "inventory_balances")).isTrue();
                try (ResultSet rows = statement.executeQuery("SELECT feature_snapshot='" + snapshot
                        + "'::jsonb FROM " + prefix + "restaurant_subscriptions WHERE restaurant_id='" + restaurantId + "'")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getBoolean(1)).isTrue();
                }
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "package_features")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(packageFeatures);
                }
                statement.execute("INSERT INTO " + prefix + "warehouses(id,restaurant_id,code,name,is_default,is_active,"
                        + "created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000032','" + restaurantId
                        + "','MAIN','Main',true,true,now(),now())");
                statement.execute("INSERT INTO " + prefix + "inventory_balances(restaurant_id,warehouse_id,item_id,"
                        + "quantity,min_quantity,created_at,updated_at) VALUES('" + restaurantId
                        + "','00000000-0000-0000-0000-000000000032','" + itemId + "',0,5,now(),now())");
                try (ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + prefix + "inventory_balances")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v31CreatesStockLedgerAndPreservesExistingInventory() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("30").load().migrate();
            String restaurantId = "00000000-0000-0000-0000-000000000131";
            String warehouseId = "00000000-0000-0000-0000-000000000132";
            String itemId = "00000000-0000-0000-0000-000000000133";
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES('" + restaurantId
                        + "','V31_KEEP','V31 keep','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "items(id,restaurant_id,sku,name,item_type,base_unit,sale_price,"
                        + "cost_price,track_inventory,availability_status,is_active,metadata,created_at,updated_at) VALUES('"
                        + itemId + "','" + restaurantId + "','ING-V31','Keep stock','INGREDIENT','g',0,2,true,"
                        + "'AVAILABLE',true,'{}'::jsonb,now(),now())");
                statement.execute("INSERT INTO " + prefix + "warehouses(id,restaurant_id,code,name,is_default,is_active,"
                        + "created_at,updated_at) VALUES('" + warehouseId + "','" + restaurantId
                        + "','MAIN','Main',true,true,now(),now())");
                statement.execute("INSERT INTO " + prefix + "inventory_balances(restaurant_id,warehouse_id,item_id,"
                        + "quantity,min_quantity,created_at,updated_at) VALUES('" + restaurantId + "','" + warehouseId
                        + "','" + itemId + "',7.500,2,now(),now())");
            }
            Flyway flyway = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("31").load();
            flyway.migrate();
            flyway.validate();
            assertThat(tableExists(schema, "stock_documents")).isTrue();
            assertThat(tableExists(schema, "stock_document_items")).isTrue();
            assertThat(tableExists(schema, "inventory_transactions")).isTrue();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT quantity,min_quantity FROM " + prefix
                            + "inventory_balances WHERE item_id='" + itemId + "'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("7.500");
                assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("2.000");
            }
        } finally {
            dropTestSchema(schema);
        }
    }

    @Test
    void v31FailsClearlyWhenLegacyNegativeBalanceExists() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("30").load().migrate();
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO " + prefix + "restaurants(id,code,name,status) VALUES "
                        + "('00000000-0000-0000-0000-000000000141','V31_NEG','Negative fixture','ACTIVE')");
                statement.execute("INSERT INTO " + prefix + "items(id,restaurant_id,name,item_type,base_unit,sale_price,"
                        + "cost_price,track_inventory,availability_status,is_active,metadata,created_at,updated_at) VALUES "
                        + "('00000000-0000-0000-0000-000000000143','00000000-0000-0000-0000-000000000141',"
                        + "'Negative','INGREDIENT','g',0,0,true,'AVAILABLE',true,'{}'::jsonb,now(),now())");
                statement.execute("INSERT INTO " + prefix + "warehouses(id,restaurant_id,code,name,is_default,is_active,"
                        + "created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000142',"
                        + "'00000000-0000-0000-0000-000000000141','MAIN','Main',true,true,now(),now())");
                statement.execute("INSERT INTO " + prefix + "inventory_balances(restaurant_id,warehouse_id,item_id,"
                        + "quantity,min_quantity,created_at,updated_at) VALUES('00000000-0000-0000-0000-000000000141',"
                        + "'00000000-0000-0000-0000-000000000142','00000000-0000-0000-0000-000000000143',-1,0,now(),now())");
            }
            Flyway upgrade = Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("31").load();
            assertThatThrownBy(upgrade::migrate)
                    .hasStackTraceContaining("V31 cannot add non-negative inventory constraint");
            try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT quantity FROM " + prefix + "inventory_balances")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("-1.000");
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
                .locations("classpath:db/migration").target("20").load();
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
                .locations("classpath:db/migration").target("24").load();
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

    @Test
    void v32MarksOnlyHistoricSubmittedLinesWithoutGuessingRecipesOrMutatingExistingData() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String prefix = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("31").load().migrate();
            String tenant=UUID.randomUUID().toString(), menu=UUID.randomUUID().toString(), recipe=UUID.randomUUID().toString();
            String submittedOrder=UUID.randomUUID().toString(), draftOrder=UUID.randomUUID().toString();
            String submitted=UUID.randomUUID().toString(), draft=UUID.randomUUID().toString(), cancelled=UUID.randomUUID().toString();
            Map<String,String> before=new java.util.LinkedHashMap<>();
            try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()) {
                statement.execute("INSERT INTO "+prefix+"restaurants(id,code,name,status) VALUES('"+tenant+"','V32_KEEP','Legacy','ACTIVE')");
                statement.execute("INSERT INTO "+prefix+"items(id,restaurant_id,name,item_type,base_unit,sale_price,cost_price,availability_status,created_at,updated_at) VALUES('"+menu+"','"+tenant+"','Food','MENU_ITEM','portion',100,10,'AVAILABLE',now(),now())");
                statement.execute("INSERT INTO "+prefix+"recipes(id,restaurant_id,menu_item_id,recipe_version,yield_quantity,yield_unit,is_active,created_at,updated_at) VALUES('"+recipe+"','"+tenant+"','"+menu+"',7,10,'portion',true,now(),now())");
                for(String order:java.util.List.of(submittedOrder,draftOrder)) {
                    boolean sent=order.equals(submittedOrder);
                    statement.execute("INSERT INTO "+prefix+"orders(id,restaurant_id,order_code,service_type,source_channel,status,currency_code,version,idempotency_key,request_hash,confirmed_at,created_at,updated_at) VALUES('"+order+"','"+tenant+"','"+order+"','TAKEAWAY','CASHIER','"+(sent?"CONFIRMED":"OPEN")+"','VND',7,'"+order+"','"+"a".repeat(64)+"',"+(sent?"now()":"NULL")+",now(),now())");
                }
                statement.execute("INSERT INTO "+prefix+"order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,line_number,sent_to_kitchen_at,created_at,updated_at) VALUES('"+submitted+"','"+tenant+"','"+submittedOrder+"','"+menu+"','Food','portion',3,100,300,'PENDING',1,now(),now(),now())");
                statement.execute("INSERT INTO "+prefix+"order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,line_number,created_at,updated_at) VALUES('"+draft+"','"+tenant+"','"+draftOrder+"','"+menu+"','Food','portion',2,100,200,'PENDING',1,now(),now())");
                statement.execute("INSERT INTO "+prefix+"order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,cancel_reason,line_number,created_at,updated_at) VALUES('"+cancelled+"','"+tenant+"','"+draftOrder+"','"+menu+"','Food','portion',1,100,100,'CANCELLED','not submitted',2,now(),now())");
                for(String table:java.util.List.of("orders","order_items","recipes","items","audit_logs","inventory_balances","inventory_transactions"))
                    before.put(table,tableSnapshot(statement,prefix,table));
            }
            Flyway upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("32").load();
            upgraded.migrate();upgraded.validate();
            try(Connection connection=dataSource.getConnection();Statement statement=connection.createStatement()) {
                for(var entry:before.entrySet())assertThat(tableSnapshot(statement,prefix,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                try(ResultSet rows=statement.executeQuery("SELECT order_item_id,snapshot_status,recipe_id,captured_at,order_quantity_snapshot FROM "+prefix+"order_item_recipe_snapshots")) {
                    assertThat(rows.next()).isTrue();assertThat(rows.getString(1)).isEqualTo(submitted);
                    assertThat(rows.getString(2)).isEqualTo("LEGACY_UNAVAILABLE");assertThat(rows.getObject(3)).isNull();
                    assertThat(rows.getObject(4)).isNull();assertThat(rows.getBigDecimal(5)).isEqualByComparingTo("3");assertThat(rows.next()).isFalse();
                }
                try(ResultSet rows=statement.executeQuery("SELECT count(*) FROM "+prefix+"order_item_recipe_usages")) {rows.next();assertThat(rows.getLong(1)).isZero();}
            }
        } finally {dropTestSchema(schema);}
    }

    @Test
    void v33MarksHistoricInventoryAsSkippedAndPreservesPhysicalStockSnapshotsAndLedger() throws Exception {
        String schema = TEST_SCHEMA_PREFIX + UUID.randomUUID().toString().replace("-", "");
        String p = "\"" + schema + "\".";
        createSchema(schema);
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("32").load().migrate();
            String tenant=UUID.randomUUID().toString(), owner=UUID.randomUUID().toString();
            String menu=UUID.randomUUID().toString(), ingredient=UUID.randomUUID().toString(), recipe=UUID.randomUUID().toString();
            String order=UUID.randomUUID().toString(), draftOrder=UUID.randomUUID().toString();
            String capturedLine=UUID.randomUUID().toString(), legacyLine=UUID.randomUUID().toString();
            String draftLine=UUID.randomUUID().toString(), cancelledDraft=UUID.randomUUID().toString();
            String snapshot=UUID.randomUUID().toString(), warehouse=UUID.randomUUID().toString();
            String document=UUID.randomUUID().toString(), documentItem=UUID.randomUUID().toString();
            Map<String,String> before = new java.util.LinkedHashMap<>();
            try (Connection connection=dataSource.getConnection(); Statement s=connection.createStatement()) {
                // CAPTURED header and its immutable usage must be inserted in one transaction.
                // Trigger queries must resolve this fixture schema, not the Spring context schema.
                String previousSchema = connection.getSchema();
                connection.setSchema(schema);
                connection.setAutoCommit(false);
                s.execute("INSERT INTO "+p+"restaurants(id,code,name,status) VALUES('"+tenant+"','V33_KEEP','Keep physical stock','ACTIVE')");
                s.execute("INSERT INTO "+p+"users(id,restaurant_id,role_id,email,password_hash,name) SELECT '"+owner+"','"+tenant+"',id,'v33@example.test','fixture','Owner' FROM "+p+"roles WHERE restaurant_id IS NULL AND code='OWNER'");
                s.execute("INSERT INTO "+p+"items(id,restaurant_id,name,item_type,base_unit,sale_price,cost_price,track_inventory,availability_status,created_at,updated_at) VALUES "
                        +"('"+menu+"','"+tenant+"','Food','MENU_ITEM','portion',100,0,false,'AVAILABLE',now(),now()),"
                        +"('"+ingredient+"','"+tenant+"','Ingredient','INGREDIENT','g',0,2,true,'AVAILABLE',now(),now())");
                s.execute("INSERT INTO "+p+"recipes(id,restaurant_id,menu_item_id,recipe_version,yield_quantity,yield_unit,is_active,created_at,updated_at) VALUES('"+recipe+"','"+tenant+"','"+menu+"',1,10,'portion',true,now(),now())");
                for(String id:java.util.List.of(order,draftOrder)) {
                    boolean submitted=id.equals(order);
                    s.execute("INSERT INTO "+p+"orders(id,restaurant_id,order_code,service_type,source_channel,status,currency_code,version,idempotency_key,request_hash,confirmed_at,created_at,updated_at) VALUES('"+id+"','"+tenant+"','"+id+"','TAKEAWAY','CASHIER','"+(submitted?"CONFIRMED":"OPEN")+"','VND',9,'"+id+"',repeat('a',64),"+(submitted?"now()":"NULL")+",now(),now())");
                }
                int lineNumber=0;
                for(String id:java.util.List.of(capturedLine,legacyLine,draftLine,cancelledDraft)) {
                    boolean sent=id.equals(capturedLine)||id.equals(legacyLine);
                    boolean cancelled=id.equals(cancelledDraft);
                    s.execute("INSERT INTO "+p+"order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,cancel_reason,line_number,sent_to_kitchen_at,created_at,updated_at) VALUES('"+id+"','"+tenant+"','"+(sent?order:draftOrder)+"','"+menu+"','Food','portion',3,100,300,'"+(cancelled?"CANCELLED":"PENDING")+"',"+(cancelled?"'never submitted'":"NULL")+","+(++lineNumber)+","+(sent?"now()":"NULL")+",now(),now())");
                }
                s.execute("INSERT INTO "+p+"order_item_recipe_snapshots(id,restaurant_id,order_item_id,menu_item_id_snapshot,snapshot_status,recipe_id,recipe_version,yield_quantity,yield_unit,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at) VALUES('"+snapshot+"','"+tenant+"','"+capturedLine+"','"+menu+"','CAPTURED','"+recipe+"',1,10,'portion',3,'portion',now(),now())");
                s.execute("INSERT INTO "+p+"order_item_recipe_usages(restaurant_id,recipe_snapshot_id,ingredient_id,ingredient_name_snapshot,ingredient_unit_snapshot,recipe_quantity_snapshot,waste_percent_snapshot,track_inventory_snapshot,required_quantity,created_at) VALUES('"+tenant+"','"+snapshot+"','"+ingredient+"','Ingredient','g',1000,5,true,315,now())");
                s.execute("INSERT INTO "+p+"warehouses(id,restaurant_id,code,name,is_default,is_active,created_at,updated_at) VALUES('"+warehouse+"','"+tenant+"','MAIN','Main',true,true,now(),now())");
                s.execute("INSERT INTO "+p+"inventory_balances(restaurant_id,warehouse_id,item_id,quantity,min_quantity,created_at,updated_at) VALUES('"+tenant+"','"+warehouse+"','"+ingredient+"',1000,10,now(),now())");
                s.execute("INSERT INTO "+p+"stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,idempotency_key,creation_request_hash,created_by,confirmed_by,confirmed_at,line_count,total_quantity,total_cost,created_at,updated_at) VALUES('"+document+"','"+tenant+"','"+warehouse+"','OLD_RECEIPT','RECEIPT','CONFIRMED','old-key',repeat('b',64),'"+owner+"','"+owner+"',now(),1,1000,2000,now(),now())");
                s.execute("INSERT INTO "+p+"stock_document_items(id,restaurant_id,stock_document_id,ingredient_id,ingredient_sku,ingredient_name,unit,quantity,unit_cost,total_cost,created_at,updated_at) VALUES('"+documentItem+"','"+tenant+"','"+document+"','"+ingredient+"','OLD','Old historical name','g',1000,2,2000,now(),now())");
                s.execute("INSERT INTO "+p+"inventory_transactions(restaurant_id,warehouse_id,item_id,transaction_type,stock_document_id,stock_document_item_id,quantity_change,before_quantity,after_quantity,unit,unit_cost,idempotency_key,created_by,created_at) VALUES('"+tenant+"','"+warehouse+"','"+ingredient+"','RECEIPT','"+document+"','"+documentItem+"',1000,0,1000,'g',2,'old-ledger-key','"+owner+"',now())");
                connection.commit();
                connection.setAutoCommit(true);
                for(String table:java.util.List.of("orders","order_items","items","recipes","order_item_recipe_snapshots",
                        "order_item_recipe_usages","stock_documents","stock_document_items","inventory_balances","inventory_transactions","audit_logs"))
                    before.put(table,tableSnapshot(s,p,table));
                connection.setSchema(previousSchema);
            }
            var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                    .locations("classpath:db/migration").target("33").load();
            upgraded.migrate(); upgraded.validate();
            try(Connection connection=dataSource.getConnection();Statement s=connection.createStatement()) {
                for(var entry:before.entrySet()) {
                    if(!java.util.Set.of("inventory_balances","inventory_transactions").contains(entry.getKey()))
                        assertThat(tableSnapshot(s,p,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                }
                try(ResultSet rows=s.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(t)-'reserved_quantity' ORDER BY (to_jsonb(t)-'reserved_quantity')::text),'[]'::jsonb)::text FROM "+p+"inventory_balances t")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo(before.get("inventory_balances"));
                }
                try(ResultSet rows=s.executeQuery("SELECT coalesce(jsonb_agg(to_jsonb(t)-ARRAY['source_type','order_id','order_item_id','reservation_id','recipe_usage_id']::text[] ORDER BY t.id),'[]'::jsonb)::text FROM "+p+"inventory_transactions t")) {
                    rows.next(); assertThat(rows.getString(1)).isEqualTo(before.get("inventory_transactions"));
                }
                try(ResultSet rows=s.executeQuery("SELECT count(*),bool_and(status='SKIPPED' AND skip_reason='LEGACY_UNAVAILABLE' AND warehouse_id IS NULL) FROM "+p+"order_item_inventory_allocations")) {
                    rows.next(); assertThat(rows.getInt(1)).isEqualTo(2); assertThat(rows.getBoolean(2)).isTrue();
                }
                try(ResultSet rows=s.executeQuery("SELECT count(*) FROM "+p+"inventory_reservations")) { rows.next(); assertThat(rows.getInt(1)).isZero(); }
                try(ResultSet rows=s.executeQuery("SELECT quantity,reserved_quantity FROM "+p+"inventory_balances")) { rows.next(); assertThat(rows.getBigDecimal(1)).isEqualByComparingTo("1000"); assertThat(rows.getBigDecimal(2)).isZero(); }
                try(ResultSet rows=s.executeQuery("SELECT source_type,unit_cost FROM "+p+"inventory_transactions")) { rows.next(); assertThat(rows.getString(1)).isEqualTo("STOCK_DOCUMENT"); assertThat(rows.getBigDecimal(2)).isEqualByComparingTo("2"); }
                assertThatThrownBy(() -> s.execute("UPDATE "+p+"inventory_transactions SET unit_cost=9"))
                        .hasMessageContaining("append-only");
                assertThatThrownBy(() -> s.execute("DELETE FROM "+p+"inventory_transactions"))
                        .hasMessageContaining("append-only");
            }
        } finally { dropTestSchema(schema); }
    }

    @Test
    void v34BaselinesCurrentLowStockWithoutChangingBalancesLedgerAuditOrFeatures() throws Exception {
        String schema=TEST_SCHEMA_PREFIX+UUID.randomUUID().toString().replace("-","");
        createSchema(schema);
        String p="\""+schema+"\".";
        UUID tenant=UUID.randomUUID(),warehouse=UUID.randomUUID(),inactiveWarehouse=UUID.randomUUID();
        Map<String,String> before=new java.util.HashMap<>();
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("33").load().migrate();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                s.execute("INSERT INTO "+p+"restaurants(id,code,name,status) VALUES('"+tenant+"','V34_BASE','Baseline','ACTIVE')");
                s.execute("INSERT INTO "+p+"warehouses(id,restaurant_id,code,name,is_active,created_at,updated_at) VALUES "
                    +"('"+warehouse+"','"+tenant+"','MAIN','Main',true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP),"
                    +"('"+inactiveWarehouse+"','"+tenant+"','OFF','Off',false,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                for(int i=0;i<6;i++) {
                    UUID item=UUID.randomUUID();
                    s.execute("INSERT INTO "+p+"items(id,restaurant_id,name,item_type,base_unit,track_inventory,is_active,created_at,updated_at) VALUES('"
                        +item+"','"+tenant+"','Ingredient "+i+"','INGREDIENT','g',"+(i!=5)+","+(i!=1)+",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                    int quantity=i==2?21:10;int threshold=i==3?0:20;
                    s.execute("INSERT INTO "+p+"inventory_balances(restaurant_id,warehouse_id,item_id,quantity,min_quantity,created_at,updated_at) VALUES('"
                        +tenant+"','"+(i==4?inactiveWarehouse:warehouse)+"','"+item+"',"+quantity+","+threshold+",CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
                }
                for(String table:java.util.List.of("inventory_balances","inventory_transactions","audit_logs","features","package_features","restaurant_subscriptions"))
                    before.put(table,tableSnapshot(s,p,table));
            }
            var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").load();upgraded.migrate();upgraded.validate();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                for(var entry:before.entrySet())assertThat(tableSnapshot(s,p,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                try(ResultSet r=s.executeQuery("SELECT count(*),bool_and(status='OPEN' AND acknowledged_by IS NULL AND quantity_at_trigger=10 AND threshold_quantity=20 AND triggered_at=created_at) FROM "+p+"low_stock_alerts")) {
                    r.next();assertThat(r.getInt(1)).isEqualTo(2);assertThat(r.getBoolean(2)).isTrue();
                }
                try(ResultSet r=s.executeQuery("SELECT array_agg(r.code ORDER BY r.code)::text FROM "+p+"role_permissions rp JOIN "+p+"roles r ON r.id=rp.role_id JOIN "+p+"permissions p ON p.id=rp.permission_id WHERE p.code='INVENTORY_ALERT_ACKNOWLEDGE'")) {
                    r.next();assertThat(r.getString(1)).isEqualTo("{OWNER,SUPER_ADMIN}");
                }
            }
        } finally {dropTestSchema(schema);}
    }

    @Test
    void v35AddsEmptyStocktakingHistoryWithoutChangingExistingPhysicalData() throws Exception {
        String schema=TEST_SCHEMA_PREFIX+UUID.randomUUID().toString().replace("-","");createSchema(schema);
        String p="\""+schema+"\".";Map<String,String> before=new java.util.LinkedHashMap<>();
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("34").load().migrate();
            v35DocumentFixture(schema,"regular-key");
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                for(String table:java.util.List.of("items","warehouses","stock_documents","stock_document_items","inventory_balances",
                    "inventory_transactions","inventory_reservations","low_stock_alerts","audit_logs","features","packages","package_features","restaurant_subscriptions"))before.put(table,tableSnapshot(s,p,table));
            }
            var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();upgraded.migrate();upgraded.validate();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement()) {
                for(var entry:before.entrySet()) {
                    if(entry.getKey().equals("stock_documents")){
                        try(ResultSet r=s.executeQuery("select coalesce(jsonb_agg(to_jsonb(t)-'stock_audit_id' ORDER BY (to_jsonb(t)-'stock_audit_id')::text),'[]'::jsonb)::text from "+p+"stock_documents t")){r.next();assertThat(r.getString(1)).isEqualTo(entry.getValue());}
                    }else assertThat(tableSnapshot(s,p,entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue());
                }
                for(String table:java.util.List.of("stock_audits","stock_audit_items"))try(ResultSet r=s.executeQuery("select count(*) from "+p+table)){r.next();assertThat(r.getLong(1)).isZero();}
                try(ResultSet r=s.executeQuery("select string_agg(r.code,',' order by r.code) from "+p+"roles r join "+p+"role_permissions rp on rp.role_id=r.id join "+p+"permissions p on p.id=rp.permission_id where p.code='STOCK_AUDIT_MANAGE'")){r.next();assertThat(r.getString(1)).isEqualTo("OWNER,SUPER_ADMIN");}
            }
        }finally{dropTestSchema(schema);}
    }

    @Test
    void v35RejectsReservedManualNamespaceClearlyWithoutChangingOldKeys() throws Exception {
        String schema=TEST_SCHEMA_PREFIX+UUID.randomUUID().toString().replace("-","");createSchema(schema);String p="\""+schema+"\".";
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("34").load().migrate();
            v35DocumentFixture(schema," sys_stock_audit:reserved ");
            var upgrade=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
            assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("reserved SYS_STOCK_AUDIT: namespace");
            assertThat(tableExists(schema,"stock_audits")).isFalse();
            try(Connection c=dataSource.getConnection();Statement s=c.createStatement();ResultSet r=s.executeQuery("select idempotency_key from "+p+"stock_documents")){r.next();assertThat(r.getString(1)).isEqualTo(" sys_stock_audit:reserved ");}
        }finally{dropTestSchema(schema);}
    }

    private void v35DocumentFixture(String schema,String key)throws Exception {
        String p="\""+schema+"\".";UUID tenant=UUID.randomUUID(),actor=UUID.randomUUID(),warehouse=UUID.randomUUID(),ingredient=UUID.randomUUID(),document=UUID.randomUUID(),line=UUID.randomUUID();
        try(Connection c=dataSource.getConnection();Statement s=c.createStatement()){
            c.setSchema(schema);
            s.execute("insert into "+p+"restaurants(id,code,name,status) values('"+tenant+"','V35_KEEP','Keep','ACTIVE')");
            s.execute("insert into "+p+"users(id,restaurant_id,role_id,email,password_hash,name) select '"+actor+"','"+tenant+"',id,'v35@test.example','fixture','Owner' from "+p+"roles where restaurant_id is null and code='OWNER'");
            s.execute("insert into "+p+"items(id,restaurant_id,name,item_type,base_unit,cost_price,track_inventory,created_at,updated_at) values('"+ingredient+"','"+tenant+"','Ingredient','INGREDIENT','g',2,true,now(),now())");
            s.execute("insert into "+p+"warehouses(id,restaurant_id,code,name,is_default,is_active,created_at,updated_at) values('"+warehouse+"','"+tenant+"','MAIN','Main',true,true,now(),now())");
            s.execute("insert into "+p+"inventory_balances(restaurant_id,warehouse_id,item_id,quantity,min_quantity,created_at,updated_at) values('"+tenant+"','"+warehouse+"','"+ingredient+"',10,2,now(),now())");
            s.execute("insert into "+p+"stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,idempotency_key,creation_request_hash,created_by,confirmed_by,confirmed_at,line_count,total_quantity,total_cost,created_at,updated_at) values('"+document+"','"+tenant+"','"+warehouse+"','KEEP','RECEIPT','CONFIRMED','"+key+"',repeat('a',64),'"+actor+"','"+actor+"',now(),1,10,20,now(),now())");
            s.execute("insert into "+p+"stock_document_items(id,restaurant_id,stock_document_id,ingredient_id,ingredient_name,unit,quantity,unit_cost,total_cost,created_at,updated_at) values('"+line+"','"+tenant+"','"+document+"','"+ingredient+"','Old name','g',10,2,20,now(),now())");
            s.execute("insert into "+p+"inventory_transactions(restaurant_id,warehouse_id,item_id,transaction_type,stock_document_id,stock_document_item_id,quantity_change,before_quantity,after_quantity,unit,unit_cost,idempotency_key,created_by,created_at) values('"+tenant+"','"+warehouse+"','"+ingredient+"','RECEIPT','"+document+"','"+line+"',10,0,10,'g',2,'keep-ledger','"+actor+"',now())");
        }
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

    private boolean indexExists(String schema, String index) throws Exception {
        String sql = "SELECT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = ? AND indexname = ?)";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, schema);
            statement.setString(2, index);
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
