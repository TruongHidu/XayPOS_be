package com.possaas.modules.table;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.service.TableQrTokenGenerator;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
        "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class TableIntegrationTest {
    private static final String SCHEMA = "it_table_" + UUID.randomUUID().toString().replace("-", "");
    private static final Set<String> ALL = Set.of("TABLE_VIEW", "TABLE_CREATE", "TABLE_UPDATE", "TABLE_OPEN", "TABLE_CLOSE");
    private static final String TABLES = "/api/v1/tables";
    private static final String AREAS = "/api/v1/table-areas";
    private static final String SESSIONS = "/api/v1/table-sessions";

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
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
    @Autowired PlatformTransactionManager transactions;
    @Autowired Clock clock;
    @Autowired EntityManagerFactory emf;
    @Autowired JwtTokenService tokens;
    @Autowired PermissionService permissions;
    @Autowired org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping handlerMappings;
    @MockitoSpyBean AuditService audit;
    @MockitoSpyBean TableQrTokenGenerator qrTokens;
    private final List<UUID> tenants = new ArrayList<>();
    private User owner;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        owner = fixture();
    }

    @AfterEach
    void cleanup() {
        reset(audit, qrTokens);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
            try {
                for (UUID tenant : tenants) {
                    jdbc.update("delete from table_sessions where restaurant_id=?", tenant);
                    jdbc.update("delete from restaurant_tables where restaurant_id=?", tenant);
                    jdbc.update("delete from table_areas where restaurant_id=?", tenant);
                    jdbc.update("delete from audit_logs where restaurant_id=?", tenant);
                    jdbc.update("delete from restaurant_subscriptions where restaurant_id=?", tenant);
                    jdbc.update("delete from user_permissions where user_id in (select id from users where restaurant_id=?)", tenant);
                    jdbc.update("delete from users where restaurant_id=?", tenant);
                    jdbc.update("delete from restaurants where id=?", tenant);
                }
            } finally {
                jdbc.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
            }
        });
        tenants.clear();
    }

    @AfterAll
    void dropOwnSchema() {
        if (!SCHEMA.matches("it_table_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void areaCrudNoOpVersionUniquenessAndSoftDelete() throws Exception {
        var a = area(" Ground ");
        assertThat(a.body.path("name").asText()).isEqualTo("Ground");
        call(post(AREAS), Map.of("name", "ground")).error(409, "TABLE_AREA_NAME_EXISTS");
        var unchanged = call(patch(ap(a)), Map.of("name", "Ground", "expectedVersion", 0));
        unchanged.expect(200);
        assertThat(unchanged.body).isEqualTo(a.body);
        assertThat(events("TABLE_AREA_UPDATED")).isZero();
        var updated = call(patch(ap(a)), Map.of("description", "hello", "displayOrder", 2, "expectedVersion", 0));
        updated.expect(200);
        call(patch(ap(a)), Map.of("name", "Stale", "expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        call(patch(ap(a) + "/status"), Map.of("active", true, "expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        call(patch(ap(a)), Map.of("description", "", "expectedVersion", 1)).expect(200);
        call(delete(ap(a)).param("expectedVersion", "2")).expect(204);
        call(delete(ap(a)).param("expectedVersion", "0")).expect(204);
        call(get(ap(a))).error(404, "TABLE_AREA_NOT_FOUND");
        assertThat(events("TABLE_AREA_DELETED")).isEqualTo(1);
        area("ground");
    }

    @Test
    void tableDefaultsCodeReservationAndVersionChecks() throws Exception {
        var t = table(null, " b01 ");
        assertThat(t.body.path("code").asText()).isEqualTo("B01");
        assertThat(t.body.path("capacity").asInt()).isEqualTo(4);
        assertThat(t.body.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(t.body.path("canOpen").asBoolean()).isTrue();
        assertThat(t.body.path("occupied").asBoolean()).isFalse();
        call(post(TABLES), Map.of("code", "B01", "name", "Duplicate")).error(409, "TABLE_CODE_EXISTS");
        var noOp = call(patch(tp(t)), Map.of("code", " b01 ", "expectedVersion", 0));
        assertThat(noOp.body).isEqualTo(t.body);
        assertThat(events("TABLE_UPDATED")).isZero();
        call(patch(tp(t)), Map.of("name", "Renamed", "capacity", 8, "displayOrder", 2, "expectedVersion", 0)).expect(200);
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        call(patch(tp(t)), Map.of("name", "Renamed", "expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        call(delete(tp(t)).param("expectedVersion", "1")).expect(204);
        call(delete(tp(t)).param("expectedVersion", "0")).expect(204);
        call(post(TABLES), Map.of("code", "b01", "name", "Reserved")).error(409, "TABLE_CODE_EXISTS");
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 1)).error(404, "TABLE_NOT_FOUND");
        call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 1)).error(404, "TABLE_NOT_FOUND");
        assertThat(events("TABLE_DELETED")).isEqualTo(1);
    }

    @Test
    void areaDisablePreservesTableConfigurationAndAssignmentRequiresPresence() throws Exception {
        var a = area("First");
        var t = table(a.id(), "A");
        call(delete(ap(a)).param("expectedVersion", "0")).error(409, "TABLE_AREA_NOT_EMPTY");
        call(patch(tp(t) + "/status"), Map.of("status", "INACTIVE", "expectedVersion", 0)).expect(200);
        call(delete(ap(a)).param("expectedVersion", "0")).error(409, "TABLE_AREA_NOT_EMPTY");
        call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 0)).expect(200);
        call(post(TABLES), Map.of("areaId", a.id(), "name", "No", "code", "NO")).error(409, "TABLE_AREA_INACTIVE");
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 1)).error(409, "TABLE_INACTIVE");
        call(patch(ap(a) + "/status"), Map.of("active", true, "expectedVersion", 1)).expect(200);
        assertThat(call(get(tp(t))).body.path("status").asText()).isEqualTo("INACTIVE");
        call(patch(tp(t) + "/status"), Map.of("status", "AVAILABLE", "expectedVersion", 1)).expect(200);
        call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 2)).expect(200);
        assertThat(call(get(tp(t))).body.path("canOpen").asBoolean()).isFalse();
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 2)).error(409, "TABLE_AREA_INACTIVE");
        call(patch(tp(t) + "/area"), Map.of("expectedVersion", 2)).error(400, "INVALID_REQUEST_BODY");
        Map<String, Object> assignment = new LinkedHashMap<>();
        assignment.put("areaId", null); assignment.put("expectedVersion", 2);
        var unassigned = call(patch(tp(t) + "/area"), assignment);
        unassigned.expect(200);
        assertThat(unassigned.body.path("area").isNull()).isTrue();
        assertThat(unassigned.body.path("canOpen").asBoolean()).isTrue();
        call(delete(ap(a)).param("expectedVersion", "3")).expect(204);
    }

    @Test
    void openSessionOccupancyMetadataAndOccupiedTableRestrictions() throws Exception {
        var a = area("Busy");
        var t = table(a.id(), "BUSY");
        call(get(tp(t) + "/current-session")).expect(204);
        var s = call(post(tp(t) + "/sessions"), Map.of("guestCount", 10, "note", " waiting ", "expectedVersion", 0));
        s.expect(201); // guestCount may exceed reference capacity.
        assertThat(s.body.path("openedBy").asText()).isEqualTo(owner.getId().toString());
        assertThat(s.body.path("status").asText()).isEqualTo("OPEN");
        var detail = call(get(tp(t)));
        assertThat(detail.body.path("occupied").asBoolean()).isTrue();
        assertThat(detail.body.path("canOpen").asBoolean()).isFalse();
        assertThat(detail.body.path("status").asText()).isEqualTo("AVAILABLE");
        assertThat(detail.body.path("version").asLong()).isZero();
        call(get(tp(t) + "/current-session")).expect(200);
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)).error(409, "TABLE_ALREADY_OCCUPIED");
        call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 0)).error(409, "TABLE_AREA_HAS_OPEN_SESSIONS");
        call(patch(tp(t) + "/status"), Map.of("status", "INACTIVE", "expectedVersion", 0)).error(409, "TABLE_HAS_OPEN_SESSION");
        call(delete(tp(t)).param("expectedVersion", "0")).error(409, "TABLE_HAS_OPEN_SESSION");
        call(patch(tp(t)), Map.of("code", "OTHER", "expectedVersion", 0)).error(409, "TABLE_HAS_OPEN_SESSION");
        call(patch(tp(t)), Map.of("capacity", 3, "expectedVersion", 0)).error(409, "TABLE_HAS_OPEN_SESSION");
        var otherArea = area("Other");
        call(patch(tp(t) + "/area"), Map.of("areaId", otherArea.id(), "expectedVersion", 0)).error(409, "TABLE_HAS_OPEN_SESSION");
        call(patch(tp(t)), Map.of("name", "Allowed", "displayOrder", 1, "expectedVersion", 0)).expect(200);
        var changed = call(patch(sp(s)), Map.of("guestCount", 2, "note", "", "expectedVersion", 0));
        changed.expect(200);
        assertThat(changed.body.path("note").isNull()).isTrue();
        call(patch(sp(s)), Map.of("guestCount", 3, "expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        var noOp = call(patch(sp(s)), Map.of("guestCount", 2, "expectedVersion", 1));
        assertThat(noOp.body).isEqualTo(changed.body);
        assertThat(events("TABLE_SESSION_UPDATED")).isEqualTo(1);
    }

    @Test
    void cancelIsIdempotentAndRetryCannotCancelReplacementSession() throws Exception {
        var a = area("History");
        var t = table(a.id(), "H");
        var s = open(t);
        var ended = call(post(sp(s) + "/cancel"), Map.of("reason", " Mistake ", "expectedVersion", 0));
        ended.expect(200);
        assertThat(ended.body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(ended.body.path("cancelReason").asText()).isEqualTo("Mistake");
        assertThat(ended.body.path("closedBy").asText()).isEqualTo(owner.getId().toString());
        assertThat(ended.body.path("closedAt").isNull()).isFalse();
        call(get(tp(t) + "/current-session")).expect(204);
        var next = open(t);
        var retry = call(post(sp(s) + "/cancel"), Map.of("reason", "Different", "expectedVersion", 0));
        assertThat(retry.body).isEqualTo(ended.body);
        assertThat(call(get(tp(t) + "/current-session")).id()).isEqualTo(next.id());
        assertThat(events("TABLE_SESSION_CANCELLED")).isEqualTo(1);
        call(patch(sp(s)), Map.of("note", "No", "expectedVersion", 1)).error(409, "INVALID_TABLE_SESSION_TRANSITION");
        cancel(next);
        call(delete(tp(t)).param("expectedVersion", "0")).expect(204);
        call(delete(ap(a)).param("expectedVersion", "0")).expect(204);
        call(get(sp(s))).expect(200);
        assertThat(call(get(SESSIONS).param("tableId", t.id().toString())).body.path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void closedSessionIsReservedAndCannotBeMutatedOrClosedThroughApi() throws Exception {
        var t = table(null, "CLOSED");
        var s = open(t);
        jdbc.update("update table_sessions set status='CLOSED',closed_at=opened_at,closed_by=opened_by where id=?", s.id());
        call(post(sp(s) + "/cancel"), Map.of("reason", "No", "expectedVersion", 0)).error(409, "INVALID_TABLE_SESSION_TRANSITION");
        call(patch(sp(s)), Map.of("note", "No", "expectedVersion", 0)).error(409, "INVALID_TABLE_SESSION_TRANSITION");
        assertThat(handlerMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream()))
                .doesNotContain("/api/v1/table-sessions/{sessionId}/close");
        assertThat(events("TABLE_SESSION_CLOSED")).isZero();
    }

    @Test
    void qrIsRestrictedRotatableAndNeverPresentInOrdinaryResponseOrAudit() throws Exception {
        var t = table(null, "QR");
        var q = call(get(tp(t) + "/qr"));
        q.expect(200);
        String first = q.body.path("qrToken").asText();
        assertThat(first).matches("[A-Za-z0-9_-]{43}");
        call(get(tp(t) + "/qr"), owner, Set.of("TABLE_VIEW")).error(403, "FORBIDDEN");
        call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 0), owner, Set.of("TABLE_VIEW")).error(403, "FORBIDDEN");
        open(t);
        var rotated = call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 0));
        rotated.expect(200);
        String second = rotated.body.path("qrToken").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(rotated.body.path("version").asLong()).isEqualTo(1);
        call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 0)).error(409, "CONCURRENT_TABLE_UPDATE");
        assertThat(jdbc.queryForObject("select count(*) from restaurant_tables where qr_token=?", Long.class, first)).isZero();
        for (String body : List.of(t.body.toString(), call(get(tp(t))).body.toString(), call(get(TABLES)).body.toString()))
            assertThat(body).doesNotContain("qrToken", "qrPath", first, second);
        String audits = jdbc.queryForList("select before_data::text,after_data::text from audit_logs where restaurant_id=?",
                owner.getRestaurantId()).toString();
        assertThat(audits).doesNotContain("qrToken", "qrPath", "qrTokenHash", first, second);
        assertThat(events("TABLE_QR_ROTATED")).isEqualTo(1);
    }

    @Test
    void qrCollisionRollsBackAndHasSpecificError(CapturedOutput output) throws Exception {
        var t = table(null, "TOKEN1");
        String token = call(get(tp(t) + "/qr")).body.path("qrToken").asText();
        doReturn(token).when(qrTokens).generate();
        call(post(TABLES), Map.of("code", "TOKEN2", "name", "Collision")).error(409, "QR_TOKEN_CONFLICT");
        assertThat(events("TABLE_CREATED")).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void validationRejectsUnknownReadonlyEmptyAndOutOfRangeFields() throws Exception {
        for (String field : List.of("restaurantId", "qrToken", "createdAt", "occupancy", "currentSession"))
            call(post(TABLES), Map.of("code", "BAD", "name", "Bad", field, "injected")).error(400, "INVALID_REQUEST_BODY");
        for (int value : List.of(0, -1, 32768))
            call(post(TABLES), Map.of("code", "BAD", "name", "Bad", "capacity", value)).error(400, "VALIDATION_ERROR");
        var a = area("Valid");
        var t = table(null, "VALID");
        call(patch(ap(a)), Map.of("expectedVersion", 0)).error(400, "EMPTY_UPDATE_REQUEST");
        call(patch(tp(t)), Map.of("expectedVersion", 0)).error(400, "EMPTY_UPDATE_REQUEST");
        call(patch(tp(t)), Map.of("name", "New")).error(400, "VALIDATION_ERROR");
        call(patch(tp(t)), Map.of("name", " ", "expectedVersion", 0)).error(400, "VALIDATION_ERROR");
        call(patch(tp(t) + "/status"), Map.of("status", "OCCUPIED", "expectedVersion", 0)).error(400, "INVALID_REQUEST_BODY");
        call(post(tp(t) + "/sessions"), Map.of("guestCount", 0, "expectedVersion", 0)).error(400, "VALIDATION_ERROR");
        call(post(tp(t) + "/sessions"), Map.of("guestCount", 32768, "expectedVersion", 0)).error(400, "VALIDATION_ERROR");
        call(post(tp(t) + "/sessions"), Map.of("sessionCode", "FORGED", "expectedVersion", 0)).error(400, "INVALID_REQUEST_BODY");
        var s = open(t);
        call(patch(sp(s)), Map.of("expectedVersion", 0)).error(400, "EMPTY_UPDATE_REQUEST");
        call(patch(sp(s)), Map.of("status", "CANCELLED", "expectedVersion", 0)).error(400, "INVALID_REQUEST_BODY");
        call(post(sp(s) + "/cancel"), Map.of("reason", " ", "expectedVersion", 0)).error(400, "VALIDATION_ERROR");
        call(get(TABLES).param("sortBy", "qrToken")).error(400, "VALIDATION_ERROR");
        call(get(TABLES).param("size", "101")).error(400, "VALIDATION_ERROR");
        call(get(TABLES).param("direction", "sideways")).error(400, "VALIDATION_ERROR");
        call(get(SESSIONS).param("openedFrom", "2026-02-01T00:00:00Z").param("openedTo", "2026-01-01T00:00:00Z"))
                .error(400, "VALIDATION_ERROR");
    }

    @Test
    void allLookupsAndMutationsHideForeignTenantResources() throws Exception {
        var a = area("Private");
        var t = table(a.id(), "PRIVATE");
        var s = open(t);
        var other = fixture();
        for (String path : List.of(ap(a), tp(t), tp(t) + "/qr", tp(t) + "/current-session", sp(s)))
            call(get(path), other, ALL).expect(404);
        call(patch(ap(a)), Map.of("name", "No", "expectedVersion", 0), other, ALL).error(404, "TABLE_AREA_NOT_FOUND");
        call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 0), other, ALL).expect(404);
        call(delete(ap(a)).param("expectedVersion", "0"), other, ALL).expect(404);
        call(patch(tp(t)), Map.of("name", "No", "expectedVersion", 0), other, ALL).expect(404);
        call(patch(tp(t) + "/status"), Map.of("status", "INACTIVE", "expectedVersion", 0), other, ALL).expect(404);
        call(patch(tp(t) + "/area"), Map.of("areaId", a.id(), "expectedVersion", 0), other, ALL).expect(404);
        call(delete(tp(t)).param("expectedVersion", "0"), other, ALL).expect(404);
        call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 0), other, ALL).expect(404);
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0), other, ALL).expect(404);
        call(patch(sp(s)), Map.of("note", "No", "expectedVersion", 0), other, ALL).expect(404);
        call(post(sp(s) + "/cancel"), Map.of("reason", "No", "expectedVersion", 0), other, ALL).expect(404);
        call(post(TABLES), Map.of("areaId", a.id(), "code", "NO", "name", "No"), other, ALL).expect(404);
        call(get(TABLES).param("areaId", a.id().toString()), other, ALL).expect(404);
        call(get(SESSIONS).param("tableId", t.id().toString()), other, ALL).expect(404);
        for (String path : List.of(TABLES, AREAS, SESSIONS))
            assertThat(call(get(path), other, ALL).body.path("totalElements").asInt()).isZero();
    }

    @Test
    void databaseEnforcesTenantForeignKeysOpenUniquenessAndTerminalChecks() throws Exception {
        var a = area("Own");
        var t = table(a.id(), "DB");
        var s = open(t);
        var other = fixture();
        var foreignArea = call(post(AREAS), Map.of("name", "Foreign"), other, ALL);
        var foreignTable = call(post(TABLES), Map.of("code", "FOREIGN", "name", "Foreign"), other, ALL);
        assertThatThrownBy(() -> jdbc.update("update restaurant_tables set area_id=? where id=?", foreignArea.id(), t.id()))
                .hasStackTraceContaining("fk_restaurant_table_area");
        assertThatThrownBy(() -> jdbc.update("update table_sessions set table_id=? where id=?", foreignTable.id(), s.id()))
                .hasStackTraceContaining("fk_table_session_table");
        assertThatThrownBy(() -> jdbc.update("""
                insert into table_sessions(restaurant_id,table_id,session_code,status,opened_by,opened_at,created_at,updated_at)
                select restaurant_id,table_id,'DUPLICATE','OPEN',opened_by,opened_at,created_at,updated_at from table_sessions where id=?
                """, s.id())).hasStackTraceContaining("ux_table_session_open");
        for (String assignment : List.of("status='CLOSED'", "status='CANCELLED'", "guest_count=0",
                "status='UNKNOWN'", "closed_at=opened_at", "status='CANCELLED',closed_at=opened_at,closed_by=opened_by,cancel_reason=' '",
                "status='CLOSED',closed_at=opened_at-interval '1 second',closed_by=opened_by"))
            assertThatThrownBy(() -> jdbc.update("update table_sessions set " + assignment + " where id=?", s.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
        for (String assignment : List.of("capacity=0", "display_order=-1", "status='OCCUPIED'"))
            assertThatThrownBy(() -> jdbc.update("update restaurant_tables set " + assignment + " where id=?", t.id()))
                    .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void missingJwtPermissionFeatureAndTenantAreRejected() throws Exception {
        assertThat(mvc.perform(get(TABLES)).andReturn().getResponse().getStatus()).isEqualTo(401);
        call(get(TABLES), owner, Set.of()).error(403, "FORBIDDEN");
        call(post(AREAS), Map.of("name", "Denied"), owner, Set.of("TABLE_VIEW")).error(403, "FORBIDDEN");
        var system = new CurrentUser(UUID.randomUUID(), null, "system@example.com", "SUPER_ADMIN", ALL);
        var auth = new UsernamePasswordAuthenticationToken(system, null, ALL.stream().map(SimpleGrantedAuthority::new).toList());
        var response = mvc.perform(get(TABLES).with(authentication(auth))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("TENANT_ACCESS_DENIED");
        jdbc.update("update restaurant_subscriptions set feature_snapshot=jsonb_set(feature_snapshot,'{features}','[]'::jsonb) where restaurant_id=?", owner.getRestaurantId());
        call(get(TABLES)).error(403, "FEATURE_NOT_ENTITLED");
        jdbc.update("update restaurant_subscriptions set status='CANCELLED' where restaurant_id=?", owner.getRestaurantId());
        call(get(TABLES)).error(403, "SUBSCRIPTION_NOT_ACTIVE");
    }

    @Test
    void realBearerUsesGrantedPermissionsAndAccountRestaurantGuards() throws Exception {
        jdbc.update("update users set role_id=(select id from roles where code='CASHIER' and restaurant_id is null) where id=?", owner.getId());
        var defaults = permissions.getEffectivePermissionCodes(owner.getId());
        assertThat(defaults).doesNotContain("TABLE_CREATE");
        bearer(post(AREAS).content("{\"name\":\"Denied\"}"), defaults).error(403, "FORBIDDEN");
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'GRANT',? from permissions where code in ('TABLE_CREATE','TABLE_VIEW')",
                owner.getId(), owner.getId());
        var granted = permissions.getEffectivePermissionCodes(owner.getId());
        bearer(post(AREAS).content("{\"name\":\"Granted\"}"), granted).expect(201);
        jdbc.update("update restaurants set status='SUSPENDED' where id=?", owner.getRestaurantId());
        bearer(get(TABLES), granted).error(403, "RESTAURANT_INACTIVE");
        jdbc.update("update restaurants set status='ACTIVE' where id=?", owner.getRestaurantId());
        jdbc.update("update users set is_active=false where id=?", owner.getId());
        bearer(get(TABLES), granted).error(401, "ACCOUNT_INACTIVE");
    }

    @Test
    void occupiedFilterRunsBeforePaginationAndSearchEscapesWildcards() throws Exception {
        var a = area("Filter");
        var t = table(a.id(), "MATCH%_");
        table(a.id(), "MATCHXX");
        var s = open(t);
        assertThat(call(get(TABLES).param("q", "%_")).body.path("totalElements").asInt()).isEqualTo(1);
        var occupied = call(get(TABLES).param("occupied", "true").param("size", "1"));
        assertThat(occupied.body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(occupied.body.path("content").get(0).path("id").asText()).isEqualTo(t.id().toString());
        assertThat(call(get(TABLES).param("occupied", "false").param("size", "1")).body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get(SESSIONS).param("tableId", t.id().toString()).param("status", "OPEN")).body.path("totalElements").asInt()).isEqualTo(1);
        cancel(s);
        assertThat(call(get(SESSIONS).param("status", "CANCELLED")).body.path("totalElements").asInt()).isEqualTo(1);
    }

    @Test
    void batchQueriesStayConstantAsPageGrows() throws Exception {
        var a = area("Batch");
        open(table(a.id(), "FIRST"));
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear(); call(get(TABLES).param("size", "100")).expect(200);
            long tableQueries = stats.getPrepareStatementCount();
            stats.clear(); call(get(SESSIONS).param("size", "100")).expect(200);
            long sessionQueries = stats.getPrepareStatementCount();
            for (int n = 0; n < 8; n++) open(table(a.id(), "BATCH" + n));
            stats.clear(); call(get(TABLES).param("size", "100")).expect(200);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(tableQueries);
            stats.clear(); call(get(SESSIONS).param("size", "100")).expect(200);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(sessionQueries);
        } finally { stats.setStatisticsEnabled(enabled); }
    }

    @Test
    void twoConcurrentOpenRequestsHaveExactlyOneWinner() throws Exception {
        var t = table(null, "RACE");
        var results = race(() -> call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)),
                () -> call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)));
        assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1);
    }

    @Test
    void areaDisableAndOpenAreSerialized() throws Exception {
        var a = area("Race");
        var t = table(a.id(), "RACEAREA");
        var results = race(() -> call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)),
                () -> call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 0)));
        assertThat(results.stream().filter(r -> r.status == 200 || r.status == 201).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                select count(*) from table_sessions s join restaurant_tables t on s.table_id=t.id
                join table_areas a on a.id=t.area_id where s.restaurant_id=? and s.status='OPEN' and not a.is_active
                """, Long.class, owner.getRestaurantId())).isZero();
    }

    @Test
    void tableDisableAndDeleteCannotRacePastOpen() throws Exception {
        for (boolean deletion : List.of(false, true)) {
            var t = table(null, "RACE" + deletion);
            var results = race(() -> call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)),
                    () -> deletion ? call(delete(tp(t)).param("expectedVersion", "0"))
                            : call(patch(tp(t) + "/status"), Map.of("status", "INACTIVE", "expectedVersion", 0)));
            assertThat(results.stream().filter(r -> r.status >= 200 && r.status < 300).count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from table_sessions s join restaurant_tables t on s.table_id=t.id
                where s.restaurant_id=? and s.status='OPEN' and (t.status='INACTIVE' or t.deleted_at is not null)
                """, Long.class, owner.getRestaurantId())).isZero();
    }

    @Test
    void everyMutationRequiresItsOwnPermission() throws Exception {
        var a = area("Permissions");
        var t = table(a.id(), "PERMISSIONS");
        var s = open(t);
        Set<String> viewOnly = Set.of("TABLE_VIEW");
        call(post(TABLES), Map.of("code", "DENIED", "name", "Denied"), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(ap(a)), Map.of("name", "Denied", "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(ap(a) + "/status"), Map.of("active", false, "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(delete(ap(a)).param("expectedVersion", "0"), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(tp(t)), Map.of("name", "Denied", "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(tp(t) + "/status"), Map.of("status", "INACTIVE", "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(tp(t) + "/area"), Map.of("areaId", a.id(), "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(delete(tp(t)).param("expectedVersion", "0"), owner, viewOnly).error(403, "FORBIDDEN");
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(patch(sp(s)), Map.of("guestCount", 2, "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        call(post(sp(s) + "/cancel"), Map.of("reason", "Denied", "expectedVersion", 0), owner, viewOnly).error(403, "FORBIDDEN");
        assertThat(events("TABLE_SESSION_CANCELLED")).isZero();
    }

    @Test
    void expiredSnapshotCannotOpenOrRotateEvenWithPermissions() throws Exception {
        var t = table(null, "EXPIRED");
        jdbc.update("update restaurant_subscriptions set end_at=? where restaurant_id=?",
                java.sql.Timestamp.from(clock.instant().minusSeconds(1)), owner.getRestaurantId());
        call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)).error(403, "SUBSCRIPTION_NOT_ACTIVE");
        call(post(tp(t) + "/qr/rotate"), Map.of("expectedVersion", 0)).error(403, "SUBSCRIPTION_NOT_ACTIVE");
        assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test
    void cancellationAndReplacementOpenPreserveSingleOpenSession() throws Exception {
        var t = table(null, "REOPEN");
        var s = open(t);
        var results = race(() -> call(post(sp(s) + "/cancel"), Map.of("reason", "Mistake", "expectedVersion", 0)),
                () -> call(post(tp(t) + "/sessions"), Map.of("expectedVersion", 0)));
        results.getFirst().expect(200);
        assertThat(results.get(1).status).isIn(201, 409);
        if (results.get(1).status == 409) open(t);
        call(post(sp(s) + "/cancel"), Map.of("reason", "Retry", "expectedVersion", 0)).expect(200);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=? and status='OPEN'", Long.class, t.id())).isEqualTo(1);
        assertThat(events("TABLE_SESSION_CANCELLED")).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackCreationAndCancellation() throws Exception {
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).record(eq(owner.getRestaurantId()), any(),
                eq("TABLE_CREATED"), any(), any(), any(), any(), any());
        call(post(TABLES), Map.of("code", "ROLLBACK", "name", "Rollback")).expect(500);
        assertThat(jdbc.queryForObject("select count(*) from restaurant_tables where restaurant_id=?", Long.class, owner.getRestaurantId())).isZero();
        reset(audit);
        var t = table(null, "KEEP");
        var s = open(t);
        doThrow(new IllegalStateException("Audit unavailable")).when(audit).record(eq(owner.getRestaurantId()), any(),
                eq("TABLE_SESSION_CANCELLED"), any(), any(), any(), any(), any());
        call(post(sp(s) + "/cancel"), Map.of("reason", "Failed", "expectedVersion", 0)).expect(500);
        assertThat(call(get(sp(s))).body.path("status").asText()).isEqualTo("OPEN");
        assertThat(events("TABLE_SESSION_CANCELLED")).isZero();
    }

    private Result area(String name) throws Exception {
        var result = call(post(AREAS), Map.of("name", name)); result.expect(201); return result;
    }

    private Result table(UUID area, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("areaId", area); body.put("code", code); body.put("name", code);
        var result = call(post(TABLES), body); result.expect(201); return result;
    }

    private Result open(Result table) throws Exception {
        var result = call(post(tp(table) + "/sessions"), Map.of("expectedVersion", table.body.path("version").asLong()));
        result.expect(201); return result;
    }

    private void cancel(Result session) throws Exception {
        call(post(sp(session) + "/cancel"), Map.of("reason", "Seating mistake", "expectedVersion", session.body.path("version").asLong())).expect(200);
    }

    private String tp(Result r) { return TABLES + "/" + r.id(); }
    private String ap(Result r) { return AREAS + "/" + r.id(); }
    private String sp(Result r) { return SESSIONS + "/" + r.id(); }

    private Result call(MockHttpServletRequestBuilder request) throws Exception { return call(request, owner, ALL); }
    private Result call(MockHttpServletRequestBuilder request, Object body) throws Exception { return call(request, body, owner, ALL); }
    private Result call(MockHttpServletRequestBuilder request, Object body, User user, Set<String> codes) throws Exception {
        return call(request.content(json.writeValueAsString(body)), user, codes);
    }
    private Result call(MockHttpServletRequestBuilder request, User user, Set<String> codes) throws Exception {
        String role = jdbc.queryForObject("select r.code from roles r join users u on u.role_id=r.id where u.id=?", String.class, user.getId());
        var principal = new CurrentUser(user.getId(), user.getRestaurantId(), user.getEmail(), role, codes);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, codes.stream().map(SimpleGrantedAuthority::new).toList());
        var response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth))).andReturn().getResponse();
        return new Result(response.getStatus(), response.getContentAsString().isBlank() ? json.createObjectNode() : json.readTree(response.getContentAsString()));
    }

    private Result bearer(MockHttpServletRequestBuilder request, Set<String> codes) throws Exception {
        String token = tokens.createAccessToken(new CurrentUser(owner.getId(), owner.getRestaurantId(), owner.getEmail(), "CASHIER", codes));
        var response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + token)).andReturn().getResponse();
        return new Result(response.getStatus(), json.readTree(response.getContentAsString()));
    }

    private long events(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?", Long.class, owner.getRestaurantId(), action);
    }

    private User fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var r = new Restaurant(); r.setCode("TABLE" + UUID.randomUUID().toString().replace("-", "")); r.setName("Table test");
            restaurants.saveAndFlush(r); tenants.add(r.getId());
            var u = new User(); u.setRestaurantId(r.getId());
            u.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
            u.setName("Owner"); u.setEmail(UUID.randomUUID() + "@example.com"); u.setPasswordHash("fixture-only");
            users.saveAndFlush(u);
            var s = new RestaurantSubscription(); s.setRestaurantId(r.getId());
            s.setPackageId(packages.findByCode("PRO").orElseThrow().getId()); s.setStatus(SubscriptionStatus.ACTIVE);
            s.setStartAt(clock.instant().minusSeconds(30)); s.setEndAt(clock.instant().plusSeconds(3600));
            s.setPriceAmount(BigDecimal.ZERO); s.setCurrencyCode("VND");
            s.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO",
                    List.of(new SubscriptionFeatureSnapshot.FeatureGrant("TABLE_MANAGEMENT", Map.of())), clock.instant()).toMap());
            subscriptions.saveAndFlush(s);
            return u;
        });
    }

    private List<Result> race(Callable<Result> first, Callable<Result> second) throws Exception {
        var ready = new CountDownLatch(2); var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return first.call(); });
            var b = pool.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new TimeoutException(); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); go.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private record Result(int status, JsonNode body) {
        UUID id() { return UUID.fromString(body.path("id").asText()); }
        void expect(int expected) { assertThat(status).describedAs(body.toString()).isEqualTo(expected); }
        void error(int expected, String code) { expect(expected); assertThat(body.path("code").asText()).isEqualTo(code); }
    }
}
