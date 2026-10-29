package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.entity.ServiceType;
import com.possaas.modules.order.entity.SourceChannel;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.report.dto.ReportGranularity;
import com.possaas.modules.report.repository.ReferenceMarginReportRepository;
import com.possaas.modules.report.service.ReportQueryContext;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false", "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceMarginRepositoryIntegrationTest {
    private static final String SCHEMA = "it_margin_repo_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-15T08:00:00Z");
    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base = requireTestEnvironment("DB_URL");
        String username = requireTestEnvironment("DB_USERNAME");
        String password = requireTestEnvironment("DB_PASSWORD");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }
    private static String requireTestEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicitly set to a dedicated PostgreSQL test database");
        }
        return value;
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired ReferenceMarginReportRepository repository;
    @Autowired PlatformTransactionManager transactions;
    @AfterAll void cleanup() {
        if (!SCHEMA.matches("it_margin_repo_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test void aggregatesWholeOrdersWithoutDoublingAndKeepsUnknownCostsOutOfCohort() {
        var tenant = restaurant();
        var one = order(tenant, "100", "10", "5", "7");
        line(tenant, one, 1, "COMPLETE", "0", "SERVED");
        line(tenant, one, 2, "COMPLETE", "20", "SERVED");
        line(tenant, one, 3, "NONE", "0", "CANCELLED");
        var two = order(tenant, "100", "10", "5", "7");
        line(tenant, two, 1, "COMPLETE", "15", "SERVED");
        line(tenant, two, 2, "INCOMPLETE", "0", "SERVED");
        var three = order(tenant, "100", "10", "5", "7");
        line(tenant, three, 1, "UNAVAILABLE", "0", "SERVED");
        var ctx = context(tenant);
        repository.assertConsistent(ctx);
        var a = repository.aggregate(ctx);
        assertThat(a.completedOrderCount()).isEqualTo(3);
        assertThat(a.fullyCostedOrderCount()).isEqualTo(1);
        assertThat(a.subtotalAmount()).isEqualByComparingTo("300");
        assertThat(a.salesAmount()).isEqualByComparingTo("306");
        assertThat(a.coveredNetSalesAmount()).isEqualByComparingTo("90");
        assertThat(a.coveredReferenceCostAmount()).isEqualByComparingTo("20");
        assertThat(a.incompleteOrderCount()).isEqualTo(1);
        assertThat(a.incompleteItemCount()).isEqualTo(1);
        assertThat(a.unavailableOrderCount()).isEqualTo(1);
        assertThat(repository.count(ctx, "EXCLUDED")).isEqualTo(2);
        assertThat(repository.page(ctx, "COMPLETE", 0, 20, "completedAt", "desc")).hasSize(1);
        assertThat(repository.page(ctx, "EXCLUDED", 0, 20, "referenceCostAmount", "desc"))
            .allSatisfy(row -> assertThat(row.referenceCostAmount()).isNull());
        var buckets = repository.buckets(ctx, ReportGranularity.DAY);
        assertThat(buckets).hasSize(1);
        assertThat(buckets.getFirst().aggregate()).isEqualTo(a);
        assertThat(repository.aggregate(context(restaurant())).completedOrderCount()).isZero();
    }

    @Test void detectsMissingSubmittedHeaderBeforeFilteringOrPagination() {
        var tenant = restaurant();
        var order = order(tenant, "100", "0", "0", "0");
        line(tenant, order, 1, "NONE", "0", "SERVED");
        assertThatThrownBy(() -> repository.assertConsistent(context(tenant)))
            .isInstanceOf(ConflictException.class).hasMessageContaining("snapshot");
    }

    @Test void legacyAndNoEligibleRemainVisibleAndExcluded() {
        var tenant = restaurant();
        var legacy = order(tenant, "100", "0", "0", "0");
        line(tenant, legacy, 1, "LEGACY", "0", "SERVED");
        order(tenant, "100", "0", "0", "0");
        var ctx = context(tenant);
        repository.assertConsistent(ctx);
        var a = repository.aggregate(ctx);
        assertThat(a.completedOrderCount()).isEqualTo(2);
        assertThat(a.fullyCostedOrderCount()).isZero();
        assertThat(a.legacyOrderCount()).isEqualTo(1);
        assertThat(a.legacyItemCount()).isEqualTo(1);
        assertThat(a.noEligibleOrderCount()).isEqualTo(1);
    }

    @Test void recipeSnapshotUsesAuthoritativeTotalRatherThanRoundedUnitAndDoesNotMultiplyUsages() {
        var tenant = restaurant();
        var order = order(tenant, "100", "0", "0", "0");
        new TransactionTemplate(transactions).executeWithoutResult(tx -> recipeLine(tenant, order));
        var ctx = context(tenant);
        repository.assertConsistent(ctx);
        var aggregate = repository.aggregate(ctx);
        assertThat(aggregate.fullyCostedOrderCount()).isEqualTo(1);
        assertThat(aggregate.coveredReferenceCostAmount()).isEqualByComparingTo("0.01");
        assertThat(repository.page(ctx, "ALL", 0, 20, "completedAt", "desc").getFirst().referenceCostAmount())
            .isEqualByComparingTo("0.01");
    }

    @Test void modifiedLineQuantityOrUnitIsAConflict() {
        var tenant = restaurant();
        var order = order(tenant, "100", "0", "0", "0");
        line(tenant, order, 1, "COMPLETE", "10", "SERVED");
        jdbc.update("UPDATE order_items SET quantity=2, unit='bowl' WHERE restaurant_id=? AND order_id=?", tenant.getId(), order);
        assertThatThrownBy(() -> repository.assertConsistent(context(tenant)))
            .isInstanceOf(ConflictException.class);
    }

    @Test void filtersCompletedAtCurrencyServiceAndChannelBeforeAggregating() {
        var tenant = restaurant();
        var included = order(tenant, "100", "0", "0", "0");
        line(tenant, included, 1, "COMPLETE", "10", "SERVED");
        var otherCurrency = order(tenant, "200", "0", "0", "0");
        line(tenant, otherCurrency, 1, "COMPLETE", "20", "SERVED");
        jdbc.update("UPDATE orders SET currency_code='USD' WHERE id=?", otherCurrency);
        var otherChannel = order(tenant, "300", "0", "0", "0");
        line(tenant, otherChannel, 1, "COMPLETE", "30", "SERVED");
        jdbc.update("UPDATE orders SET source_channel='WAITER' WHERE id=?", otherChannel);
        var outside = order(tenant, "400", "0", "0", "0");
        line(tenant, outside, 1, "COMPLETE", "40", "SERVED");
        jdbc.update("UPDATE orders SET completed_at=? WHERE id=?", Timestamp.from(Instant.parse("2026-10-16T00:00:00Z")), outside);
        var ctx = new ReportQueryContext(tenant.getId(), LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15"),
            ZoneId.of("UTC"), Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-16T00:00:00Z"),
            "VND", ServiceType.TAKEAWAY, SourceChannel.CASHIER, NOW);
        repository.assertConsistent(ctx);
        assertThat(repository.aggregate(ctx).completedOrderCount()).isEqualTo(1);
        assertThat(repository.aggregate(ctx).subtotalAmount()).isEqualByComparingTo("100");
    }

    private void recipeLine(Restaurant restaurant, UUID order) {
        UUID menu = UUID.randomUUID(), ingredient = UUID.randomUUID(), ingredient2 = UUID.randomUUID();
        UUID recipe = UUID.randomUUID(), line = UUID.randomUUID(), header = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO items(id,restaurant_id,name,item_type,base_unit,created_at,updated_at)
            VALUES(?,?,'Dish','MENU_ITEM','portion',?,?), (?,?,'Ingredient','INGREDIENT','g',?,?),
                  (?,?,'Another','INGREDIENT','g',?,?)
            """, menu, restaurant.getId(),Timestamp.from(NOW),Timestamp.from(NOW),
            ingredient,restaurant.getId(),Timestamp.from(NOW),Timestamp.from(NOW),
            ingredient2,restaurant.getId(),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO recipes(id,restaurant_id,menu_item_id,recipe_version,yield_quantity,yield_unit,is_active,created_at,updated_at)
            VALUES(?,?,?,1,3,'portion',true,?,?)
            """,recipe,restaurant.getId(),menu,Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO order_items(id,restaurant_id,order_id,item_id,item_name,unit,quantity,unit_price,line_total,status,
                sent_to_kitchen_at,created_at,updated_at,line_number)
            VALUES(?,?,?,?,'Dish','portion',3,100,300,'SERVED',?,?,?,1)
            """,line,restaurant.getId(),order,menu,Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO order_item_recipe_snapshots(id,restaurant_id,order_item_id,menu_item_id_snapshot,snapshot_status,
                recipe_id,recipe_version,yield_quantity,yield_unit,order_quantity_snapshot,order_unit_snapshot,
                captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot,cost_snapshot_version,cost_status,
                unit_cost_snapshot,total_cost_snapshot)
            VALUES(?,?,?,?,'CAPTURED',?,1,3,'portion',3,'portion',?,?,true,'RECIPE',1,'COMPLETE',0,0.01)
            """,header,restaurant.getId(),line,menu,recipe,Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("""
            INSERT INTO order_item_recipe_usages(restaurant_id,recipe_snapshot_id,ingredient_id,ingredient_name_snapshot,
              ingredient_unit_snapshot,recipe_quantity_snapshot,waste_percent_snapshot,track_inventory_snapshot,
              required_quantity,created_at,cost_configured_snapshot,unit_cost_snapshot,line_cost_snapshot)
            VALUES(?,?,?,'Ingredient','g',0.125,0,false,0.125,?,true,0.04,0.01),
                  (?,?,?,'Another','g',0.100,0,false,0.100,?,true,0,0)
            """,restaurant.getId(),header,ingredient,Timestamp.from(NOW),restaurant.getId(),header,ingredient2,Timestamp.from(NOW));
    }

    private Restaurant restaurant() {
        var r = new Restaurant();
        r.setCode("MR_" + UUID.randomUUID().toString().replace("-", ""));
        r.setName("Margin test"); r.setTimezone("UTC"); r.setCurrencyCode("VND");
        return restaurants.saveAndFlush(r);
    }
    private ReportQueryContext context(Restaurant restaurant) {
        return new ReportQueryContext(restaurant.getId(), LocalDate.parse("2026-10-15"), LocalDate.parse("2026-10-15"),
            ZoneId.of("UTC"), Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-16T00:00:00Z"), "VND", null, null, NOW);
    }
    private UUID order(Restaurant restaurant, String subtotal, String discount, String tax, String service) {
        UUID id = UUID.randomUUID();
        var total = new BigDecimal(subtotal).subtract(new BigDecimal(discount)).add(new BigDecimal(tax)).add(new BigDecimal(service));
        jdbc.update("""
          INSERT INTO orders(id,restaurant_id,order_code,service_type,source_channel,status,payment_status,currency_code,
          subtotal_amount,discount_amount,tax_amount,service_charge_amount,total_amount,paid_amount,completed_at,created_at,updated_at,idempotency_key,request_hash)
          VALUES(?, ?, ?, 'TAKEAWAY','CASHIER','COMPLETED','PAID','VND', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,id,restaurant.getId(),id.toString(),new BigDecimal(subtotal),new BigDecimal(discount),new BigDecimal(tax),
          new BigDecimal(service),total,total,Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),id.toString(),"a".repeat(64));
        return id;
    }
    private void line(Restaurant restaurant, UUID order, int number, String cost, String unitCost, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
          INSERT INTO order_items(id,restaurant_id,order_id,item_name,unit,quantity,unit_price,line_total,status,
             sent_to_kitchen_at,cancel_reason,created_at,updated_at,line_number)
          VALUES(?,?,?,'Example','portion',1,100,100,?,?,?,?,?,?)
          """,id,restaurant.getId(),order,status,Timestamp.from(NOW),"CANCELLED".equals(status) ? "cancelled" : null,Timestamp.from(NOW),Timestamp.from(NOW),number);
        if (cost.equals("NONE")) return;
        if (cost.equals("LEGACY")) {
            jdbc.update("""
              INSERT INTO order_item_recipe_snapshots(restaurant_id,order_item_id,snapshot_status,order_quantity_snapshot,order_unit_snapshot,created_at)
              VALUES(?,?,'LEGACY_UNAVAILABLE',1,'portion',?)
              """,restaurant.getId(),id,Timestamp.from(NOW));
            return;
        }
        boolean disabled = cost.equals("UNAVAILABLE");
        String recipeState = disabled ? "FEATURE_DISABLED" : "RECIPE_DISABLED";
        String reason = disabled ? "RECIPE_FEATURE_DISABLED" : cost.equals("INCOMPLETE") ? "MANUAL_COST_NOT_CONFIGURED" : null;
        BigDecimal value = cost.equals("COMPLETE") ? new BigDecimal(unitCost) : null;
        jdbc.update("""
          INSERT INTO order_item_recipe_snapshots(restaurant_id,order_item_id,snapshot_status,order_quantity_snapshot,order_unit_snapshot,
          captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot,cost_snapshot_version,cost_status,cost_unavailable_reason,
          unit_cost_snapshot,total_cost_snapshot) VALUES(?,?,?,1,'portion',?,?,?, ?,1,?,?,?,?)
          """,restaurant.getId(),id,recipeState,Timestamp.from(NOW),Timestamp.from(NOW),disabled,disabled ? "RECIPE" : "MANUAL",cost,reason,value,value);
    }
}
