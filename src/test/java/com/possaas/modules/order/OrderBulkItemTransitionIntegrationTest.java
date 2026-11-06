package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemGroup;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemGroupRepository;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.OrderItemStatus;
import com.possaas.modules.order.entity.OrderItemStatusHistory;
import com.possaas.modules.order.repository.OrderItemStatusHistoryRepository;
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.order.service.OrderHistory;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.table.entity.RestaurantTable;
import com.possaas.modules.table.entity.TableSession;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import com.possaas.modules.table.repository.TableSessionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(OrderBulkItemTransitionIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderBulkItemTransitionIntegrationTest {
    private static final String SCHEMA = "it_order_bulk_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final String ORDERS = "/api/v1/orders";
    private static final List<String> FEATURES = List.of("ORDER_MANAGEMENT", "POS_QUICK_ORDER",
        "TABLE_MANAGEMENT", "PAYMENT_MANAGEMENT", "KITCHEN_DISPLAY");
    private static final Set<String> ALL = Set.of("ORDER_VIEW", "ORDER_CREATE", "ORDER_UPDATE",
        "ORDER_CANCEL", "ORDER_MARK_SERVED", "ORDER_COMPLETE", "KITCHEN_START_COOKING",
        "KITCHEN_MARK_READY", "PAYMENT_CREATE", "PAYMENT_CONFIRM");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock orderBulkClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
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
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository menuItems;
    @Autowired ItemGroupRepository groups;
    @Autowired RestaurantTableRepository tables;
    @Autowired TableSessionRepository sessions;
    @Autowired OrderItemStatusHistoryRepository histories;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean OrderHistory historyWriter;
    private Fixture f;

    private record Fixture(Restaurant restaurant, User user, RestaurantSubscription subscription,
                           Item item, TableSession session) {}

    private record Result(int status, JsonNode body, String cacheControl) {
        UUID id() { return UUID.fromString(body.path("id").asText()); }
        long version() { return body.path("version").asLong(); }
        UUID lineId(int index) { return UUID.fromString(body.path("items").get(index).path("id").asText()); }
        void expect(int expected) { assertThat(status).as(body.toString()).isEqualTo(expected); }
        void error(int expected, String code) {
            expect(expected);
            assertThat(body.path("code").asText()).isEqualTo(code);
        }
    }

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        f = fixture();
    }

    @AfterEach
    void resetFailureInjection() { reset(audit, orders, historyWriter); }

    @AfterAll
    void dropOwnSchema() {
        if (!SCHEMA.matches("it_order_bulk_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void readyUpdatesOnlyCookingLinesAndRecordsOneMutationWithPerLineHistory() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING, OrderItemStatus.COOKING,
            OrderItemStatus.READY, OrderItemStatus.SERVED, OrderItemStatus.CANCELLED);
        var beforeCounts = historyCounts(order);
        long beforeSequence = mutation(order);
        var updated = bulk(order, "mark-ready");
        updated.expect(200);
        assertThat(updated.cacheControl).contains("no-store");
        assertThat(updated.version()).isEqualTo(order.version() + 1);
        assertThat(mutation(order)).isEqualTo(beforeSequence + 1);
        assertStatuses(updated, "READY", "READY", "READY", "SERVED", "CANCELLED");
        assertThat(updated.body.path("status").asText()).isEqualTo("READY");
        assertThat(historyCounts(updated)).containsExactly(beforeCounts.get(0) + 1,
            beforeCounts.get(1) + 1, beforeCounts.get(2), beforeCounts.get(3), beforeCounts.get(4));
        for (int index : List.of(0, 1)) assertLastHistory(updated, index,
            OrderItemStatus.COOKING, OrderItemStatus.READY, beforeSequence + 1);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isEqualTo(1);
        assertUnchangedSnapshotsAndMoney(order, updated);
        assertThat(detail(order).body).isEqualTo(updated.body);
        assertAuditSnapshots(order, updated, "ORDER_ITEMS_MARKED_READY");
    }

    @Test
    void servedUpdatesOnlyReadyLinesWithoutCompletingOrderOrClosingSession() throws Exception {
        var order = prepare(true, OrderItemStatus.READY, OrderItemStatus.READY,
            OrderItemStatus.SERVED, OrderItemStatus.CANCELLED);
        var beforeCounts = historyCounts(order);
        var sequence = mutation(order);
        var updated = bulk(order, "mark-served");
        updated.expect(200);
        assertStatuses(updated, "SERVED", "SERVED", "SERVED", "CANCELLED");
        assertThat(updated.body.path("status").asText()).isEqualTo("SERVED");
        assertThat(updated.body.path("completedAt").isNull()).isTrue();
        assertThat(updated.version()).isEqualTo(order.version() + 1);
        assertThat(mutation(order)).isEqualTo(sequence + 1);
        assertThat(historyCounts(updated)).containsExactly(beforeCounts.get(0) + 1,
            beforeCounts.get(1) + 1, beforeCounts.get(2), beforeCounts.get(3));
        for (int index : List.of(0, 1)) assertLastHistory(updated, index,
            OrderItemStatus.READY, OrderItemStatus.SERVED, sequence + 1);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
        assertThat(events("ORDER_COMPLETED")).isZero();
        assertThat(events("TABLE_SESSION_CLOSED")).isZero();
        assertUnchangedSnapshotsAndMoney(order, updated);
        assertAuditSnapshots(order, updated, "ORDER_ITEMS_MARKED_SERVED");
    }

    @Test
    void servedUpdatesMixedLiveStatusesWithActualHistorySourcesAndOneAggregateMutation() throws Exception {
        var order = prepare(true, OrderItemStatus.PENDING, OrderItemStatus.COOKING,
            OrderItemStatus.READY, OrderItemStatus.SERVED, OrderItemStatus.CANCELLED);
        var counts = historyCounts(order);
        long sequence = mutation(order);
        var updated = bulk(order, "mark-served");
        updated.expect(200);
        assertThat(updated.cacheControl).contains("no-store");
        assertStatuses(updated, "SERVED", "SERVED", "SERVED", "SERVED", "CANCELLED");
        assertThat(updated.body.path("status").asText()).isEqualTo("SERVED");
        assertThat(updated.body.path("completedAt").isNull()).isTrue();
        assertThat(updated.version()).isEqualTo(order.version() + 1);
        assertThat(mutation(updated)).isEqualTo(sequence + 1);
        assertThat(historyCounts(updated)).containsExactly(counts.get(0) + 1, counts.get(1) + 1,
            counts.get(2) + 1, counts.get(3), counts.get(4));
        assertLastHistory(updated, 0, OrderItemStatus.PENDING, OrderItemStatus.SERVED, sequence + 1);
        assertLastHistory(updated, 1, OrderItemStatus.COOKING, OrderItemStatus.SERVED, sequence + 1);
        assertLastHistory(updated, 2, OrderItemStatus.READY, OrderItemStatus.SERVED, sequence + 1);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus())
            .isEqualTo(TableSessionStatus.OPEN);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
        assertThat(events("ORDER_COMPLETED")).isZero();
        assertThat(events("TABLE_SESSION_CLOSED")).isZero();
        assertUnchangedSnapshotsAndMoney(order, updated);
        assertAuditSnapshots(order, updated, "ORDER_ITEMS_MARKED_SERVED");
        assertThat(detail(order).body).isEqualTo(updated.body);

        var retry = bulk(order, "mark-served");
        retry.expect(200);
        assertThat(retry.body).isEqualTo(updated.body);
        assertThat(historyCounts(retry)).isEqualTo(historyCounts(updated));
        assertThat(mutation(retry)).isEqualTo(sequence + 1);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"mark-ready", "mark-served"})
    void exactRetryWithOriginalVersionCreatesNoExtraHistoryAuditOrMutation(String action) throws Exception {
        var source = action.equals("mark-ready") ? OrderItemStatus.COOKING : OrderItemStatus.READY;
        var order = prepare(false, source, source);
        var changed = bulk(order, action);
        changed.expect(200);
        var counts = historyCounts(changed);
        long sequence = mutation(changed);
        var retry = bulk(order, action);
        retry.expect(200);
        assertThat(retry.body).isEqualTo(changed.body);
        assertThat(mutation(order)).isEqualTo(sequence);
        assertThat(historyCounts(retry)).isEqualTo(counts);
        assertThat(events(action.equals("mark-ready") ? "ORDER_ITEMS_MARKED_READY" : "ORDER_ITEMS_MARKED_SERVED"))
            .isEqualTo(1);
    }

    @Test
    void readyNoOpOnReadyAndServedNeverRewindsServedOrder() throws Exception {
        var mixed = prepare(false, OrderItemStatus.READY, OrderItemStatus.SERVED);
        var sequence = mutation(mixed);
        var noop = bulk(mixed, "mark-ready", 0);
        noop.expect(200);
        assertThat(noop.body).isEqualTo(mixed.body);
        assertThat(mutation(mixed)).isEqualTo(sequence);
        var served = prepare(false, OrderItemStatus.SERVED, OrderItemStatus.SERVED);
        var servedNoop = bulk(served, "mark-ready", 0);
        servedNoop.expect(200);
        assertThat(servedNoop.body).isEqualTo(served.body);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
    }

    @Test
    void readyRejectsPendingAtomicallyIncludingEarlierEligibleLines() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING, OrderItemStatus.PENDING, OrderItemStatus.READY);
        var counts = historyCounts(order);
        bulk(order, "mark-ready").error(409, "INVALID_ORDER_ITEM_TRANSITION");
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(historyCounts(order)).isEqualTo(counts);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COOKING"})
    void servedAllowsPendingAndCookingLinesAlongsideReady(String source) throws Exception {
        var order = prepare(false, OrderItemStatus.READY, OrderItemStatus.valueOf(source));
        var counts = historyCounts(order);
        long sequence = mutation(order);
        var updated = bulk(order, "mark-served");
        updated.expect(200);
        assertStatuses(updated, "SERVED", "SERVED");
        assertThat(historyCounts(updated)).containsExactly(counts.get(0) + 1, counts.get(1) + 1);
        assertLastHistory(updated, 0, OrderItemStatus.READY, OrderItemStatus.SERVED, sequence + 1);
        assertLastHistory(updated, 1, OrderItemStatus.valueOf(source), OrderItemStatus.SERVED, sequence + 1);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
        assertUnchangedSnapshotsAndMoney(order, updated);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COOKING"})
    void individualServingStillRejectsUnreadyLinesWithoutChangingHistory(String source) throws Exception {
        var order = prepare(false, OrderItemStatus.valueOf(source));
        var counts = historyCounts(order);
        var sequence = mutation(order);
        single(order, 0, "mark-served").error(409, "INVALID_ORDER_ITEM_TRANSITION");
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(historyCounts(order)).isEqualTo(counts);
        assertThat(mutation(order)).isEqualTo(sequence);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"OPEN", "CANCELLED", "COMPLETED"})
    void draftAndTerminalOrdersAreRejectedEvenWhenLinesWouldAllowNoOp(String state) throws Exception {
        Result order;
        if (state.equals("OPEN")) order = create(false, 1, "DRAFT");
        else if (state.equals("CANCELLED")) {
            var submitted = create(false, 1, "SUBMIT");
            order = call(post(path(submitted) + "/cancel"), Map.of("expectedVersion", submitted.version(), "reason", "cancel fixture"));
        } else {
            var served = prepare(false, OrderItemStatus.SERVED);
            pay(served).expect(201);
            var paid = detail(served);
            order = call(post(path(served) + "/complete"), Map.of("expectedVersion", paid.version()));
        }
        assertThat(order.body.path("status").asText()).isEqualTo(state);
        for (String action : List.of("mark-ready", "mark-served")) {
            bulk(order, action, 0).error(409, "INVALID_ORDER_ITEM_TRANSITION");
            assertThat(detail(order).body).isEqualTo(order.body);
        }
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mark-ready", "mark-served"})
    void cancelledOnlyOrderIsNotVacuouslySuccessful(String action) throws Exception {
        var order = create(false, 1, "SUBMIT");
        // Construct a legacy inconsistent submitted aggregate to exercise the empty-effective-lines boundary.
        jdbc.update("update order_items set status='CANCELLED',cancel_reason='legacy fixture' where order_id=?", order.id());
        var before = detail(order);
        bulk(order, action).error(409, "EMPTY_ORDER");
        assertThat(detail(order).body).isEqualTo(before.body);
    }

    @ParameterizedTest
    @CsvSource({"mark-ready,COOKING", "mark-served,READY", "mark-served,PENDING", "mark-served,COOKING"})
    void staleVersionCannotChangeEligibleItems(String action, String source) throws Exception {
        var order = prepare(false, OrderItemStatus.valueOf(source));
        var counts = historyCounts(order);
        bulk(order, action, order.version() - 1).error(409, "CONCURRENT_ORDER_UPDATE");
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(historyCounts(order)).isEqualTo(counts);
    }

    @Test
    void paidOrderStillAcceptsReadyAndServedWithoutChangingPaymentOrSnapshots() throws Exception {
        var order = prepare(true, OrderItemStatus.COOKING, OrderItemStatus.COOKING);
        pay(order).expect(201);
        var paid = detail(order);
        jdbc.update("update items set name='Changed menu',base_unit='plate',sale_price=999999 where id=?", f.item.getId());
        var ready = bulk(paid, "mark-ready");
        ready.expect(200);
        var served = bulk(ready, "mark-served");
        served.expect(200);
        assertThat(served.body.path("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(served.body.path("paidAmount").decimalValue()).isEqualByComparingTo(paid.body.path("paidAmount").decimalValue());
        assertUnchangedSnapshotsAndMoney(paid, ready);
        assertUnchangedSnapshotsAndMoney(paid, served);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "KITCHEN"})
    void migratedSystemRoleCanUseBothApisWithRealBearerAuthentication(String roleCode) throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING, OrderItemStatus.COOKING);
        var actor = assignRole(roleCode);
        assertThat(actor.permissions()).contains("KITCHEN_MARK_READY", "ORDER_MARK_SERVED");
        if (roleCode.equals("CASHIER")) assertThat(actor.permissions()).doesNotContain("KITCHEN_START_COOKING");
        else assertThat(actor.permissions()).doesNotContain("ORDER_COMPLETE", "PAYMENT_CONFIRM", "TABLE_CLOSE");
        var ready = bearer(post(path(order) + "/mark-ready"), version(order.version()), actor);
        ready.expect(200);
        var served = bearer(post(path(order) + "/mark-served"), version(ready.version()), actor);
        served.expect(200);
        assertThat(served.body.path("status").asText()).isEqualTo("SERVED");
    }

    @Test
    void tenantCustomRoleWithPermissionsIsAllowedWithoutCashierKitchenRoleCheck() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING);
        var role = new Role();
        role.setRestaurantId(f.restaurant.getId()); role.setCode("SERVICE_CUSTOM"); role.setName("Custom service role");
        role = roles.saveAndFlush(role);
        jdbc.update("insert into role_permissions(role_id,permission_id) select ?,id from permissions where code in ('KITCHEN_MARK_READY','ORDER_MARK_SERVED')", role.getId());
        jdbc.update("update users set role_id=? where id=?", role.getId(), f.user.getId());
        var actor = new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), role.getCode(),
            permissions.getEffectivePermissionCodes(f.user.getId()));
        var ready = bearer(post(path(order) + "/mark-ready"), version(order.version()), actor);
        ready.expect(200);
        bearer(post(path(order) + "/mark-served"), version(ready.version()), actor).expect(200);
    }

    @ParameterizedTest
    @CsvSource({"mark-ready,KITCHEN_MARK_READY,COOKING", "mark-served,ORDER_MARK_SERVED,READY"})
    void explicitUserDenyOverridesMigratedRolePermission(String action, String permission, String source) throws Exception {
        var order = prepare(false, OrderItemStatus.valueOf(source));
        assignRole("KITCHEN");
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'DENY',? from permissions where code=?",
            f.user.getId(), f.user.getId(), permission);
        var actor = new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), "KITCHEN",
            permissions.getEffectivePermissionCodes(f.user.getId()));
        assertThat(actor.hasPermission(permission)).isFalse();
        bearer(post(path(order) + "/" + action), version(order.version()), actor).error(403, "FORBIDDEN");
        assertThat(detail(order).body).isEqualTo(order.body);
    }

    @ParameterizedTest
    @CsvSource({"mark-ready,READY", "mark-served,SERVED"})
    void noOpStillRequiresPermissionAndAuthentication(String action, String source) throws Exception {
        var order = prepare(false, OrderItemStatus.valueOf(source));
        callAs(post(path(order) + "/" + action), version(0), actor(Set.of("ORDER_VIEW"))).error(403, "FORBIDDEN");
        var unauthenticated = mvc.perform(post(path(order) + "/" + action).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(version(0)))).andReturn().getResponse();
        assertThat(unauthenticated.getStatus()).isEqualTo(401);
    }

    @ParameterizedTest
    @CsvSource({"mark-ready,KITCHEN_DISPLAY,READY", "mark-served,ORDER_MANAGEMENT,SERVED"})
    void noOpStillRequiresTheMatchingFeature(String action, String feature, String source) throws Exception {
        var order = prepare(false, OrderItemStatus.valueOf(source));
        setFeatures(FEATURES.stream().filter(code -> !code.equals(feature)).toList());
        bulk(order, action, 0).error(403, "FEATURE_NOT_ENTITLED");
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isZero();
    }

    @Test
    void readyUsesKitchenFeatureWithoutRequiringOrderManagement() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING);
        setFeatures(List.of("KITCHEN_DISPLAY"));
        var ready = bulk(order, "mark-ready");
        ready.expect(200);
        bulk(ready, "mark-served").error(403, "FEATURE_NOT_ENTITLED");
    }

    @Test
    void servedUsesOrderFeatureWithoutRequiringKitchenDisplay() throws Exception {
        var order = prepare(false, OrderItemStatus.READY);
        setFeatures(List.of("ORDER_MANAGEMENT"));
        bulk(order, "mark-served").expect(200);
    }

    @Test
    void cashierCanServePendingDirectlyWithOnlyServingPermissionAndOrderFeature() throws Exception {
        var order = prepare(true, OrderItemStatus.PENDING, OrderItemStatus.PENDING);
        var migrated = assignRole("CASHIER");
        assertThat(migrated.permissions()).contains("ORDER_MARK_SERVED")
            .doesNotContain("KITCHEN_START_COOKING");
        var cashier = new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(),
            "CASHIER", Set.of("ORDER_MARK_SERVED"));
        setFeatures(List.of("ORDER_MANAGEMENT"));
        var counts = historyCounts(order);
        var sequence = mutation(order);
        var served = bearer(post(path(order) + "/mark-served"), version(order.version()), cashier);
        served.expect(200);
        assertStatuses(served, "SERVED", "SERVED");
        assertThat(served.body.path("status").asText()).isEqualTo("SERVED");
        assertThat(served.body.path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(historyCounts(served)).containsExactly(counts.get(0) + 1, counts.get(1) + 1);
        for (int index : List.of(0, 1)) assertLastHistory(served, index,
            OrderItemStatus.PENDING, OrderItemStatus.SERVED, sequence + 1);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus())
            .isEqualTo(TableSessionStatus.OPEN);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
        assertUnchangedSnapshotsAndMoney(order, served);
    }

    @Test
    void inactiveAccountsAndRestaurantsRemainBlockedAtSecurityBoundary() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING);
        jdbc.update("update users set is_active=false where id=?", f.user.getId());
        bearer(post(path(order) + "/mark-ready"), version(order.version()), actor(ALL)).error(401, "ACCOUNT_INACTIVE");
        jdbc.update("update users set is_active=true where id=?", f.user.getId());
        jdbc.update("update restaurants set status='SUSPENDED' where id=?", f.restaurant.getId());
        bearer(post(path(order) + "/mark-ready"), version(order.version()), actor(ALL)).error(403, "RESTAURANT_INACTIVE");
    }

    @Test
    void foreignTenantOrdersAreHiddenForBothActions() throws Exception {
        var own = f;
        f = fixture();
        var foreign = prepare(false, OrderItemStatus.READY);
        f = own;
        for (String action : List.of("mark-ready", "mark-served"))
            bulk(foreign, action).error(404, "ORDER_NOT_FOUND");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mark-ready", "mark-served"})
    void versionBodyValidationRejectsMissingNegativeAndUnsupportedFields(String action) throws Exception {
        var order = prepare(false, OrderItemStatus.READY);
        call(post(path(order) + "/" + action), Map.of()).error(400, "VALIDATION_ERROR");
        call(post(path(order) + "/" + action), version(-1)).error(400, "VALIDATION_ERROR");
        call(post(path(order) + "/" + action), Map.of("expectedOrderVersion", order.version(), "status", "SERVED"))
            .error(400, "INVALID_REQUEST_BODY");
        call(post(path(order) + "/" + action), null).error(400, "INVALID_REQUEST_BODY");
    }

    @ParameterizedTest
    @ValueSource(strings = {"mark-ready", "mark-served"})
    void auditFailureRollsBackItemsOrderHistoryAndAlreadyWrittenAudit(String action) throws Exception {
        var source = action.equals("mark-ready") ? OrderItemStatus.COOKING : OrderItemStatus.READY;
        var order = prepare(false, source, source);
        var counts = historyCounts(order);
        var sequence = mutation(order);
        String event = action.equals("mark-ready") ? "ORDER_ITEMS_MARKED_READY" : "ORDER_ITEMS_MARKED_SERVED";
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("audit failed after write"); })
            .when(audit).record(eq(f.restaurant.getId()), any(), eq(event), any(), any(), any(), any(), any());
        bulk(order, action).error(500, "INTERNAL_ERROR");
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(mutation(order)).isEqualTo(sequence);
        assertThat(historyCounts(order)).isEqualTo(counts);
        assertThat(events(event)).isZero();
    }

    @Test
    void servingMixedSourcesRollsBackAllGroupsWhenHistoryFailsAfterWriting() throws Exception {
        var order = prepare(false, OrderItemStatus.PENDING, OrderItemStatus.COOKING, OrderItemStatus.READY);
        var counts = historyCounts(order);
        var sequence = mutation(order);
        var writtenGroups = new AtomicInteger();
        doAnswer(invocation -> {
            invocation.callRealMethod();
            if (writtenGroups.incrementAndGet() == 2)
                throw new IllegalStateException("history failed after writing second source group");
            return null;
        }).when(historyWriter).transitioned(any(Order.class), any(List.class), eq(f.user.getId()),
            any(OrderItemStatus.class), eq(OrderItemStatus.SERVED), any(Instant.class));
        bulk(order, "mark-served").error(500, "INTERNAL_ERROR");
        assertThat(writtenGroups.get()).isEqualTo(2);
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(historyCounts(order)).isEqualTo(counts);
        assertThat(mutation(order)).isEqualTo(sequence);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isZero();
    }

    @Test
    void servingMixedSourcesRollsBackItemsAndAllHistoriesWhenAuditFailsAfterWriting() throws Exception {
        var order = prepare(false, OrderItemStatus.PENDING, OrderItemStatus.COOKING, OrderItemStatus.READY);
        var counts = historyCounts(order);
        var sequence = mutation(order);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("audit failed after mixed-source history write");
        }).when(audit).record(eq(f.restaurant.getId()), any(), eq("ORDER_ITEMS_MARKED_SERVED"),
            any(), any(), any(), any(), any());
        bulk(order, "mark-served").error(500, "INTERNAL_ERROR");
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(historyCounts(order)).isEqualTo(counts);
        assertThat(mutation(order)).isEqualTo(sequence);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isZero();
    }

    @Test
    void persistenceConflictsMapTo409AndRollBackChangedLines() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING);
        doThrow(new ObjectOptimisticLockingFailureException(Order.class, order.id())).when(orders).saveAndFlush(any());
        bulk(order, "mark-ready").error(409, "CONCURRENT_ORDER_UPDATE");
        reset(orders);
        assertThat(detail(order).body).isEqualTo(order.body);
        doThrow(new DataIntegrityViolationException("fixture persistence conflict")).when(orders).saveAndFlush(any());
        bulk(order, "mark-ready").error(409, "CONCURRENT_ORDER_UPDATE");
        reset(orders);
        assertThat(detail(order).body).isEqualTo(order.body);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"mark-ready", "mark-served"})
    void concurrentIdenticalBulkRequestsProduceOneMutationAndOneAudit(String action) throws Exception {
        var source = action.equals("mark-ready") ? OrderItemStatus.COOKING : OrderItemStatus.READY;
        var order = prepare(false, source, source);
        var counts = historyCounts(order);
        var sequence = mutation(order);
        var responses = race(() -> bulk(order, action), () -> bulk(order, action));
        assertThat(responses).extracting(Result::status).containsExactly(200, 200);
        assertThat(responses.get(0).body).isEqualTo(responses.get(1).body);
        assertThat(mutation(order)).isEqualTo(sequence + 1);
        assertThat(historyCounts(order)).containsExactly(counts.get(0) + 1, counts.get(1) + 1);
        assertThat(events(action.equals("mark-ready") ? "ORDER_ITEMS_MARKED_READY" : "ORDER_ITEMS_MARKED_SERVED"))
            .isEqualTo(1);
    }

    @Test
    void concurrentDirectServingOfMixedSourcesProducesOneMutationHistoryPerLineAndAudit() throws Exception {
        var order = prepare(false, OrderItemStatus.PENDING, OrderItemStatus.COOKING, OrderItemStatus.READY);
        var counts = historyCounts(order);
        var sequence = mutation(order);
        var responses = race(() -> bulk(order, "mark-served"), () -> bulk(order, "mark-served"));
        assertThat(responses).extracting(Result::status).containsExactly(200, 200);
        assertThat(responses.get(0).body).isEqualTo(responses.get(1).body);
        assertStatuses(responses.get(0), "SERVED", "SERVED", "SERVED");
        assertThat(mutation(order)).isEqualTo(sequence + 1);
        assertThat(historyCounts(order)).containsExactly(counts.get(0) + 1, counts.get(1) + 1, counts.get(2) + 1);
        assertLastHistory(order, 0, OrderItemStatus.PENDING, OrderItemStatus.SERVED, sequence + 1);
        assertLastHistory(order, 1, OrderItemStatus.COOKING, OrderItemStatus.SERVED, sequence + 1);
        assertLastHistory(order, 2, OrderItemStatus.READY, OrderItemStatus.SERVED, sequence + 1);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
    }

    @Test
    void concurrentSingleAndBulkReadyHaveNoLostTransitionsOrDuplicateHistory() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING, OrderItemStatus.COOKING);
        var counts = historyCounts(order);
        var responses = race(() -> bulk(order, "mark-ready"), () -> single(order, 0, "mark-ready"));
        responses.get(1).expect(200);
        assertThat(responses.get(0).status).isIn(200, 409);
        var current = detail(order);
        if (responses.get(0).status == 409) {
            responses.get(0).error(409, "CONCURRENT_ORDER_UPDATE");
            current = bulk(current, "mark-ready");
            current.expect(200);
        }
        assertStatuses(current, "READY", "READY");
        assertThat(historyCounts(order)).containsExactly(counts.get(0) + 1, counts.get(1) + 1);
        assertThat(events("ORDER_ITEMS_MARKED_READY")).isEqualTo(1);
    }

    @Test
    void concurrentAppendAndBulkSerializeAtTheAggregateBoundary() throws Exception {
        var order = prepare(false, OrderItemStatus.COOKING);
        var responses = race(() -> bulk(order, "mark-ready"), () -> append(order));
        assertThat(responses).extracting(Result::status).containsExactlyInAnyOrder(200, 409);
        var current = detail(order);
        assertThat(current.version()).isEqualTo(order.version() + 1);
        if (responses.get(0).status == 200) {
            responses.get(1).error(409, "CONCURRENT_ORDER_UPDATE");
            assertStatuses(current, "READY");
            assertThat(events("ORDER_ITEMS_ADDED")).isZero();
        } else {
            responses.get(0).error(409, "INVALID_ORDER_ITEM_TRANSITION");
            assertStatuses(current, "COOKING", "PENDING");
            assertThat(events("ORDER_ITEMS_MARKED_READY")).isZero();
            assertThat(current.body.path("totalAmount").decimalValue()).isEqualByComparingTo("100000.00");
        }
    }

    @Test
    void oldRetryCannotReadyAnAppendedBatchAfterItsItemsStartCooking() throws Exception {
        var original = prepare(false, OrderItemStatus.COOKING);
        var oldIntent = bulk(original, "mark-ready");
        oldIntent.expect(200);
        var added = append(oldIntent);
        added.expect(200);
        var cooking = single(added, 1, "start-cooking");
        cooking.expect(200);
        bulk(oldIntent, "mark-ready").error(409, "CONCURRENT_ORDER_UPDATE");
        assertThat(detail(original).body).isEqualTo(cooking.body);
        assertStatuses(cooking, "READY", "COOKING");
    }

    @Test
    void oldServingRetryCannotServeNewlyAppendedPendingLines() throws Exception {
        var original = prepare(false, OrderItemStatus.PENDING);
        var oldIntent = bulk(original, "mark-served");
        oldIntent.expect(200);
        var added = append(oldIntent);
        added.expect(200);
        var counts = historyCounts(added);
        var sequence = mutation(added);
        bulk(oldIntent, "mark-served").error(409, "CONCURRENT_ORDER_UPDATE");
        assertThat(detail(original).body).isEqualTo(added.body);
        assertStatuses(added, "SERVED", "PENDING");
        assertThat(historyCounts(added)).isEqualTo(counts);
        assertThat(mutation(added)).isEqualTo(sequence);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);

        var served = bulk(added, "mark-served");
        served.expect(200);
        assertStatuses(served, "SERVED", "SERVED");
        assertThat(historyCounts(served)).containsExactly(counts.get(0), counts.get(1) + 1);
        assertLastHistory(served, 1, OrderItemStatus.PENDING, OrderItemStatus.SERVED, sequence + 1);
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(2);
    }

    @Test
    void directlyServingPendingItemsDoesNotBypassCompletionPaymentRequirement() throws Exception {
        var pending = prepare(false, OrderItemStatus.PENDING);
        var served = bulk(pending, "mark-served");
        served.expect(200);
        call(post(path(served) + "/complete"), Map.of("expectedVersion", served.version()))
            .error(409, "ORDER_NOT_PAID");
        assertThat(detail(served).body).isEqualTo(served.body);
        assertThat(events("ORDER_COMPLETED")).isZero();
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
    }

    @Test
    void concurrentPaymentAndServingCanResumeWithoutLosingEitherUpdate() throws Exception {
        var order = prepare(false, OrderItemStatus.READY);
        var responses = race(() -> bulk(order, "mark-served"), () -> pay(order));
        assertThat(responses.stream().filter(result -> result.status == 200 || result.status == 201).count()).isEqualTo(1);
        assertThat(responses).extracting(Result::status).contains(409);
        var current = detail(order);
        if (current.body.path("paymentStatus").asText().equals("UNPAID")) pay(current).expect(201);
        else bulk(current, "mark-served").expect(200);
        var finished = detail(order);
        assertThat(finished.body.path("paymentStatus").asText()).isEqualTo("PAID");
        assertThat(finished.body.path("status").asText()).isEqualTo("SERVED");
        assertThat(events("ORDER_ITEMS_MARKED_SERVED")).isEqualTo(1);
        assertThat(events("PAYMENT_RECORDED")).isEqualTo(1);
    }

    private Fixture fixture() {
        var restaurant = new Restaurant();
        restaurant.setCode("BULK" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Bulk transition fixture");
        restaurant = restaurants.saveAndFlush(restaurant);
        var user = new User();
        user.setRestaurantId(restaurant.getId());
        user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Owner"); user.setEmail(UUID.randomUUID() + "@example.test"); user.setPasswordHash("fixture-only");
        user = users.saveAndFlush(user);
        var subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurant.getId()); subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setStartAt(NOW.minusSeconds(60));
        subscription.setEndAt(NOW.plusSeconds(3600)); subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND"); subscription.setFeatureSnapshot(snapshot(FEATURES));
        subscription = subscriptions.saveAndFlush(subscription);
        var group = new ItemGroup(); group.initialize(restaurant.getId(), NOW); group.setName("Main");
        group = groups.saveAndFlush(group);
        var item = new Item(); item.initialize(restaurant.getId(), NOW); item.setGroupId(group.getId());
        item.setName("Pho"); item.setItemType(ItemType.MENU_ITEM); item.setBaseUnit("bowl");
        item.setSalePrice(new BigDecimal("50000.00")); item.setCostPrice(new BigDecimal("12000.00"));item.setCostConfigured(true);
        item = menuItems.saveAndFlush(item);
        var table = new RestaurantTable(); table.initialize(restaurant.getId(), NOW); table.setCode("B01");
        table.setName("Table 1"); table.setQrToken("t" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(10));
        table = tables.saveAndFlush(table);
        var session = new TableSession(); session.initialize(restaurant.getId(), NOW); session.setTableId(table.getId());
        session.setSessionCode("SESSION"); session.setOpenedBy(user.getId()); session.setOpenedAt(NOW);
        session = sessions.saveAndFlush(session);
        return new Fixture(restaurant, user, subscription, item, session);
    }

    private Map<String, Object> snapshot(List<String> features) {
        return new SubscriptionFeatureSnapshot(1, "PRO", features.stream()
            .map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(), NOW).toMap();
    }

    private void setFeatures(List<String> features) {
        var subscription = subscriptions.findById(f.subscription.getId()).orElseThrow();
        subscription.setFeatureSnapshot(snapshot(features)); subscriptions.saveAndFlush(subscription);
    }

    private Result create(boolean dineIn, int count, String mode) throws Exception {
        var request = new LinkedHashMap<String, Object>();
        request.put("serviceType", dineIn ? "DINE_IN" : "TAKEAWAY");
        request.put("sourceChannel", dineIn ? "WAITER" : "CASHIER");
        if (dineIn) request.put("tableSessionId", f.session.getId());
        request.put("submissionMode", mode); request.put("customerName", "Guest");
        request.put("customerPhone", "0900000000"); request.put("note", "Order note");
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) items.add(line("line " + i));
        request.put("items", items);
        var response = call(post(ORDERS).header("Idempotency-Key", "bulk-" + UUID.randomUUID()), request);
        response.expect(201);
        return response;
    }

    private Map<String, Object> line(String note) { return Map.of("itemId", f.item.getId(), "quantity", BigDecimal.ONE, "note", note); }

    private Result prepare(boolean dineIn, OrderItemStatus... statuses) throws Exception {
        var current = create(dineIn, statuses.length, "SUBMIT");
        for (int i = 0; i < statuses.length; i++) {
            var status = statuses[i];
            if (status == OrderItemStatus.CANCELLED) {
                current = call(post(path(current) + "/items/" + current.lineId(i) + "/cancel"),
                    Map.of("expectedVersion", current.version(), "reason", "cancel fixture"));
                current.expect(200);
                continue;
            }
            if (status != OrderItemStatus.PENDING) { current = single(current, i, "start-cooking"); current.expect(200); }
            if (status == OrderItemStatus.READY || status == OrderItemStatus.SERVED) {
                current = single(current, i, "mark-ready"); current.expect(200);
            }
            if (status == OrderItemStatus.SERVED) { current = single(current, i, "mark-served"); current.expect(200); }
        }
        return current;
    }

    private Result bulk(Result order, String action) throws Exception { return bulk(order, action, order.version()); }
    private Result bulk(Result order, String action, long expectedVersion) throws Exception {
        return call(post(path(order) + "/" + action), version(expectedVersion));
    }
    private Result single(Result order, int lineIndex, String action) throws Exception {
        return call(post(path(order) + "/items/" + order.lineId(lineIndex) + "/" + action), version(order.version()));
    }
    private Result append(Result order) throws Exception {
        return call(post(path(order) + "/items").header("Idempotency-Key", "append-" + UUID.randomUUID()),
            Map.of("expectedVersion", order.version(), "submissionMode", "SUBMIT", "items", List.of(line("additional"))));
    }
    private Result pay(Result order) throws Exception {
        return call(post(path(order) + "/payments").header("Idempotency-Key", "payment-" + UUID.randomUUID()),
            Map.of("expectedOrderVersion", order.version(), "paymentMethod", "CASH", "amount", order.body.path("totalAmount").decimalValue()));
    }
    private Map<String, Long> version(long expectedVersion) { return Map.of("expectedOrderVersion", expectedVersion); }
    private String path(Result order) { return ORDERS + "/" + order.id(); }
    private Result detail(Result order) throws Exception { return call(get(path(order)), null); }
    private CurrentUser actor(Set<String> codes) {
        return new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), "OWNER", codes);
    }
    private CurrentUser assignRole(String code) {
        jdbc.update("update users set role_id=(select id from roles where code=? and restaurant_id is null) where id=?", code, f.user.getId());
        return new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), code,
            permissions.getEffectivePermissionCodes(f.user.getId()));
    }
    private Result call(MockHttpServletRequestBuilder request, Object body) throws Exception { return callAs(request, body, actor(ALL)); }
    private Result callAs(MockHttpServletRequestBuilder request, Object body, CurrentUser actor) throws Exception {
        request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null,
            actor.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
        return perform(request, body);
    }
    private Result bearer(MockHttpServletRequestBuilder request, Object body, CurrentUser actor) throws Exception {
        return perform(request.header("Authorization", "Bearer " + jwt.createAccessToken(actor)), body);
    }
    private Result perform(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) request.content(json.writeValueAsString(body));
        var response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andReturn().getResponse();
        return new Result(response.getStatus(), response.getContentAsString().isBlank()
            ? json.createObjectNode() : json.readTree(response.getContentAsString()), response.getHeader("Cache-Control"));
    }
    private List<OrderItemStatusHistory> history(Result order, int index) {
        return histories.findAllByRestaurantIdAndOrderItemIdOrderByChangeSequenceAsc(f.restaurant.getId(), order.lineId(index));
    }
    private List<Integer> historyCounts(Result order) {
        var counts = new ArrayList<Integer>();
        for (int i = 0; i < order.body.path("items").size(); i++) counts.add(history(order, i).size());
        return counts;
    }
    private long mutation(Result order) { return jdbc.queryForObject("select mutation_sequence from orders where id=?", Long.class, order.id()); }
    private long events(String event) {
        return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?", Long.class, f.restaurant.getId(), event);
    }
    private void assertStatuses(Result order, String... expected) {
        var statuses = new ArrayList<String>();
        order.body.path("items").forEach(item -> statuses.add(item.path("status").asText()));
        assertThat(statuses).containsExactly(expected);
    }
    private void assertLastHistory(Result order, int index, OrderItemStatus from, OrderItemStatus to, long sequence) {
        var entries = history(order, index);
        var last = entries.getLast();
        assertThat(last.getRestaurantId()).isEqualTo(f.restaurant.getId());
        assertThat(last.getOrderItemId()).isEqualTo(order.lineId(index));
        assertThat(last.getFromStatus()).isEqualTo(from);
        assertThat(last.getToStatus()).isEqualTo(to);
        assertThat(last.getChangedBy()).isEqualTo(f.user.getId());
        assertThat(last.getChangedAt()).isEqualTo(NOW);
        assertThat(last.getChangeSequence()).isEqualTo(sequence);
    }
    private void assertUnchangedSnapshotsAndMoney(Result before, Result after) {
        for (String field : List.of("tableSessionId", "serviceType", "sourceChannel", "customerName", "customerPhone",
            "guestCount", "currencyCode", "subtotalAmount", "discountAmount", "taxAmount", "serviceChargeAmount",
            "totalAmount", "paidAmount", "paymentStatus", "note", "createdBy", "createdAt", "confirmedAt"))
            assertThat(after.body.path(field)).as(field).isEqualTo(before.body.path(field));
        for (int i = 0; i < before.body.path("items").size(); i++) {
            var oldLine = (ObjectNode) before.body.path("items").get(i).deepCopy();
            var newLine = (ObjectNode) after.body.path("items").get(i).deepCopy();
            oldLine.remove("status"); newLine.remove("status");
            assertThat(newLine).as("immutable item fields %s", i).isEqualTo(oldLine);
        }
    }
    private void assertAuditSnapshots(Result before, Result after, String event) throws Exception {
        var auditRow = jdbc.queryForMap("select before_data::text as before_state,after_data::text as after_state,actor_user_id from audit_logs where entity_id=? and action_code=?", before.id(), event);
        var oldState = json.readTree((String) auditRow.get("before_state"));
        var newState = json.readTree((String) auditRow.get("after_state"));
        assertThat(oldState.path("status").asText()).isEqualTo(before.body.path("status").asText());
        assertThat(newState.path("status").asText()).isEqualTo(after.body.path("status").asText());
        assertThat(auditRow.get("actor_user_id")).isEqualTo(f.user.getId());
        assertThat(newState.path("mutationSequence").asLong()).isEqualTo(oldState.path("mutationSequence").asLong() + 1);
    }
    private List<Result> race(Callable<Result> firstCall, Callable<Result> secondCall) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return firstCall.call(); });
            var second = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return secondCall.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
