package com.possaas.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
class OrderReferenceCostMigrationIntegrationTest {
    private static final String PREFIX = "it_reference_cost_";
    private static final String CONTEXT_SCHEMA = newSchema();
    private static final List<String> HEADER_COLUMNS = List.of("cost_snapshot_version", "cost_status",
        "cost_unavailable_reason", "unit_cost_snapshot", "total_cost_snapshot");
    private static final List<String> USAGE_COLUMNS = List.of("cost_configured_snapshot", "unit_cost_snapshot",
        "line_cost_snapshot");
    @Autowired DataSource dataSource;

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
    void cleanupContext() throws Exception { dropSchema(CONTEXT_SCHEMA); }

    @Test
    void v38PreservesV37SnapshotsReservationsBalancesAndLedgerWithoutRepricingHistory() throws Exception {
        try (TestSchema schema = schemaAt("37")) {
            Fixture fixture = fixture(schema.name);
            History consumed = History.newIds(), reserved = History.newIds(), released = History.newIds();
            transaction(schema.name, sql -> {
                history(sql, fixture, consumed, true, "2", false);
                history(sql, fixture, reserved, true, "2", false);
                history(sql, fixture, released, true, "2", false);
                consume(sql, fixture, consumed, "NULL");
                sql.execute("UPDATE inventory_balances SET quantity=99.875,reserved_quantity=.250");
                header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.legacy());
                header(sql, fixture, line(sql, fixture), "FEATURE_DISABLED", Cost.legacy());
                header(sql, fixture, line(sql, fixture), "LEGACY_UNAVAILABLE", Cost.legacy());
            });
            Map<String, String> before = snapshots(schema.name, false);

            Flyway upgrade = migrations(schema.name, "38");
            assertThat(upgrade.migrate().migrationsExecuted).isEqualTo(1);
            upgrade.validate();
            assertThat(snapshots(schema.name, true)).isEqualTo(before);
            transaction(schema.name, sql -> {
                assertThat(columnShape(sql, "order_item_recipe_snapshots", HEADER_COLUMNS)).isEqualTo(
                    "cost_snapshot_version:smallint:false,cost_status:character varying(30):false,"
                    + "cost_unavailable_reason:character varying(60):false,unit_cost_snapshot:numeric(38,2):false,"
                    + "total_cost_snapshot:numeric(38,2):false");
                assertThat(columnShape(sql, "order_item_recipe_usages", USAGE_COLUMNS)).isEqualTo(
                    "cost_configured_snapshot:boolean:false,unit_cost_snapshot:numeric(14,2):false,line_cost_snapshot:numeric(38,2):false");
                assertThat(value(sql, "SELECT bool_and(cost_snapshot_version IS NULL AND cost_status IS NULL"
                    + " AND cost_unavailable_reason IS NULL AND unit_cost_snapshot IS NULL AND total_cost_snapshot IS NULL)"
                    + " FROM order_item_recipe_snapshots")).isEqualTo("t");
                assertThat(value(sql, "SELECT bool_and(cost_configured_snapshot IS NULL AND unit_cost_snapshot IS NULL"
                    + " AND line_cost_snapshot IS NULL) FROM order_item_recipe_usages")).isEqualTo("t");
            });
            rejects(schema.name, sql -> ledger(sql, fixture, reserved, "2"), "frozen usage cost");
            rejects(schema.name, sql -> usage(sql, fixture, reserved.snapshot, fixture.otherIngredient,
                false, ".125", "NULL", "NULL", "NULL"), "immutable");
            rejects(schema.name, sql -> sql.execute("UPDATE order_item_recipe_snapshots SET total_cost_snapshot=1"), "immutable");
            rejects(schema.name, sql -> sql.execute("DELETE FROM order_item_recipe_usages"), "immutable");
            assertThatThrownBy(() -> transaction(schema.name,
                sql -> sql.execute("UPDATE inventory_transactions SET unit_cost=99"))).hasMessageContaining("append-only");
            assertThatThrownBy(() -> transaction(schema.name,
                sql -> sql.execute("DELETE FROM inventory_transactions"))).hasMessageContaining("append-only");

            transaction(schema.name, sql -> {
                // Today's reference price must not affect the legacy consumption decision.
                sql.execute("UPDATE items SET cost_price=99,cost_configured=true WHERE id='" + fixture.ingredient + "'");
                sql.execute(ledgerSql(fixture, reserved, "NULL").replace("-.125,100,99.875", "-.125,99.875,99.750"));
                sql.execute("UPDATE order_item_inventory_allocations SET status='CONSUMED',consumed_at=now() WHERE id='"
                    + reserved.allocation + "'");
                sql.execute("UPDATE order_item_inventory_allocations SET status='RELEASED',released_at=now() WHERE id='"
                    + released.allocation + "'");
                sql.execute("UPDATE inventory_balances SET quantity=99.750,reserved_quantity=0");
                assertThat(value(sql, "SELECT count(*) FROM inventory_transactions WHERE transaction_type='RECIPE_USE'"
                    + " AND unit_cost IS NULL")).isEqualTo("2");
                assertThat(value(sql, "SELECT status FROM order_item_inventory_allocations WHERE id='"
                    + consumed.allocation + "'")).isEqualTo("CONSUMED");
                assertThat(value(sql, "SELECT status FROM order_item_inventory_allocations WHERE id='"
                    + released.allocation + "'")).isEqualTo("RELEASED");
            });
        }
    }

    @Test
    void freshHeadersRequireVersionedSourceStatusReasonAndExactManualAmounts() throws Exception {
        try (TestSchema schema = schemaAt("38")) {
            Fixture fixture = fixture(schema.name);
            for (String status : List.of("RECIPE_DISABLED", "FEATURE_DISABLED", "CAPTURED")) {
                rejects(schema.name, sql -> header(sql, fixture, line(sql, fixture), status, Cost.legacy()), "version 1");
            }
            for (Cost invalid : List.of(
                    new Cost("2", "'COMPLETE'", "NULL", "1", "3"),
                    new Cost("1", "NULL", "NULL", "1", "3"),
                    new Cost("1", "'LEGACY_UNAVAILABLE'", "NULL", "NULL", "NULL"),
                    new Cost("1", "'NOT_SUBMITTED'", "NULL", "NULL", "NULL"),
                    Cost.complete("-1", "-3"), Cost.complete("1", "2.99"), Cost.complete("'NaN'", "'NaN'"),
                    Cost.complete("NULL", "3"), Cost.complete("1", "NULL"),
                    new Cost("1", "'COMPLETE'", "'MANUAL_COST_NOT_CONFIGURED'", "1", "3"),
                    Cost.incomplete("NULL"), Cost.incomplete("'INGREDIENT_COST_NOT_CONFIGURED'"),
                    new Cost("1", "'INCOMPLETE'", "'MANUAL_COST_NOT_CONFIGURED'", "0", "0"),
                    Cost.unavailable())) {
                rejects(schema.name, sql -> header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", invalid),
                    "ck_order_reference_cost_snapshot");
            }
            rejects(schema.name, sql -> header(sql, fixture, line(sql, fixture), "FEATURE_DISABLED",
                Cost.complete("0", "0")), "ck_order_reference_cost_snapshot");
            rejects(schema.name, sql -> header(sql, fixture, line(sql, fixture), "NO_RECIPE",
                Cost.unavailable()), "ck_order_reference_cost_snapshot");
            rejects(schema.name, sql -> sql.execute("INSERT INTO order_item_recipe_snapshots(restaurant_id,order_item_id,"
                + "menu_item_id_snapshot,snapshot_status,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at,"
                + "recipe_enabled_snapshot,cost_source_snapshot,cost_snapshot_version,cost_status,unit_cost_snapshot,total_cost_snapshot) VALUES('"
                + fixture.tenant + "','" + line(sql, fixture) + "','" + fixture.menu
                + "','RECIPE_DISABLED',3,'portion',now(),now(),false,'RECIPE',1,'COMPLETE',0,0)"), "violates check constraint");
            Fixture foreign = fixture(schema.name);
            String[] foreignLine = new String[1];
            transaction(schema.name, sql -> foreignLine[0] = line(sql, foreign));
            assertThatThrownBy(() -> transaction(schema.name, sql -> header(sql, fixture, foreignLine[0],
                "RECIPE_DISABLED", Cost.complete("0", "0"))))
                .isInstanceOf(SQLException.class).hasMessageContaining("fk_order_recipe_snapshot_line");
            assertThatThrownBy(() -> transaction(schema.name, sql -> {
                String snapshot = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete("0", "0"));
                usage(sql, fixture, snapshot, foreign.ingredient, true, ".125", "true", "0", "0");
            })).isInstanceOf(SQLException.class).hasMessageContaining("fk_order_recipe_usage_ingredient");
            transaction(schema.name, sql -> {
                header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.complete("1.23", "3.69"));
                header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.complete("0", "0"));
                header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.incomplete("'MANUAL_COST_NOT_CONFIGURED'"));
                header(sql, fixture, line(sql, fixture), "FEATURE_DISABLED", Cost.unavailable());
                header(sql, fixture, line(sql, fixture), "LEGACY_UNAVAILABLE", Cost.legacy());
            });
            rejects(schema.name, sql -> {
                String snapshot = header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.complete("0", "0"));
                usage(sql, fixture, snapshot, fixture.ingredient, true, ".125", "true", "0", "0");
            }, "immutable");
            rejects(schema.name, sql -> {
                String snapshot = header(sql, fixture, line(sql, fixture), "RECIPE_DISABLED", Cost.complete("0", "0"));
                sql.execute("UPDATE order_item_recipe_snapshots SET cost_status='INCOMPLETE' WHERE id='" + snapshot + "'");
            }, "immutable");
        }
    }

    @Test
    void deferredRecipeGuardValidatesAllUsageCostsAndAuthoritativeRoundedSum() throws Exception {
        try (TestSchema schema = schemaAt("38")) {
            Fixture fixture = fixture(schema.name);
            for (String[] invalid : List.of(
                    new String[] {"NULL", "NULL", "NULL"},
                    new String[] {"true", "NULL", "NULL"},
                    new String[] {"true", "-1", "-.13"},
                    new String[] {"true", "'NaN'", "'NaN'"},
                    new String[] {"true", "2", ".26"},
                    new String[] {"false", "0", "0"},
                    new String[] {"false", "NULL", "0"})) {
                rejects(schema.name, sql -> {
                    String snapshot = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete(".08", ".25"));
                    usage(sql, fixture, snapshot, fixture.ingredient, true, ".125", invalid[0], invalid[1], invalid[2]);
                }, "ck_order_reference_cost_usage");
            }
            rejects(schema.name, sql -> header(sql, fixture, line(sql, fixture), "CAPTURED",
                Cost.complete("0", "0")), "requires ingredient usages");
            for (Cost invalid : List.of(Cost.complete(".09", ".25"), Cost.complete(".08", ".24"),
                    Cost.incomplete("'INGREDIENT_COST_NOT_CONFIGURED'"))) {
                rejects(schema.name, sql -> {
                    String snapshot = header(sql, fixture, line(sql, fixture), "CAPTURED", invalid);
                    usage(sql, fixture, snapshot, fixture.ingredient, true, ".125", "true", "2", ".25");
                }, "ck_order_reference_cost_aggregate");
            }
            rejects(schema.name, sql -> {
                String snapshot = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete(".08", ".25"));
                usage(sql, fixture, snapshot, fixture.ingredient, true, ".125", "true", "2", ".25");
                usage(sql, fixture, snapshot, fixture.otherIngredient, false, ".125", "false", "NULL", "NULL");
            }, "ck_order_reference_cost_aggregate");
            transaction(schema.name, sql -> {
                // HALF_UP at the usage level; .00 display unit multiplied by 3 does not recover .01 total.
                String rounded = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete("0", ".01"));
                usage(sql, fixture, rounded, fixture.ingredient, true, ".001", "true", "5", ".01");
                // Both tracked and untracked known usages participate in the authoritative sum.
                String all = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete(".17", ".50"));
                usage(sql, fixture, all, fixture.ingredient, true, ".125", "true", "2", ".25");
                usage(sql, fixture, all, fixture.otherIngredient, false, ".125", "true", "2", ".25");
                String zero = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.complete("0", "0"));
                usage(sql, fixture, zero, fixture.ingredient, true, ".125", "true", "0", "0");
                String mixed = header(sql, fixture, line(sql, fixture), "CAPTURED", Cost.incomplete("'INGREDIENT_COST_NOT_CONFIGURED'"));
                usage(sql, fixture, mixed, fixture.ingredient, true, ".125", "true", "2", ".25");
                usage(sql, fixture, mixed, fixture.otherIngredient, false, ".125", "false", "NULL", "NULL");
            });
        }
    }

    @Test
    void recipeUseRequiresExactFrozenKnownZeroOrUnknownPriceAndPreservesIdentityHistoryGuards() throws Exception {
        try (TestSchema schema = schemaAt("38")) {
            Fixture fixture = fixture(schema.name);
            for (String configuredPrice : List.of("2", "0", "NULL")) {
                History history = History.newIds();
                transaction(schema.name, sql -> history(sql, fixture, history, false, configuredPrice, true));
                String expected = configuredPrice;
                for (String invalid : List.of("NULL", "0", "2", "3")) {
                    if (!invalid.equals(expected)) {
                        rejects(schema.name, sql -> consume(sql, fixture, history, invalid), "frozen usage cost");
                    }
                }
                rejects(schema.name, sql -> sql.execute(ledgerSql(fixture, history, expected).replace("'g',", "'kg',")),
                    "exactly match its reservation");
                rejects(schema.name, sql -> sql.execute(ledgerSql(fixture, history, expected).replace("-.125,100,99.875", "-.126,100,99.874")),
                    "exactly match its reservation");
                // Inserting a valid ledger entry without its CONSUMED transition fails at commit.
                rejects(schema.name, sql -> ledger(sql, fixture, history, expected), "consumed allocation at commit");
                transaction(schema.name, sql -> consume(sql, fixture, history, expected));
                assertThatThrownBy(() -> transaction(schema.name, sql -> ledger(sql, fixture, history, expected)))
                    .isInstanceOf(SQLException.class).hasMessageContaining("uq_recipe_use_reservation");
            }
            rejects(schema.name, sql -> sql.execute("DELETE FROM inventory_reservations"), "immutable");
            rejects(schema.name, sql -> sql.execute("DELETE FROM order_item_inventory_allocations"), "cannot be deleted");
            transaction(schema.name, sql -> {
                assertThat(value(sql, "SELECT count(*) FROM inventory_transactions")).isEqualTo("3");
                assertThat(value(sql, "SELECT count(*) FROM inventory_transactions WHERE unit_cost=0")).isEqualTo("1");
                assertThat(value(sql, "SELECT count(*) FROM inventory_transactions WHERE unit_cost IS NULL")).isEqualTo("1");
            });
        }
    }

    private Fixture fixture(String schema) throws Exception {
        Fixture fixture = new Fixture(id(), id(), id(), id(), id(), id(), id(), id());
        transaction(schema, sql -> {
            sql.execute("INSERT INTO restaurants(id,code,name,status) VALUES('" + fixture.tenant + "','" + fixture.tenant + "','Reference costs','ACTIVE')");
            sql.execute("INSERT INTO users(id,restaurant_id,role_id,email,password_hash,name) SELECT '" + fixture.actor + "','"
                + fixture.tenant + "',id,'" + fixture.actor + "@example.test','fixture','Owner' FROM roles WHERE restaurant_id IS NULL AND code='OWNER'");
            sql.execute("INSERT INTO items(id,restaurant_id,name,item_type,base_unit,sale_price,cost_price,cost_configured,recipe_enabled,track_inventory,created_at,updated_at) VALUES "
                + "('" + fixture.menu + "','" + fixture.tenant + "','Food','MENU_ITEM','portion',100,0,false,true,false,now(),now()),"
                + "('" + fixture.ingredient + "','" + fixture.tenant + "','Ingredient','INGREDIENT','g',0,2,true,false,true,now(),now()),"
                + "('" + fixture.otherIngredient + "','" + fixture.tenant + "','Other','INGREDIENT','g',0,0,false,false,false,now(),now())");
            sql.execute("INSERT INTO recipes(id,restaurant_id,menu_item_id,recipe_version,yield_quantity,yield_unit,is_active,created_at,updated_at) VALUES('"
                + fixture.recipe + "','" + fixture.tenant + "','" + fixture.menu + "',1,3,'portion',true,now(),now())");
            sql.execute("INSERT INTO orders(id,restaurant_id,order_code,service_type,source_channel,status,currency_code,version,idempotency_key,request_hash,confirmed_at,created_at,updated_at) VALUES('"
                + fixture.order + "','" + fixture.tenant + "','" + fixture.order + "','TAKEAWAY','CASHIER','CONFIRMED','VND',9,'"
                + fixture.order + "',repeat('a',64),now(),now(),now())");
            sql.execute("INSERT INTO warehouses(id,restaurant_id,code,name,is_default,is_active,created_at,updated_at) VALUES('"
                + fixture.warehouse + "','" + fixture.tenant + "','MAIN','Main',true,true,now(),now())");
            sql.execute("INSERT INTO inventory_balances(restaurant_id,warehouse_id,item_id,quantity,reserved_quantity,min_quantity,created_at,updated_at) VALUES('"
                + fixture.tenant + "','" + fixture.warehouse + "','" + fixture.ingredient + "',100,0,0,now(),now())");
        });
        return fixture;
    }

    private static String line(Statement sql, Fixture fixture) throws Exception {
        String line = id();
        line(sql, fixture, line);
        return line;
    }

    private static void line(Statement sql, Fixture fixture, String line) throws Exception {
        sql.execute("INSERT INTO order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,line_number,sent_to_kitchen_at,created_at,updated_at) SELECT '"
            + line + "','" + fixture.tenant + "','" + fixture.order + "','" + fixture.menu
            + "','Food','portion',3,100,300,'PENDING',coalesce(max(line_number),0)+1,now(),now(),now() FROM order_items WHERE order_id='" + fixture.order + "'");
    }

    private static String header(Statement sql, Fixture fixture, String line, String status, Cost cost) throws Exception {
        String snapshot = id();
        header(sql, fixture, line, snapshot, status, cost);
        return snapshot;
    }

    private static void header(Statement sql, Fixture fixture, String line, String snapshot, String status, Cost cost) throws Exception {
        boolean legacy = status.equals("LEGACY_UNAVAILABLE"), manual = status.equals("RECIPE_DISABLED");
        String recipe = status.equals("CAPTURED") ? "'" + fixture.recipe + "',1,3,'portion'" : "NULL,NULL,NULL,NULL";
        String monetaryColumns = cost.version == null ? "" : ",cost_snapshot_version,cost_status,cost_unavailable_reason,unit_cost_snapshot,total_cost_snapshot";
        String monetaryValues = cost.version == null ? "" : "," + String.join(",", cost.version, cost.status, cost.reason, cost.unit, cost.total);
        sql.execute("INSERT INTO order_item_recipe_snapshots(id,restaurant_id,order_item_id,menu_item_id_snapshot,snapshot_status,recipe_id,recipe_version,yield_quantity,yield_unit,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot"
            + monetaryColumns + ") VALUES('" + snapshot + "','" + fixture.tenant + "','" + line + "','" + fixture.menu + "','"
            + status + "'," + recipe + ",3,'portion'," + (legacy ? "NULL" : "now()") + ",now(),"
            + (legacy ? "NULL,NULL" : manual ? "false,'MANUAL'" : "true,'RECIPE'") + monetaryValues + ")");
    }

    private static void usage(Statement sql, Fixture fixture, String snapshot, String ingredient,
            boolean tracked, String required, String configured, String price, String amount) throws Exception {
        usage(sql, fixture, snapshot, id(), ingredient, tracked, required, configured, price, amount, false);
    }

    private static void usage(Statement sql, Fixture fixture, String snapshot, String usage, String ingredient,
            boolean tracked, String required, String configured, String price, String amount, boolean legacy) throws Exception {
        sql.execute("INSERT INTO order_item_recipe_usages(id,restaurant_id,recipe_snapshot_id,ingredient_id,ingredient_name_snapshot,ingredient_unit_snapshot,recipe_quantity_snapshot,waste_percent_snapshot,track_inventory_snapshot,required_quantity,created_at"
            + (legacy ? "" : ",cost_configured_snapshot,unit_cost_snapshot,line_cost_snapshot") + ") VALUES('" + usage + "','"
            + fixture.tenant + "','" + snapshot + "','" + ingredient + "','Ingredient','g'," + required + ",0," + tracked + "," + required
            + ",now()" + (legacy ? "" : "," + configured + "," + price + "," + amount) + ")");
    }

    private static void history(Statement sql, Fixture fixture, History history, boolean legacy,
            String configuredPrice, boolean partial) throws Exception {
        boolean known = !configuredPrice.equals("NULL");
        String amount = configuredPrice.equals("2") ? ".25" : "0";
        Cost cost = legacy ? Cost.legacy() : partial || !known ? Cost.incomplete("'INGREDIENT_COST_NOT_CONFIGURED'")
            : Cost.complete(configuredPrice.equals("2") ? ".08" : "0", amount);
        line(sql, fixture, history.line);
        header(sql, fixture, history.line, history.snapshot, "CAPTURED", cost);
        usage(sql, fixture, history.snapshot, history.usage, fixture.ingredient, true, ".125",
            Boolean.toString(known), configuredPrice, known ? amount : "NULL", legacy);
        if (partial) usage(sql, fixture, history.snapshot, fixture.otherIngredient, false, ".125", "false", "NULL", "NULL");
        sql.execute("INSERT INTO order_item_inventory_allocations(id,restaurant_id,order_id,order_item_id,recipe_snapshot_id,status,warehouse_id,order_quantity_snapshot,order_unit_snapshot,reserved_at,created_at,updated_at) VALUES('"
            + history.allocation + "','" + fixture.tenant + "','" + fixture.order + "','" + history.line + "','" + history.snapshot
            + "','RESERVED','" + fixture.warehouse + "',3,'portion',now(),now(),now())");
        sql.execute("INSERT INTO inventory_reservations(id,restaurant_id,allocation_id,recipe_snapshot_id,recipe_usage_id,warehouse_id,ingredient_id,quantity,ingredient_name_snapshot,ingredient_unit_snapshot,created_at) VALUES('"
            + history.reservation + "','" + fixture.tenant + "','" + history.allocation + "','" + history.snapshot + "','" + history.usage
            + "','" + fixture.warehouse + "','" + fixture.ingredient + "',.125,'Ingredient','g',now())");
    }

    private static String ledgerSql(Fixture fixture, History history, String price) {
        return "INSERT INTO inventory_transactions(restaurant_id,warehouse_id,item_id,transaction_type,source_type,order_id,order_item_id,reservation_id,recipe_usage_id,quantity_change,before_quantity,after_quantity,unit,unit_cost,idempotency_key,created_by,created_at) VALUES('"
            + fixture.tenant + "','" + fixture.warehouse + "','" + fixture.ingredient + "','RECIPE_USE','ORDER_ITEM','"
            + fixture.order + "','" + history.line + "','" + history.reservation + "','" + history.usage
            + "',-.125,100,99.875,'g'," + price + ",'" + id() + "','" + fixture.actor + "',now())";
    }

    private static void ledger(Statement sql, Fixture fixture, History history, String price) throws Exception {
        sql.execute(ledgerSql(fixture, history, price));
    }

    private static void consume(Statement sql, Fixture fixture, History history, String price) throws Exception {
        ledger(sql, fixture, history, price);
        sql.execute("UPDATE order_item_inventory_allocations SET status='CONSUMED',consumed_at=now() WHERE id='" + history.allocation + "'");
    }

    private Map<String, String> snapshots(String schema, boolean upgraded) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        transaction(schema, sql -> {
            for (String table : List.of("restaurants", "users", "items", "recipes", "orders", "order_items",
                    "order_item_recipe_snapshots", "order_item_recipe_usages", "order_item_inventory_allocations",
                    "inventory_reservations", "inventory_balances", "inventory_transactions", "audit_logs",
                    "packages", "features", "package_features", "permissions", "role_permissions")) {
                List<String> columns = !upgraded ? List.of() : table.equals("order_item_recipe_snapshots") ? HEADER_COLUMNS
                    : table.equals("order_item_recipe_usages") ? USAGE_COLUMNS : List.of();
                String row = "to_jsonb(t)" + (columns.isEmpty() ? "" : "-ARRAY['" + String.join("','", columns) + "']::text[]");
                result.put(table, value(sql, "SELECT coalesce(jsonb_agg(" + row + " ORDER BY (" + row + ")::text),'[]'::jsonb)::text FROM " + table + " t"));
            }
        });
        return result;
    }

    private void rejects(String schema, SqlWork work, String message) {
        assertThatThrownBy(() -> transaction(schema, work)).isInstanceOf(SQLException.class)
            .hasMessageContaining(message).satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23514"));
    }

    private void transaction(String schema, SqlWork work) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            connection.setSchema(schema);
            connection.setAutoCommit(false);
            try {
                work.run(sql);
                connection.commit();
            } catch (Exception error) {
                connection.rollback();
                throw error;
            }
        }
    }

    private TestSchema schemaAt(String version) throws Exception {
        String schema = newSchema();
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA \"" + schema + "\"");
        }
        try {
            migrations(schema, version).migrate();
            return new TestSchema(schema);
        } catch (Exception error) {
            dropSchema(schema);
            throw error;
        }
    }

    private Flyway migrations(String schema, String target) {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").target(target).load();
    }

    private void dropSchema(String schema) throws Exception {
        if (!schema.matches("it_reference_cost_[0-9a-f]{32}")) throw new IllegalArgumentException("Not an owned test schema: " + schema);
        try (Connection connection = dataSource.getConnection(); Statement sql = connection.createStatement()) {
            sql.execute("DROP SCHEMA \"" + schema + "\" CASCADE");
        }
    }

    private static String value(Statement sql, String query) throws Exception {
        try (ResultSet rows = sql.executeQuery(query)) { rows.next(); return rows.getString(1); }
    }

    private static String columnShape(Statement sql, String table, List<String> columns) throws Exception {
        return value(sql, "SELECT string_agg(attname||':'||format_type(atttypid,atttypmod)||':'||attnotnull::text,',' ORDER BY attnum)"
            + " FROM pg_attribute WHERE attrelid='" + table + "'::regclass AND attname IN ('" + String.join("','", columns) + "')");
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static String newSchema() { return PREFIX + UUID.randomUUID().toString().replace("-", ""); }
    @FunctionalInterface private interface SqlWork { void run(Statement sql) throws Exception; }
    private final class TestSchema implements AutoCloseable {
        private final String name;
        private TestSchema(String name) { this.name = name; }
        @Override public void close() throws Exception { dropSchema(name); }
    }
    private record Fixture(String tenant, String actor, String menu, String ingredient,
        String otherIngredient, String recipe, String warehouse, String order) {}
    private record History(String line, String snapshot, String usage, String allocation, String reservation) {
        private static History newIds() { return new History(id(), id(), id(), id(), id()); }
    }
    private record Cost(String version, String status, String reason, String unit, String total) {
        private static Cost legacy() { return new Cost(null, null, null, null, null); }
        private static Cost complete(String unit, String total) { return new Cost("1", "'COMPLETE'", "NULL", unit, total); }
        private static Cost incomplete(String reason) { return new Cost("1", "'INCOMPLETE'", reason, "NULL", "NULL"); }
        private static Cost unavailable() { return new Cost("1", "'UNAVAILABLE'", "'RECIPE_FEATURE_DISABLED'", "NULL", "NULL"); }
    }
}
