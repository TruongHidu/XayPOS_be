package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false",
    "logging.level.root=WARN",
    "app.cors.allowed-origins=http://localhost:3000"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(ReferenceMarginIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferenceMarginIntegrationTest {
    private static final String SCHEMA = "it_margin_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Set<String> BOTH = Set.of("REPORT_DAILY_VIEW", "REPORT_DETAIL_VIEW");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock reportClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base = requireTestEnvironment("DB_URL");
        String username = requireTestEnvironment("DB_USERNAME");
        String password = requireTestEnvironment("DB_PASSWORD");
        String url = base.contains("currentSchema=")
            ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
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

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_margin_[0-9a-f]{32}")) throw new IllegalStateException("Not a report test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    private static final Set<String> COST_PERMS = Set.of("ORDER_VIEW", "REPORT_DETAIL_VIEW");
    private static final Set<String> COST_FEATURES = Set.of("ORDER_MANAGEMENT", "DETAIL_REPORT");
    private static final String BASE = "/api/v1/reports/reference-margin/";

    @Test
    void summarySeriesAndPageReconcileAndRemainReadOnly() throws Exception {
        Restaurant restaurant = fixture("VND", COST_FEATURES);
        UUID first = order(restaurant, "COVERED", "COMPLETED", "PAID", NOW, NOW, null, "100.00", "VND", "CASHIER");
        UUID line = item(restaurant, first, null, "Pho", "portion", "1.000", "100.00", "SERVED", 1);
        snapshot(restaurant, line, true, "40.00");
        UUID second = order(restaurant, "MISSING", "COMPLETED", "PAID", NOW, NOW, null, "50.00", "VND", "WAITER");
        UUID missing = item(restaurant, second, null, "Tea", "portion", "1.000", "50.00", "SERVED", 1);
        snapshot(restaurant, missing, false, null);
        Map<String, Long> before = counts(restaurant);
        long snapshots = count("order_item_recipe_snapshots", restaurant);

        JsonNode summary = getJson(range(BASE + "summary"), restaurant, COST_PERMS, 200);
        assertThat(summary.at("/meta/dateBasis").asText()).isEqualTo("COMPLETED");
        assertThat(summary.at("/data/costCoverageStatus").asText()).isEqualTo("PARTIAL");
        assertThat(summary.at("/data/completedOrderCount").asLong()).isEqualTo(2);
        assertThat(summary.at("/data/fullyCostedOrderCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/data/excludedOrderCount").asLong()).isEqualTo(1);
        assertThat(summary.at("/data/orderCoveragePercent").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(summary.at("/data/netSalesAmount").decimalValue()).isEqualByComparingTo("150.00");
        assertThat(summary.at("/data/coveredReferenceCostAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(summary.at("/data/coveredReferenceMarginAmount").decimalValue()).isEqualByComparingTo("60.00");
        assertThat(summary.at("/data/costIssues/0/code").asText()).isEqualTo("INCOMPLETE");

        JsonNode series = getJson(range(BASE + "series"), restaurant, COST_PERMS, 200);
        assertThat(series.at("/data/0/bucketStartDate").asText()).isEqualTo("2026-10-15");
        assertThat(series.at("/data/0/completedOrderCount").asLong()).isEqualTo(2);
        assertThat(series.at("/data/0/coveredReferenceCostAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(series.at("/data/0/metrics").isMissingNode()).isTrue();

        JsonNode complete = getJson(range(BASE + "orders") + "&costCoverage=COMPLETE&size=1", restaurant, COST_PERMS, 200);
        assertThat(complete.at("/data/totalElements").asLong()).isEqualTo(1);
        assertThat(complete.at("/data/content/0/orderId").asText()).isEqualTo(first.toString());
        assertThat(complete.at("/data/content/0/referenceCostAmount").decimalValue()).isEqualByComparingTo("40.00");
        JsonNode excluded = getJson(range(BASE + "orders") + "&costCoverage=EXCLUDED", restaurant, COST_PERMS, 200);
        assertThat(excluded.at("/data/totalElements").asLong()).isEqualTo(1);
        assertThat(excluded.at("/data/content/0/orderId").asText()).isEqualTo(second.toString());
        assertThat(excluded.at("/data/content/0/referenceCostAmount").isNull()).isTrue();
        assertThat(counts(restaurant)).isEqualTo(before);
        assertThat(count("order_item_recipe_snapshots", restaurant)).isEqualTo(snapshots);
    }

    @Test
    void bothPermissionsAndFeaturesAreRequiredForEachEndpoint() throws Exception {
        Restaurant restaurant = fixture("VND", COST_FEATURES);
        Restaurant noOrderFeature = fixture("VND", Set.of("DETAIL_REPORT"));
        Restaurant noDetailFeature = fixture("VND", Set.of("ORDER_MANAGEMENT"));
        for (String endpoint : List.of("summary", "series", "orders")) {
            String path = range(BASE + endpoint);
            getJson(path, restaurant, Set.of("REPORT_DETAIL_VIEW"), 403);
            getJson(path, restaurant, Set.of("ORDER_VIEW"), 403);
            assertThat(getJson(path, noOrderFeature, COST_PERMS, 403).path("code").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
            assertThat(getJson(path, noDetailFeature, COST_PERMS, 403).path("code").asText()).isEqualTo("FEATURE_NOT_ENTITLED");
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Test
    void equalAmountsAreNotDeduplicatedAndEmptySeriesBucketsStayEmpty() throws Exception {
        Restaurant restaurant = fixture("VND", COST_FEATURES);
        for (int i = 0; i < 2; i++) {
            UUID order = order(restaurant, "EQUAL-" + i, "COMPLETED", "PAID", NOW, NOW, null,
                "100.00", "VND", "CASHIER");
            UUID line = item(restaurant, order, null, "Dish", "portion", "1.000", "100.00", "SERVED", 1);
            snapshot(restaurant, line, true, "0.00");
        }
        String query = "?fromDate=2026-10-14&toDate=2026-10-16";
        JsonNode summary = getJson(BASE + "summary" + query, restaurant, COST_PERMS, 200);
        assertThat(summary.at("/data/completedOrderCount").asLong()).isEqualTo(2);
        assertThat(summary.at("/data/fullyCostedOrderCount").asLong()).isEqualTo(2);
        assertThat(summary.at("/data/netSalesAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(summary.at("/data/coveredReferenceCostAmount").decimalValue()).isEqualByComparingTo("0.00");

        JsonNode series = getJson(BASE + "series" + query, restaurant, COST_PERMS, 200);
        assertThat(series.path("data")).hasSize(3);
        assertThat(series.at("/data/0/costCoverageStatus").asText()).isEqualTo("NO_DATA");
        assertThat(series.at("/data/0/orderCoveragePercent").isNull()).isTrue();
        assertThat(series.at("/data/0/coveredReferenceMarginPercent").isNull()).isTrue();
        assertThat(series.at("/data/1/completedOrderCount").asLong()).isEqualTo(2);
        assertThat(series.at("/data/1/netSalesAmount").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(series.at("/data/2/costCoverageStatus").asText()).isEqualTo("NO_DATA");
    }

    @Test
    void noDataAndUnavailableAreDistinctAndInconsistentSnapshotFails() throws Exception {
        Restaurant empty = fixture("VND", COST_FEATURES);
        JsonNode noData = getJson(range(BASE + "summary"), empty, COST_PERMS, 200);
        assertThat(noData.at("/data/costCoverageStatus").asText()).isEqualTo("NO_DATA");
        assertThat(noData.at("/data/orderCoveragePercent").isNull()).isTrue();

        Restaurant unknown = fixture("VND", COST_FEATURES);
        UUID order = order(unknown, "UNKNOWN", "COMPLETED", "PAID", NOW, NOW, null,
            "100.00", "VND", "CASHIER");
        UUID line = item(unknown, order, null, "Dish", "portion", "1.000", "100.00", "SERVED", 1);
        snapshot(unknown, line, false, null);
        JsonNode unavailable = getJson(range(BASE + "summary"), unknown, COST_PERMS, 200);
        assertThat(unavailable.at("/data/costCoverageStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(unavailable.at("/data/orderCoveragePercent").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(unavailable.at("/data/coveredReferenceMarginPercent").isNull()).isTrue();

        Restaurant invalid = fixture("VND", COST_FEATURES);
        UUID invalidOrder = order(invalid, "INVALID", "COMPLETED", "PAID", NOW, NOW, null,
            "100.00", "VND", "CASHIER");
        item(invalid, invalidOrder, null, "Dish", "portion", "1.000", "100.00", "SERVED", 1);
        for (String endpoint : List.of("summary", "series", "orders")) {
            JsonNode error = getJson(range(BASE + endpoint), invalid, COST_PERMS, 409);
            assertThat(error.path("code").asText()).isEqualTo("REPORT_COST_DATA_CONFLICT");
        }
    }

    @Test
    void sortedPagesRemainStableAndPutUnavailableCostLast() throws Exception {
        Restaurant restaurant = fixture("VND", COST_FEATURES);
        for (int i = 0; i < 3; i++) {
            UUID order = order(restaurant, "PAGE-" + i, "COMPLETED", "PAID", NOW, NOW, null,
                "100.00", "VND", "CASHIER");
            UUID line = item(restaurant, order, null, "Dish", "portion", "1.000", "100.00", "SERVED", 1);
            snapshot(restaurant, line, i < 2, i < 2 ? "30.00" : null);
        }
        String query = range(BASE + "orders") + "&sortBy=referenceCostAmount&direction=desc&size=1";
        JsonNode first = getJson(query + "&page=0", restaurant, COST_PERMS, 200);
        JsonNode second = getJson(query + "&page=1", restaurant, COST_PERMS, 200);
        JsonNode third = getJson(query + "&page=2", restaurant, COST_PERMS, 200);
        assertThat(first.at("/data/totalElements").asLong()).isEqualTo(3);
        assertThat(first.at("/data/content/0/referenceCostAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(second.at("/data/content/0/referenceCostAmount").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(first.at("/data/content/0/orderId").asText()).isNotEqualTo(second.at("/data/content/0/orderId").asText());
        assertThat(third.at("/data/content/0/referenceCostAmount").isNull()).isTrue();
        assertThat(getJson(query + "&page=0", restaurant, COST_PERMS, 200).at("/data/content/0/orderId").asText())
            .isEqualTo(first.at("/data/content/0/orderId").asText());
        assertThat(getJson(query + "&costCoverage=EXCLUDED", restaurant, COST_PERMS, 200)
            .at("/data/totalElements").asLong()).isEqualTo(1);
    }

    private void snapshot(Restaurant restaurant, UUID itemId, boolean configured, String cost) {
        jdbc.update("""
            INSERT INTO order_item_recipe_snapshots
              (restaurant_id,order_item_id,snapshot_status,order_quantity_snapshot,order_unit_snapshot,
               captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot,cost_snapshot_version,
               cost_status,cost_unavailable_reason,unit_cost_snapshot,total_cost_snapshot)
            VALUES (?,?, 'RECIPE_DISABLED',1,'portion',?,?,false,'MANUAL',1,?,?,?,?)
            """, restaurant.getId(), itemId, timestamp(NOW), timestamp(NOW),
            configured ? "COMPLETE" : "INCOMPLETE", configured ? null : "MANUAL_COST_NOT_CONFIGURED",
            configured ? new BigDecimal(cost) : null, configured ? new BigDecimal(cost) : null);
    }

    private Restaurant fixture(String currency, Set<String> features) {
        var restaurant = new Restaurant();
        restaurant.setCode("REPORT_" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Report fixture");
        restaurant.setTimezone("UTC");
        restaurant.setCurrencyCode(currency);
        restaurant = restaurants.saveAndFlush(restaurant);

        var subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("BASIC").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(NOW.minusSeconds(86400));
        subscription.setEndAt(NOW.plusSeconds(86400));
        subscription.setActivatedAt(NOW.minusSeconds(86400));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(
            1,
            "TEST",
            features.stream().sorted().map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(),
            NOW
        ).toMap());
        subscriptions.saveAndFlush(subscription);
        return restaurant;
    }

    private UUID order(
        Restaurant restaurant,
        String code,
        String status,
        String paymentStatus,
        Instant createdAt,
        Instant completedAt,
        Instant cancelledAt,
        String total,
        String currency,
        String sourceChannel
    ) {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal(total);
        jdbc.update("""
            INSERT INTO orders (
                id, restaurant_id, order_code, table_session_id, service_type, source_channel,
                status, payment_status, guest_count, currency_code, subtotal_amount,
                discount_amount, tax_amount, service_charge_amount, total_amount, paid_amount,
                cancel_reason, completed_at, cancelled_at, created_at, updated_at,
                mutation_sequence, idempotency_key, request_hash
            ) VALUES (?, ?, ?, NULL, 'TAKEAWAY', ?, ?, ?, 1, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """,
            id,
            restaurant.getId(),
            code + "-" + id.toString().substring(0, 8),
            sourceChannel,
            status,
            paymentStatus,
            currency,
            amount,
            amount,
            "PAID".equals(paymentStatus) ? amount : BigDecimal.ZERO,
            "CANCELLED".equals(status) ? "Fixture cancelled" : null,
            timestamp(completedAt),
            timestamp(cancelledAt),
            timestamp(createdAt),
            timestamp(createdAt),
            UUID.randomUUID().toString(),
            "a".repeat(64)
        );
        return id;
    }

    private UUID item(
        Restaurant restaurant,
        UUID orderId,
        UUID itemId,
        String name,
        String unit,
        String quantity,
        String lineTotal,
        String status,
        int lineNumber
    ) {
        UUID line = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO order_items (
                id, restaurant_id, order_id, item_id, item_name, unit, quantity,
                unit_price, discount_amount, line_total, status, cancel_reason,
                created_at, updated_at, line_number
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
            """,
            line,
            restaurant.getId(),
            orderId,
            itemId,
            name,
            unit,
            new BigDecimal(quantity),
            new BigDecimal(lineTotal).divide(new BigDecimal(quantity)),
            new BigDecimal(lineTotal),
            status,
            "CANCELLED".equals(status) ? "Fixture cancelled" : null,
            timestamp(NOW),
            timestamp(NOW),
            lineNumber
        );
        return line;
    }

    private void payment(
        Restaurant restaurant,
        UUID orderId,
        String amount,
        String method,
        String status,
        Instant paidAt,
        String currency
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO payments (
                id, restaurant_id, order_id, payment_code, amount, currency_code,
                payment_method, status, confirmation_source, requested_at, paid_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROVIDER_WEBHOOK', ?, ?, ?, ?)
            """,
            id,
            restaurant.getId(),
            orderId,
            "PAY-" + id.toString().substring(0, 12),
            new BigDecimal(amount),
            currency,
            method,
            status,
            timestamp(paidAt == null ? NOW : paidAt),
            timestamp(paidAt),
            timestamp(NOW),
            timestamp(NOW)
        );
    }

    private JsonNode getJson(String path, Restaurant restaurant, Set<String> permissions, int expected) throws Exception {
        var user = new CurrentUser(UUID.randomUUID(), restaurant.getId(), "report@example.test", "OWNER", permissions);
        var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        var response = mvc.perform(get(path).with(authentication(auth))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return json.readTree(response.getContentAsString());
    }

    private Map<String, Long> counts(Restaurant restaurant) {
        return Map.of(
            "orders", count("orders", restaurant),
            "payments", count("payments", restaurant),
            "subscriptions", count("restaurant_subscriptions", restaurant),
            "audit", count("audit_logs", restaurant)
        );
    }

    private long count(String table, Restaurant restaurant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE restaurant_id=?", Long.class, restaurant.getId());
    }

    private static String range(String path) {
        return path + "?fromDate=2026-10-15&toDate=2026-10-15";
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
