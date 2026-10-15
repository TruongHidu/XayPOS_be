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
@Import(ReportIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReportIntegrationTest {
    private static final String SCHEMA = "it_report_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-15T12:00:00Z");
    private static final Set<String> BOTH = Set.of("REPORT_DAILY_VIEW", "REPORT_DETAIL_VIEW");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock reportClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=")
            ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_report_[0-9a-f]{32}")) throw new IllegalStateException("Not a report test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void temporalFactsUseTheirOwnBusinessTimestampsAndSuccessLedger() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DAILY_REVENUE", "DETAIL_REPORT"));
        UUID completed = order(
            restaurant,
            "OLD-CREATED",
            "COMPLETED",
            "PAID",
            Instant.parse("2026-10-13T08:00:00Z"),
            Instant.parse("2026-10-15T08:00:00Z"),
            null,
            "100000.00",
            "VND",
            "WAITER"
        );
        payment(restaurant, completed, "50000.00", "CASH", "SUCCESS", Instant.parse("2026-10-14T08:00:00Z"), "VND");
        payment(restaurant, completed, "50000.00", "TRANSFER", "SUCCESS", Instant.parse("2026-10-14T09:00:00Z"), "VND");
        payment(restaurant, completed, "999.00", "CASH", "FAILED", null, "VND");
        UUID paidNotCompleted = order(
            restaurant,
            "PAID-READY",
            "READY",
            "PAID",
            Instant.parse("2026-10-14T07:00:00Z"),
            null,
            null,
            "20000.00",
            "VND",
            "CASHIER"
        );
        payment(restaurant, paidNotCompleted, "20000.00", "CASH", "SUCCESS", Instant.parse("2026-10-14T10:00:00Z"), "VND");

        JsonNode paidDay = getJson("/api/v1/reports/daily?date=2026-10-14", restaurant, BOTH, 200);
        assertThat(paidDay.at("/data/collections/collectedAmount").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(paidDay.at("/data/collections/successfulPaymentCount").asLong()).isEqualTo(3);
        assertThat(paidDay.at("/data/collections/paidOrderCount").asLong()).isEqualTo(2);
        assertThat(paidDay.at("/data/orders/completedOrderCount").asLong()).isZero();
        assertThat(paidDay.at("/data/orders/createdOrderCount").asLong()).isEqualTo(1);

        JsonNode completedDay = getJson("/api/v1/reports/daily?date=2026-10-15", restaurant, BOTH, 200);
        assertThat(completedDay.at("/data/collections/collectedAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(completedDay.at("/data/orders/completedOrderSalesAmount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(completedDay.at("/data/orders/averageCompletedOrderValue").decimalValue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void seriesAndPaymentMethodsReconcileWithoutItemOrEqualAmountFanOut() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DAILY_REVENUE", "DETAIL_REPORT"));
        UUID order = order(restaurant, "RECONCILE", "COMPLETED", "PAID", NOW.minusSeconds(3600), NOW, null,
            "200000.00", "VND", "CASHIER");
        item(restaurant, order, null, "Pho", "portion", "1.000", "100000.00", "SERVED", 1);
        item(restaurant, order, null, "Tea", "glass", "1.000", "100000.00", "SERVED", 2);
        payment(restaurant, order, "100000.00", "CASH", "SUCCESS", NOW.minusSeconds(1200), "VND");
        payment(restaurant, order, "100000.00", "TRANSFER", "SUCCESS", NOW.minusSeconds(600), "VND");

        JsonNode summary = getJson(range("/api/v1/reports/summary"), restaurant, BOTH, 200);
        JsonNode series = getJson(range("/api/v1/reports/revenue-series"), restaurant, BOTH, 200);
        JsonNode methods = getJson(range("/api/v1/reports/payment-methods"), restaurant, BOTH, 200);
        assertThat(summary.at("/data/collections/collectedAmount").decimalValue()).isEqualByComparingTo("200000.00");
        assertThat(summary.at("/data/orders/completedOrderSalesAmount").decimalValue()).isEqualByComparingTo("200000.00");
        assertThat(series.at("/data/0/collectedAmount").decimalValue()).isEqualByComparingTo("200000.00");
        assertThat(methods.path("data").size()).isEqualTo(2);
        assertThat(methods.at("/data/0/collectedAmount").decimalValue()
            .add(methods.at("/data/1/collectedAmount").decimalValue())).isEqualByComparingTo("200000.00");
        assertThat(methods.at("/data/0/percentage").decimalValue()
            .add(methods.at("/data/1/percentage").decimalValue())).isEqualByComparingTo("100.00");
    }

    @Test
    void topItemsGroupSnapshotsExcludeCancelledLinesAndCountOrdersDistinctly() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DETAIL_REPORT"));
        UUID first = order(restaurant, "ITEM-1", "COMPLETED", "PAID", NOW, NOW, null,
            "90000.00", "VND", "WAITER");
        item(restaurant, first, null, "Pho snapshot", "portion", "1.000", "30000.00", "SERVED", 1);
        item(restaurant, first, null, "Pho snapshot", "portion", "2.000", "60000.00", "SERVED", 2);
        item(restaurant, first, null, "Cancelled", "portion", "1.000", "99999.00", "CANCELLED", 3);
        UUID second = order(restaurant, "ITEM-2", "COMPLETED", "PAID", NOW, NOW, null,
            "30000.00", "VND", "WAITER");
        item(restaurant, second, null, "Pho snapshot", "portion", "1.000", "30000.00", "SERVED", 1);

        JsonNode result = getJson(range("/api/v1/reports/top-items") + "&size=1", restaurant, BOTH, 200);
        assertThat(result.at("/data/totalElements").asLong()).isEqualTo(1);
        assertThat(result.at("/data/content/0/itemId").isNull()).isTrue();
        assertThat(result.at("/data/content/0/quantity").decimalValue()).isEqualByComparingTo("4.000");
        assertThat(result.at("/data/content/0/itemSalesAmount").decimalValue()).isEqualByComparingTo("120000.00");
        assertThat(result.at("/data/content/0/completedOrderCount").asLong()).isEqualTo(2);
    }

    @Test
    void paidOrderListingReturnsOneOrderWithWindowLedgerSumAndLatestPaymentTime() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DETAIL_REPORT"));
        UUID order = order(restaurant, "PAID-LIST", "READY", "PAID", NOW.minusSeconds(7200), null, null,
            "100000.00", "VND", "CASHIER");
        payment(restaurant, order, "40000.00", "CASH", "SUCCESS", NOW.minusSeconds(1200), "VND");
        payment(restaurant, order, "60000.00", "TRANSFER", "SUCCESS", NOW.minusSeconds(600), "VND");

        JsonNode result = getJson(range("/api/v1/reports/orders") + "&dateBasis=PAID", restaurant, BOTH, 200);
        assertThat(result.at("/data/totalElements").asLong()).isEqualTo(1);
        assertThat(result.at("/data/content/0/collectedAmountInRange").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(result.at("/data/content/0/reportEventAt").asText()).isEqualTo("2026-10-15T11:50:00Z");
        assertThat(result.at("/data/content/0/customerName").isMissingNode()).isTrue();
    }

    @Test
    void emptyFiltersCurrencyTenantPermissionFeatureAndInvalidTimezoneStayIsolated() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DAILY_REVENUE", "DETAIL_REPORT"));
        UUID vnd = order(restaurant, "VND", "COMPLETED", "PAID", NOW, NOW, null,
            "100.00", "VND", "CASHIER");
        payment(restaurant, vnd, "100.00", "CASH", "SUCCESS", NOW, "VND");
        UUID usd = order(restaurant, "USD", "COMPLETED", "PAID", NOW, NOW, null,
            "2.00", "USD", "WAITER");
        payment(restaurant, usd, "2.00", "TRANSFER", "SUCCESS", NOW, "USD");
        Restaurant other = fixture("VND", Set.of("DAILY_REVENUE", "DETAIL_REPORT"));
        UUID otherOrder = order(other, "OTHER", "COMPLETED", "PAID", NOW, NOW, null,
            "999.00", "VND", "CASHIER");
        payment(other, otherOrder, "999.00", "CASH", "SUCCESS", NOW, "VND");

        JsonNode defaults = getJson(range("/api/v1/reports/summary"), restaurant, BOTH, 200);
        assertThat(defaults.at("/data/collections/collectedAmount").decimalValue()).isEqualByComparingTo("100.00");
        JsonNode usdResult = getJson(range("/api/v1/reports/summary") + "&currencyCode=usd&sourceChannel=WAITER", restaurant, BOTH, 200);
        assertThat(usdResult.at("/data/collections/collectedAmount").decimalValue()).isEqualByComparingTo("2.00");
        JsonNode empty = getJson(range("/api/v1/reports/payment-methods") + "&sourceChannel=QR_STATIC", restaurant, BOTH, 200);
        assertThat(empty.path("data")).hasSize(2);
        assertThat(empty.at("/data/0/percentage").decimalValue()).isEqualByComparingTo("0.00");

        getJson(range("/api/v1/reports/summary"), restaurant, Set.of("REPORT_DAILY_VIEW"), 403);
        getJson("/api/v1/reports/daily?date=2026-10-15", restaurant, Set.of("REPORT_DETAIL_VIEW"), 403);
        assertThat(mvc.perform(get(range("/api/v1/reports/summary"))).andReturn().getResponse().getStatus()).isEqualTo(401);

        Restaurant dailyOnly = fixture("VND", Set.of("DAILY_REVENUE"));
        JsonNode denied = getJson(range("/api/v1/reports/summary"), dailyOnly, BOTH, 403);
        assertThat(denied.path("code").asText()).isEqualTo("FEATURE_NOT_ENTITLED");

        jdbc.update("UPDATE restaurants SET timezone='Invalid/Zone' WHERE id=?", restaurant.getId());
        JsonNode badZone = getJson(range("/api/v1/reports/summary"), restaurant, BOTH, 409);
        assertThat(badZone.path("code").asText()).isEqualTo("INVALID_REPORT_TIMEZONE");
    }

    @Test
    void reportReadsDoNotMutateBusinessOrAuditTables() throws Exception {
        Restaurant restaurant = fixture("VND", Set.of("DAILY_REVENUE", "DETAIL_REPORT"));
        UUID order = order(restaurant, "READ-ONLY", "COMPLETED", "PAID", NOW, NOW, null,
            "100.00", "VND", "CASHIER");
        payment(restaurant, order, "100.00", "CASH", "SUCCESS", NOW, "VND");
        Map<String, Long> before = counts(restaurant);
        getJson(range("/api/v1/reports/summary"), restaurant, BOTH, 200);
        getJson(range("/api/v1/reports/orders") + "&dateBasis=PAID", restaurant, BOTH, 200);
        assertThat(counts(restaurant)).isEqualTo(before);
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

    private void item(
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
        jdbc.update("""
            INSERT INTO order_items (
                id, restaurant_id, order_id, item_id, item_name, unit, quantity,
                unit_price, discount_amount, line_total, status, cancel_reason,
                created_at, updated_at, line_number
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID(),
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
