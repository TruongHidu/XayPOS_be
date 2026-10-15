package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.possaas.modules.order.repository.OrderRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.table.application.port.OrderSessionClosureCommand;
import com.possaas.modules.table.entity.RestaurantTable;
import com.possaas.modules.table.entity.TableArea;
import com.possaas.modules.table.entity.TableSession;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import com.possaas.modules.table.repository.TableAreaRepository;
import com.possaas.modules.table.repository.TableSessionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.springframework.transaction.IllegalTransactionStateException;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(OrderCompletionSessionIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderCompletionSessionIntegrationTest {
    private static final String SCHEMA = "it_order_completion_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-14T00:00:00Z");
    private static final String ORDERS = "/api/v1/orders";
    private static final List<String> FEATURES = List.of("ORDER_MANAGEMENT", "POS_QUICK_ORDER",
        "TABLE_MANAGEMENT", "PAYMENT_MANAGEMENT", "KITCHEN_DISPLAY");
    private static final Set<String> ALL = Set.of("ORDER_VIEW", "ORDER_CREATE", "ORDER_UPDATE",
        "ORDER_CANCEL", "ORDER_COMPLETE", "ORDER_MARK_SERVED", "KITCHEN_START_COOKING", "KITCHEN_MARK_READY",
        "TABLE_VIEW", "TABLE_OPEN", "TABLE_CLOSE", "TABLE_TRANSFER", "PAYMENT_CREATE", "PAYMENT_CONFIRM");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock completionClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
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
    @Autowired TableAreaRepository areas;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @MockitoSpyBean OrderRepository orders;
    @MockitoSpyBean TableSessionRepository sessions;
    @MockitoSpyBean OrderSessionClosureCommand closure;
    @MockitoSpyBean AuditService audit;
    private Fixture f;

    private record Fixture(Restaurant restaurant, User user, RestaurantSubscription subscription,
                           Item item, RestaurantTable table, TableSession session) {}
    private record Result(int status, JsonNode body, String cacheControl) {
        UUID id() { return UUID.fromString(body.path("id").asText()); }
        long version() { return body.path("version").asLong(); }
        UUID lineId(int index) { return UUID.fromString(body.path("items").get(index).path("id").asText()); }
        void expect(int expected) { assertThat(status).as(body.toString()).isEqualTo(expected); }
        void error(int expected, String code) { expect(expected); assertThat(body.path("code").asText()).isEqualTo(code); }
    }
    private record State(JsonNode order, Map<String, Object> session, long mutation, long history,
                         long completionAudits, long closureAudits) {}

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        f = fixture();
    }

    @AfterEach
    void resetFailureInjection() { reset(audit, orders, sessions, closure); }

    @AfterAll
    void dropOwnSchema() {
        if (!SCHEMA.matches("it_order_completion_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void paidServedDineInCompletesAndClosesWithOneMutationEachAndImmutableFinancialData() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED, OrderItemStatus.SERVED, OrderItemStatus.CANCELLED);
        var before = state(order);
        var tableBefore = jdbc.queryForMap("select * from restaurant_tables where id=?", f.table.getId());
        var paymentsBefore = jdbc.queryForList("select * from payments where order_id=?", order.id());
        jdbc.update("update items set name='Catalog changed',base_unit='plate',sale_price=999999 where id=?", f.item.getId());

        var completed = complete(order);
        completed.expect(200);
        assertThat(completed.cacheControl).contains("no-store");
        assertThat(completed.body.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(Instant.parse(completed.body.path("completedAt").asText())).isEqualTo(NOW);
        assertThat(completed.version()).isEqualTo(order.version() + 1);
        assertThat(mutation(order)).isEqualTo(before.mutation + 1);
        var session = sessions.findById(f.session.getId()).orElseThrow();
        assertThat(session.getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(session.getVersion()).isEqualTo(f.session.getVersion() + 1);
        assertThat(session.getClosedAt()).isEqualTo(NOW).isAfterOrEqualTo(session.getOpenedAt());
        assertThat(session.getClosedBy()).isEqualTo(f.user.getId());
        assertThat(events("ORDER_COMPLETED")).isEqualTo(1);
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
        assertThat(historyCount(order)).isEqualTo(before.history);
        assertThat(completed.body.path("items")).isEqualTo(order.body.path("items"));
        for (String field : List.of("tableSessionId", "serviceType", "sourceChannel", "customerName", "customerPhone",
            "guestCount", "currencyCode", "subtotalAmount", "discountAmount", "taxAmount", "serviceChargeAmount",
            "totalAmount", "paidAmount", "paymentStatus", "note", "createdBy", "createdAt", "confirmedAt"))
            assertThat(completed.body.path(field)).as(field).isEqualTo(order.body.path(field));
        assertThat(jdbc.queryForList("select * from payments where order_id=?", order.id())).isEqualTo(paymentsBefore);
        assertThat(jdbc.queryForMap("select * from restaurant_tables where id=?", f.table.getId())).isEqualTo(tableBefore);
        var table = call(get("/api/v1/tables/" + f.table.getId()), null);
        table.expect(200);
        assertThat(table.body.path("occupied").asBoolean()).isFalse();
        assertThat(table.body.path("currentSession").isNull()).isTrue();
        assertThat(table.body.path("canOpen").asBoolean()).isTrue();
        assertAudit("ORDER_COMPLETED", order.id(), "SERVED", "COMPLETED");
        assertAudit("TABLE_SESSION_CLOSED", f.session.getId(), "OPEN", "CLOSED");
    }

    @Test
    void takeawayCompletionAndRetryNeedNoTablePermissionFeatureOrPort() throws Exception {
        var order = prepared(false, OrderItemStatus.SERVED);
        setFeatures(List.of("ORDER_MANAGEMENT"));
        clearInvocations(closure);
        var actor = actor(Set.of("ORDER_COMPLETE"));
        var completed = callAs(post(path(order) + "/complete"), version(order.version()), actor);
        completed.expect(200);
        callAs(post(path(order) + "/complete"), version(order.version()), actor).expect(200);
        verifyNoInteractions(closure);
        assertThat(completed.body.path("tableSessionId").isNull()).isTrue();
        assertThat(events("ORDER_COMPLETED")).isEqualTo(1);
        assertThat(events("TABLE_SESSION_CLOSED")).isZero();
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    @Test
    void unpaidOrderIsRejectedWithoutMutatingSessionOrAudit() throws Exception {
        var order = create(true, 1, null);
        setLineStatus(order, "SERVED");
        var current = detail(order);
        var before = state(current);
        complete(current).error(409, "ORDER_NOT_PAID");
        assertUnchanged(current, before);
    }

    @ParameterizedTest
    @CsvSource({"PAID,49999.99,50000.00", "PARTIALLY_PAID,25000,50000", "PAID,0,0", "REFUNDED,50000,50000"})
    void paymentStateBalanceAndPositiveTotalAreRechecked(String paymentStatus, String paid, String total) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        jdbc.update("update orders set payment_status=?,paid_amount=?,subtotal_amount=?,total_amount=? where id=?",
            paymentStatus, new BigDecimal(paid), new BigDecimal(total), new BigDecimal(total), order.id());
        var current = detail(order);
        var before = state(current);
        complete(current).error(409, "ORDER_NOT_PAID");
        assertUnchanged(current, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PENDING", "COOKING", "READY"})
    void everyUnservedItemBlocksPaidCompletionWithoutAdvancingAnyItem(String blocked) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED, OrderItemStatus.valueOf(blocked));
        var before = state(order);
        complete(order).error(409, "ORDER_NOT_READY_FOR_COMPLETION");
        assertUnchanged(order, before);
    }

    @Test
    void noEffectiveItemsCannotCompleteEvenIfPaid() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        jdbc.update("update order_items set status='CANCELLED',cancel_reason='legacy invalid aggregate' where order_id=?", order.id());
        var current = detail(order);
        var before = state(current);
        complete(current).error(409, "ORDER_NOT_READY_FOR_COMPLETION");
        assertUnchanged(current, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_COMPLETE", "TABLE_CLOSE"})
    void missingPermissionIsCheckedBeforeMutationAndCompletedNoOp(String missing) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var restricted = ALL.stream().filter(code -> !code.equals(missing)).collect(java.util.stream.Collectors.toSet());
        var before = state(order);
        callAs(post(path(order) + "/complete"), version(order.version()), actor(restricted)).error(403, "FORBIDDEN");
        assertUnchanged(order, before);
        var completed = complete(order);
        completed.expect(200);
        var after = state(completed);
        callAs(post(path(order) + "/complete"), version(order.version()), actor(restricted)).error(403, "FORBIDDEN");
        assertUnchanged(completed, after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_MANAGEMENT", "TABLE_MANAGEMENT"})
    void missingFeatureIsCheckedBeforeMutationAndCompletedNoOp(String missing) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        setFeatures(FEATURES.stream().filter(code -> !code.equals(missing)).toList());
        complete(order).error(403, "FEATURE_NOT_ENTITLED");
        setFeatures(FEATURES);
        assertUnchanged(order, before);
        var completed = complete(order);
        completed.expect(200);
        var after = state(completed);
        setFeatures(FEATURES.stream().filter(code -> !code.equals(missing)).toList());
        complete(order).error(403, "FEATURE_NOT_ENTITLED");
        setFeatures(FEATURES);
        assertUnchanged(completed, after);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASHIER", "MANAGER"})
    void migratedCashierAndManagerCanCompleteDineInUsingRealJwt(String roleCode) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var actor = assignRole(roleCode);
        assertThat(actor.permissions()).contains("ORDER_COMPLETE", "TABLE_CLOSE");
        bearer(post(path(order) + "/complete"), version(order.version()), actor).expect(200);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getClosedBy()).isEqualTo(f.user.getId());
        jdbc.update("update users set role_id=(select id from roles where restaurant_id is null and code='KITCHEN') where id=?", f.user.getId());
        assertThat(permissions.getEffectivePermissionCodes(f.user.getId())).doesNotContain("ORDER_COMPLETE", "TABLE_CLOSE");
    }

    @Test
    void userDenyTableCloseOverridesCashierGrantEvenOnCompletedNoOp() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var cashier = assignRole("CASHIER");
        var completed = bearer(post(path(order) + "/complete"), version(order.version()), cashier);
        completed.expect(200);
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'DENY',? from permissions where code='TABLE_CLOSE'",
            f.user.getId(), f.user.getId());
        var denied = assignRole("CASHIER");
        assertThat(denied.hasPermission("TABLE_CLOSE")).isFalse();
        var before = state(completed);
        bearer(post(path(order) + "/complete"), version(order.version()), denied).error(403, "FORBIDDEN");
        assertUnchanged(completed, before);
    }

    @Test
    void tenantCustomRoleWithEffectivePermissionsCanCompleteWithoutRoleChecks() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var role = new Role(); role.setRestaurantId(f.restaurant.getId()); role.setCode("SERVICE_CUSTOM"); role.setName("Service");
        role = roles.saveAndFlush(role);
        jdbc.update("insert into role_permissions(role_id,permission_id) select ?,id from permissions where code in ('ORDER_COMPLETE','TABLE_CLOSE')", role.getId());
        jdbc.update("update users set role_id=? where id=?", role.getId(), f.user.getId());
        var actor = new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), role.getCode(),
            permissions.getEffectivePermissionCodes(f.user.getId()));
        bearer(post(path(order) + "/complete"), version(order.version()), actor).expect(200);
    }

    @Test
    void oldVersionCannotMutateOrderOrSession() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        complete(order, order.version() - 1).error(409, "CONCURRENT_ORDER_UPDATE");
        assertUnchanged(order, before);
    }

    @Test
    void completionRetryAndManualCloseRetryDoNotDuplicateAuditOrVersion() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var completed = complete(order); completed.expect(200);
        var before = state(completed);
        var retry = complete(order); retry.expect(200);
        assertThat(retry.body).isEqualTo(completed.body);
        var manual = close(0); manual.expect(200);
        assertThat(manual.body.path("status").asText()).isEqualTo("CLOSED");
        assertUnchanged(completed, before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLOSED", "CANCELLED"})
    void terminalSessionBlocksFirstCompletionAndNeverReopens(String terminal) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        jdbc.update("update table_sessions set status=?,closed_at=opened_at,closed_by=opened_by,cancel_reason='legacy fixture' where id=?",
            terminal, f.session.getId());
        var before = state(order);
        complete(order).error(409, "TABLE_SESSION_NOT_OPEN");
        assertUnchanged(order, before);
    }

    @Test
    void legacyCompletedOpenSessionIsRepairedWithoutRewritingCompletedOrder() throws Exception {
        var order = legacyCompleted(prepared(true, OrderItemStatus.SERVED));
        var before = state(order);
        var repaired = complete(order, 0); repaired.expect(200);
        assertThat(repaired.body).isEqualTo(order.body);
        assertThat(mutation(order)).isEqualTo(before.mutation);
        assertThat(events("ORDER_COMPLETED")).isZero();
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
        assertThat(historyCount(order)).isEqualTo(before.history);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        complete(order, 0).expect(200);
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAYMENT", "ITEMS"})
    void legacyRepairRechecksPaymentAndServingRatherThanBlindlyClosing(String invalid) throws Exception {
        var order = legacyCompleted(prepared(true, OrderItemStatus.SERVED));
        if (invalid.equals("PAYMENT")) jdbc.update("update orders set paid_amount=1 where id=?", order.id());
        else setLineStatus(order, "READY");
        var current = detail(order);
        var before = state(current);
        complete(current, 0).error(409, invalid.equals("PAYMENT") ? "ORDER_NOT_PAID" : "ORDER_NOT_READY_FOR_COMPLETION");
        assertUnchanged(current, before);
    }

    @Test
    void legacyRepairCannotCloseWhileAnotherOrderIsServingInSameSession() throws Exception {
        var old = legacyCompleted(prepared(true, OrderItemStatus.SERVED));
        var active = create(true, 1, null);
        var before = state(old);
        complete(old, 0).error(409, "TABLE_SESSION_NOT_READY_TO_CLOSE");
        assertUnchanged(old, before);
        assertThat(detail(active).body).isEqualTo(active.body);
    }

    @Test
    void anotherHistoricalOrderWithUnresolvedMoneyRollsBackNewCompletionAfterFlush() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        historicalOrder(order, "COMPLETED", "UNPAID", BigDecimal.ZERO);
        var before = state(order);
        complete(order).error(409, "TABLE_SESSION_NOT_READY_TO_CLOSE");
        assertUnchanged(order, before);
    }

    @Test
    void paidCompletedAndUnpaidCancelledHistoryAllowClosureAndRemainUnchanged() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var completedHistory = historicalOrder(order, "COMPLETED", "PAID", new BigDecimal("50000"));
        var cancelledHistory = historicalOrder(order, "CANCELLED", "UNPAID", BigDecimal.ZERO);
        var first = jdbc.queryForMap("select * from orders where id=?", completedHistory);
        var second = jdbc.queryForMap("select * from orders where id=?", cancelledHistory);
        complete(order).expect(200);
        assertThat(jdbc.queryForMap("select * from orders where id=?", completedHistory)).isEqualTo(first);
        assertThat(jdbc.queryForMap("select * from orders where id=?", cancelledHistory)).isEqualTo(second);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ORDER_COMPLETED", "TABLE_SESSION_CLOSED"})
    void auditFailureAfterWritingRollsBackBothEntitiesAndAllAudits(String event) throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        doAnswer(invocation -> { invocation.callRealMethod(); throw new IllegalStateException("audit unavailable after write"); })
            .when(audit).record(eq(f.restaurant.getId()), any(), eq(event), any(), any(), any(), any(), any());
        complete(order).error(500, "INTERNAL_ERROR");
        assertUnchanged(order, before);
    }

    @Test
    void sessionPersistenceFailureAfterOrderFlushRollsBackCompletion() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        doThrow(new IllegalStateException("session writer unavailable")).when(sessions).saveAndFlush(any());
        complete(order).error(500, "INTERNAL_ERROR");
        assertUnchanged(order, before);
    }

    @Test
    void orderOptimisticConflictIsStable409AndSessionRemainsOpen() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        doThrow(new ObjectOptimisticLockingFailureException(Order.class, order.id())).when(orders).saveAndFlush(any());
        complete(order).error(409, "CONCURRENT_ORDER_UPDATE");
        assertUnchanged(order, before);
    }

    @Test
    void sessionPersistenceConflictFromOrderRouteIs409AndRollsBackBothEntities() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        doThrow(new DataIntegrityViolationException("fixture session constraint conflict")).when(sessions).saveAndFlush(any());
        var result = complete(order); result.expect(409);
        assertThat(result.body.path("code").asText()).isIn("CONCURRENT_ORDER_UPDATE", "CONCURRENT_TABLE_UPDATE");
        assertUnchanged(order, before);
    }

    @Test
    void transferredSessionClosesAtCurrentTableWithoutChangingQrOrTransferHistory() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var target = freeTable();
        var moved = transfer(target, 0); moved.expect(200);
        var history = jdbc.queryForList("select * from table_session_transfers where session_id=?", f.session.getId());
        var beforeTarget = jdbc.queryForMap("select * from restaurant_tables where id=?", target.getId());
        var beforeSource = jdbc.queryForMap("select * from restaurant_tables where id=?", f.table.getId());
        complete(order).expect(200);
        var closed = sessions.findById(f.session.getId()).orElseThrow();
        assertThat(closed.getTableId()).isEqualTo(target.getId());
        assertThat(closed.getVersion()).isEqualTo(2);
        assertThat(closed.getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(jdbc.queryForList("select * from table_session_transfers where session_id=?", f.session.getId())).isEqualTo(history);
        assertThat(jdbc.queryForMap("select * from restaurant_tables where id=?", target.getId())).isEqualTo(beforeTarget);
        assertThat(jdbc.queryForMap("select * from restaurant_tables where id=?", f.table.getId())).isEqualTo(beforeSource);
        assertThat(detail(order).body.path("tableSessionId").asText()).isEqualTo(f.session.getId().toString());
    }

    @Test
    void retryOldOrderDoesNotCloseNewSessionOnSamePhysicalTable() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var completed = complete(order); completed.expect(200);
        var next = call(post("/api/v1/tables/" + f.table.getId() + "/sessions"), version(0)); next.expect(201);
        assertThat(next.id()).isNotEqualTo(f.session.getId());
        var beforeNew = jdbc.queryForMap("select * from table_sessions where id=?", next.id());
        complete(order).expect(200);
        assertThat(jdbc.queryForMap("select * from table_sessions where id=?", next.id())).isEqualTo(beforeNew);
        assertThat(next.body.path("status").asText()).isEqualTo("OPEN");
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    void inactivePhysicalTableAndAreaDoNotPreventFinishingExistingSeating() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var area = new TableArea(); area.initialize(f.restaurant.getId(), NOW); area.setName("Old area"); area.setActive(false);
        area = areas.saveAndFlush(area);
        jdbc.update("update restaurant_tables set status='INACTIVE',area_id=? where id=?", area.getId(), f.table.getId());
        complete(order).expect(200);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(tables.findById(f.table.getId()).orElseThrow().getStatus().name()).isEqualTo("INACTIVE");
        assertThat(areas.findById(area.getId()).orElseThrow().isActive()).isFalse();
    }

    @Test
    void futureOpenedSessionCannotCloseAndOrderCompletionRollsBack() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        jdbc.update("update table_sessions set opened_at=? where id=?", Timestamp.from(NOW.plusSeconds(1)), f.session.getId());
        var before = state(order);
        complete(order).error(409, "TABLE_SESSION_NOT_READY_TO_CLOSE");
        assertUnchanged(order, before);
    }

    @Test
    void concurrentIdenticalCompletionsHaveOneCompletionClosureAndVersionIncrement() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var before = state(order);
        var result = race(() -> complete(order), () -> complete(order));
        assertThat(result).extracting(Result::status).containsExactly(200, 200);
        assertThat(result.get(0).body).isEqualTo(result.get(1).body);
        assertThat(result.get(0).version()).isEqualTo(order.version() + 1);
        assertThat(mutation(order)).isEqualTo(before.mutation + 1);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getVersion()).isEqualTo(1);
        assertThat(events("ORDER_COMPLETED")).isEqualTo(1);
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    void completeAndManualCloseSerializeWithoutDuplicateClosure() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var result = race(() -> complete(order), () -> close(0));
        result.get(0).expect(200);
        assertThat(result.get(1).status).isIn(200, 409);
        if (result.get(1).status == 409) result.get(1).error(409, "TABLE_SESSION_NOT_READY_TO_CLOSE");
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    void completeAndTransferSerializeAndCloseTheActualSessionLocation() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var target = freeTable();
        var result = race(() -> complete(order), () -> transfer(target, 0));
        result.get(0).expect(200);
        assertThat(result.get(1).status).isIn(200, 409);
        var closed = sessions.findById(f.session.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        if (result.get(1).status == 200) assertThat(closed.getTableId()).isEqualTo(target.getId());
        else {
            result.get(1).error(409, "INVALID_TABLE_SESSION_TRANSITION");
            assertThat(closed.getTableId()).isEqualTo(f.table.getId());
        }
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where restaurant_id=? and status='OPEN'", Long.class, f.restaurant.getId())).isZero();
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    void completeAndNewTableOrderNeverAttachNewOrderToClosedOldSession() throws Exception {
        var order = prepared(true, OrderItemStatus.SERVED);
        var result = race(() -> complete(order), () -> create(true, 1, f.table.getId()));
        result.get(0).expect(200);
        assertThat(result.get(1).status).isIn(201, 409);
        var closed = sessions.findById(f.session.getId()).orElseThrow();
        assertThat(closed.getStatus()).isEqualTo(TableSessionStatus.CLOSED);
        if (result.get(1).status == 201) {
            UUID nextSessionId = UUID.fromString(result.get(1).body.path("tableSessionId").asText());
            assertThat(nextSessionId).isNotEqualTo(closed.getId());
            assertThat(sessions.findById(nextSessionId).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        } else result.get(1).error(409, "TABLE_SESSION_HAS_SERVING_ORDER");
        assertThat(events("ORDER_COMPLETED")).isEqualTo(1);
        assertThat(events("TABLE_SESSION_CLOSED")).isEqualTo(1);
    }

    @Test
    void foreignTenantCompletionIsHiddenAndJwtAccountGuardsRemainInForce() throws Exception {
        var own = f;
        f = fixture();
        var foreign = prepared(true, OrderItemStatus.SERVED);
        f = own;
        complete(foreign).error(404, "ORDER_NOT_FOUND");
        var order = prepared(true, OrderItemStatus.SERVED);
        jdbc.update("update users set is_active=false where id=?", f.user.getId());
        bearer(post(path(order) + "/complete"), version(order.version()), actor(ALL)).error(401, "ACCOUNT_INACTIVE");
        jdbc.update("update users set is_active=true where id=?", f.user.getId());
        jdbc.update("update restaurants set status='SUSPENDED' where id=?", f.restaurant.getId());
        bearer(post(path(order) + "/complete"), version(order.version()), actor(ALL)).error(403, "RESTAURANT_INACTIVE");
        assertThat(events("ORDER_COMPLETED")).isZero();
        assertThat(events("TABLE_SESSION_CLOSED")).isZero();
    }

    @Test
    void tableClosurePortRequiresTheOuterTransactionForInspectionAndMutation() {
        assertThatThrownBy(() -> closure.isClosed(f.restaurant.getId(), f.session.getId()))
            .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> closure.close(f.restaurant.getId(), f.session.getId(), NOW, "127.0.0.1"))
            .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Result create(boolean dineIn, int count, UUID tableId) throws Exception {
        var body = new LinkedHashMap<String, Object>();
        body.put("serviceType", dineIn ? "DINE_IN" : "TAKEAWAY");
        body.put("sourceChannel", dineIn ? "WAITER" : "CASHIER");
        body.put("submissionMode", "SUBMIT");
        if (dineIn) body.put(tableId == null ? "tableSessionId" : "tableId", tableId == null ? f.session.getId() : tableId);
        body.put("customerName", "Guest"); body.put("customerPhone", "0900000000"); body.put("note", "Order note");
        var items = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < count; i++) items.add(Map.of("itemId", f.item.getId(), "quantity", BigDecimal.ONE, "note", "line " + i));
        body.put("items", items);
        var result = call(post(ORDERS).header("Idempotency-Key", "create-" + UUID.randomUUID()), body);
        if (tableId == null) result.expect(201);
        return result;
    }

    private Result prepared(boolean dineIn, OrderItemStatus... statuses) throws Exception {
        var current = create(dineIn, statuses.length, null);
        for (int i = 0; i < statuses.length; i++) {
            if (statuses[i] == OrderItemStatus.CANCELLED) {
                current = call(post(path(current) + "/items/" + current.lineId(i) + "/cancel"),
                    Map.of("expectedVersion", current.version(), "reason", "Cancelled fixture"));
                current.expect(200);
                continue;
            }
            if (statuses[i] != OrderItemStatus.PENDING) current = transition(current, i, "start-cooking");
            if (statuses[i] == OrderItemStatus.READY || statuses[i] == OrderItemStatus.SERVED) current = transition(current, i, "mark-ready");
            if (statuses[i] == OrderItemStatus.SERVED) current = transition(current, i, "mark-served");
        }
        call(post(path(current) + "/payments").header("Idempotency-Key", "pay-" + UUID.randomUUID()),
            Map.of("expectedOrderVersion", current.version(), "paymentMethod", "CASH", "amount", current.body.path("totalAmount").decimalValue())).expect(201);
        return detail(current);
    }

    private Result transition(Result order, int index, String action) throws Exception {
        var result = call(post(path(order) + "/items/" + order.lineId(index) + "/" + action),
            Map.of("expectedOrderVersion", order.version()));
        result.expect(200); return result;
    }
    private Result legacyCompleted(Result order) throws Exception {
        jdbc.update("update orders set status='COMPLETED',completed_at=? where id=?", Timestamp.from(NOW.minusSeconds(1)), order.id());
        return detail(order);
    }
    private void setLineStatus(Result order, String status) { jdbc.update("update order_items set status=? where order_id=?", status, order.id()); }
    private UUID historicalOrder(Result order, String status, String paymentStatus, BigDecimal paid) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into orders(id,restaurant_id,order_code,table_session_id,service_type,source_channel,status,
                payment_status,currency_code,total_amount,subtotal_amount,paid_amount,created_by,
                confirmed_at,completed_at,cancelled_at,cancel_reason,created_at,updated_at,idempotency_key,request_hash)
            select ?,restaurant_id,?,table_session_id,service_type,source_channel,?,?,currency_code,
                total_amount,subtotal_amount,?,created_by,confirmed_at,created_at,created_at,'historical fixture',
                created_at,updated_at,?,request_hash from orders where id=?
            """, id, "HISTORY-" + id.toString().substring(0, 8), status, paymentStatus, paid, "history-" + id, order.id());
        return id;
    }
    private Result complete(Result order) throws Exception { return complete(order, order.version()); }
    private Result complete(Result order, long expectedVersion) throws Exception { return call(post(path(order) + "/complete"), version(expectedVersion)); }
    private Result close(long expectedVersion) throws Exception { return call(post("/api/v1/table-sessions/" + f.session.getId() + "/close"), version(expectedVersion)); }
    private Result transfer(RestaurantTable target, long expectedVersion) throws Exception {
        return call(post("/api/v1/table-sessions/" + f.session.getId() + "/transfer").header("Idempotency-Key", "transfer-" + UUID.randomUUID()),
            Map.of("expectedVersion", expectedVersion, "targetTableId", target.getId()));
    }
    private Map<String, Long> version(long value) { return Map.of("expectedVersion", value); }
    private String path(Result order) { return ORDERS + "/" + order.id(); }
    private Result detail(Result order) throws Exception { return call(get(path(order)), null); }
    private CurrentUser actor(Set<String> codes) { return new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), "OWNER", codes); }
    private CurrentUser assignRole(String code) {
        jdbc.update("update users set role_id=(select id from roles where code=? and restaurant_id is null) where id=?", code, f.user.getId());
        return new CurrentUser(f.user.getId(), f.restaurant.getId(), f.user.getEmail(), code, permissions.getEffectivePermissionCodes(f.user.getId()));
    }
    private Result call(MockHttpServletRequestBuilder request, Object body) throws Exception { return callAs(request, body, actor(ALL)); }
    private Result callAs(MockHttpServletRequestBuilder request, Object body, CurrentUser actor) throws Exception {
        request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null, actor.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
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
    private Map<String, Object> snapshot(List<String> features) {
        return new SubscriptionFeatureSnapshot(1, "PRO", features.stream()
            .map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(), NOW).toMap();
    }
    private void setFeatures(List<String> features) {
        var subscription = subscriptions.findById(f.subscription.getId()).orElseThrow();
        subscription.setFeatureSnapshot(snapshot(features)); subscriptions.saveAndFlush(subscription);
    }
    private Fixture fixture() {
        var restaurant = new Restaurant(); restaurant.setCode("CMP" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Completion fixture"); restaurant = restaurants.saveAndFlush(restaurant);
        var user = new User(); user.setRestaurantId(restaurant.getId());
        user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Owner"); user.setEmail(UUID.randomUUID() + "@example.test"); user.setPasswordHash("fixture-only"); user = users.saveAndFlush(user);
        var subscription = new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId()); subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(NOW.minusSeconds(60)); subscription.setEndAt(NOW.plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND"); subscription.setFeatureSnapshot(snapshot(FEATURES));
        subscription = subscriptions.saveAndFlush(subscription);
        var group = new ItemGroup(); group.initialize(restaurant.getId(), NOW); group.setName("Main"); group = groups.saveAndFlush(group);
        var item = new Item(); item.initialize(restaurant.getId(), NOW); item.setGroupId(group.getId()); item.setItemType(ItemType.MENU_ITEM);
        item.setName("Pho"); item.setBaseUnit("bowl"); item.setSalePrice(new BigDecimal("50000.00")); item.setCostPrice(new BigDecimal("12000.00"));
        item = menuItems.saveAndFlush(item);
        var table = new RestaurantTable(); table.initialize(restaurant.getId(), NOW); table.setCode("B01"); table.setName("Table 1");
        table.setQrToken("t" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(10)); table = tables.saveAndFlush(table);
        var session = new TableSession(); session.initialize(restaurant.getId(), NOW.minusSeconds(30)); session.setTableId(table.getId());
        session.setSessionCode("SESSION"); session.setOpenedBy(user.getId()); session.setOpenedAt(NOW.minusSeconds(30)); session = sessions.saveAndFlush(session);
        return new Fixture(restaurant, user, subscription, item, table, session);
    }
    private RestaurantTable freeTable() {
        var table = new RestaurantTable(); table.initialize(f.restaurant.getId(), NOW); table.setCode("FREE-" + UUID.randomUUID().toString().substring(0, 8));
        table.setName("Free table"); table.setQrToken("t" + UUID.randomUUID().toString().replace("-", "") + "a".repeat(10)); return tables.saveAndFlush(table);
    }
    private long mutation(Result order) { return jdbc.queryForObject("select mutation_sequence from orders where id=?", Long.class, order.id()); }
    private long historyCount(Result order) { return jdbc.queryForObject("select count(*) from order_item_status_history h join order_items i on i.id=h.order_item_id where i.order_id=?", Long.class, order.id()); }
    private long events(String action) { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?", Long.class, f.restaurant.getId(), action); }
    private State state(Result order) throws Exception {
        return new State(detail(order).body, jdbc.queryForMap("select * from table_sessions where id=?", f.session.getId()),
            mutation(order), historyCount(order), events("ORDER_COMPLETED"), events("TABLE_SESSION_CLOSED"));
    }
    private void assertUnchanged(Result order, State before) throws Exception { assertThat(state(order)).isEqualTo(before); }
    private void assertAudit(String action, UUID entity, String from, String to) throws Exception {
        var row = jdbc.queryForMap("select before_data::text as before_state,after_data::text as after_state,actor_user_id from audit_logs where action_code=? and entity_id=?", action, entity);
        assertThat(json.readTree((String) row.get("before_state")).path("status").asText()).isEqualTo(from);
        assertThat(json.readTree((String) row.get("after_state")).path("status").asText()).isEqualTo(to);
        assertThat(row.get("actor_user_id")).isEqualTo(f.user.getId());
    }
    private List<Result> race(Callable<Result> a, Callable<Result> b) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return a.call(); });
            var second = pool.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return b.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            return List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
