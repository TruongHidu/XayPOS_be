package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.menu.service.strategy.MenuItemCreationStrategy;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@AutoConfigureMockMvc
class MenuIntegrationTest {
    @Autowired
    PermissionService permissions;
    @Autowired
    JwtTokenService tokens;
    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    RestaurantRepository restaurants;
    @Autowired
    UserRepository users;
    @Autowired
    RoleRepository roles;
    @Autowired
    RestaurantSubscriptionRepository subscriptions;
    @Autowired
    PackagePlanRepository packages;
    @Autowired
    ItemRepository items;
    @Autowired
    ItemGroupRepository groups;
    @Autowired
    Clock clock;
    @Autowired
    PlatformTransactionManager transactions;
    @Autowired
    EntityManagerFactory emf;
    @MockitoSpyBean
    MenuItemCreationStrategy strategy;
    @MockitoSpyBean
    AuditService audit;
    private final List<UUID> tenants = new ArrayList<>();
    private User owner;
    private static final Set<String> ALL = Set.of("MENU_VIEW", "MENU_CREATE", "MENU_UPDATE", "MENU_DELETE");

    @BeforeEach
    void setup() {
        owner = fixture();
    }

    @AfterEach
    void cleanup() {
        reset(strategy, audit);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            jdbc.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
            try {
                for (UUID id : tenants) {
                    jdbc.update("delete from items where restaurant_id=?", id);
                    jdbc.update("delete from item_groups where restaurant_id=?", id);
                    jdbc.update("delete from audit_logs where restaurant_id=?", id);
                    jdbc.update("delete from restaurant_subscriptions where restaurant_id=?", id);
                    jdbc.update("delete from users where restaurant_id=?", id);
                    jdbc.update("delete from restaurants where id=?", id);
                }
            } finally {
                jdbc.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
            }
        });
    }

    @Test
    void createUsesStrategyAndUpdatePreservesLifecycleAndInternalDefaults() throws Exception {
        var group = createGroup("Drinks");
        var item = createItem(group.id(), " coffee ");
        verify(strategy).create(any());
        assertThat(item.body.path("sku").asText()).isEqualTo("COFFEE");
        assertThat(item.body.toString()).doesNotContain("costPrice", "trackInventory", "metadata", "itemType");
        var changed = call(patch(path(item) + "/availability")
                .content("{\"availabilityStatus\":\"OUT_OF_STOCK\",\"expectedVersion\":0}"));
        changed.expect(200);
        call(patch(path(item)).content("{\"name\":\"New name\",\"expectedVersion\":1}")).expect(200);
        verify(strategy, times(1)).create(any());
        var loaded = items.findById(item.id()).orElseThrow();
        assertThat(loaded.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.OUT_OF_STOCK);
        assertThat(loaded.getCostPrice()).isEqualByComparingTo("0");
        assertThat(loaded.getMetadata()).isEmpty();
    }

    @Test
    void lifecycleRetriesKeepVersionsAndDoNotDuplicateAudit() throws Exception {
        var item = createItem(null, null);
        call(patch(path(item) + "/availability")
                .content("{\"availabilityStatus\":\"OUT_OF_STOCK\",\"expectedVersion\":0}")).expect(200);
        call(patch(path(item) + "/status").content("{\"active\":false,\"expectedVersion\":1}")).expect(200);
        var retry = call(patch(path(item) + "/status").content("{\"active\":false,\"expectedVersion\":0}"));
        retry.expect(200);
        assertThat(retry.body.path("version").asLong()).isEqualTo(2);
        call(patch(path(item) + "/availability")
                .content("{\"availabilityStatus\":\"AVAILABLE\",\"expectedVersion\":2}"))
                .error(409, "INVALID_MENU_ITEM_TRANSITION");
        var enabled = call(patch(path(item) + "/status").content("{\"active\":true,\"expectedVersion\":2}"));
        enabled.expect(200);
        assertThat(enabled.body.path("availabilityStatus").asText()).isEqualTo("OUT_OF_STOCK");
        call(delete(path(item)).param("expectedVersion", "3")).expect(204);
        call(delete(path(item)).param("expectedVersion", "0")).expect(204);
        call(get(path(item))).error(404, "MENU_ITEM_NOT_FOUND");
        call(patch(path(item)).content("{\"name\":\"x\",\"expectedVersion\":4}")).error(404, "MENU_ITEM_NOT_FOUND");
        assertThat(events("MENU_ITEM_DISABLED")).isEqualTo(1);
        assertThat(events("MENU_ITEM_DELETED")).isEqualTo(1);
    }

    @Test
    void inactiveGroupHidesItemsWithoutMutatingThemAndCannotBeDeletedWhileOccupied() throws Exception {
        var g = createGroup("Drinks");
        var i = createItem(g.id(), null);
        call(patch(groupPath(g) + "/status").content("{\"active\":false,\"expectedVersion\":0}")).expect(200);
        var detail = call(get(path(i)));
        detail.expect(200);
        assertThat(detail.body.path("sellable").asBoolean()).isFalse();
        assertThat(detail.body.path("active").asBoolean()).isTrue();
        assertThat(detail.body.path("version").asLong()).isZero();
        call(delete(groupPath(g)).param("expectedVersion", "1")).error(409, "MENU_GROUP_NOT_EMPTY");
        call(post("/api/v1/menu/items").content(itemBody(g.id(), "other"))).error(409, "MENU_GROUP_INACTIVE");
        call(put(path(i) + "/group").content("{\"expectedVersion\":0}")).error(400, "INVALID_REQUEST_BODY");
        var ungrouped = call(put(path(i) + "/group").content("{\"groupId\":null,\"expectedVersion\":0}"));
        ungrouped.expect(200);
        assertThat(ungrouped.body.path("sellable").asBoolean()).isTrue();
        call(put(path(i) + "/group").content("{\"groupId\":null,\"expectedVersion\":0}")).expect(200);
        call(delete(groupPath(g)).param("expectedVersion", "1")).expect(204);
        call(delete(groupPath(g)).param("expectedVersion", "0")).expect(204);
        assertThat(events("MENU_ITEM_GROUP_CHANGED")).isEqualTo(1);
    }

    @Test
    void crossTenantAndIngredientIdsAreHiddenAndDatabaseRejectsCrossTenantGroup() throws Exception {
        var i = createItem(null, null);
        var other = fixture();
        call(get(path(i)), other, ALL).error(404, "MENU_ITEM_NOT_FOUND");
        call(delete(path(i)).param("expectedVersion", "0"), other, ALL).error(404, "MENU_ITEM_NOT_FOUND");
        var foreign = call(post("/api/v1/menu/groups").content("{\"name\":\"Foreign\"}"), other, ALL);
        foreign.expect(201);
        call(put(path(i) + "/group").content("{\"groupId\":\"" + foreign.id() + "\",\"expectedVersion\":0}")).error(404,
                "MENU_GROUP_NOT_FOUND");
        assertThatThrownBy(() -> jdbc.update("update items set item_group_id=? where id=?", foreign.id(), i.id()))
                .hasStackTraceContaining("fk_menu_item_group");
        jdbc.update("update items set item_type='INGREDIENT' where id=?", i.id());
        call(get(path(i))).error(404, "MENU_ITEM_NOT_FOUND");
        call(delete(path(i)).param("expectedVersion", "0")).error(404, "MENU_ITEM_NOT_FOUND");
        assertThat(call(get("/api/v1/menu/items")).body.path("totalElements").asLong()).isZero();
    }

    @Test
    void validationAndReadOnlyFieldsCannotBypassBusinessRules() throws Exception {
        for (String extra : List.of("\"itemType\":\"INGREDIENT\"",
                "\"restaurantId\":\"" + owner.getRestaurantId() + "\"", "\"costPrice\":1",
                "\"availabilityStatus\":\"OUT_OF_STOCK\"", "\"metadata\":{}", "\"recipe\":{}")) {
            call(post("/api/v1/menu/items")
                    .content("{\"name\":\"Coffee\",\"baseUnit\":\"cup\",\"salePrice\":1," + extra + "}"))
                    .error(400, "INVALID_REQUEST_BODY");
        }
        for (String price : List.of("-1", "1.001", "1000000000000"))
            call(post("/api/v1/menu/items")
                    .content("{\"name\":\"Coffee\",\"baseUnit\":\"cup\",\"salePrice\":" + price + "}"))
                    .error(400, "VALIDATION_ERROR");
        var i = createItem(null, "sku");
        call(patch(path(i)).content("{\"expectedVersion\":0}")).error(400, "EMPTY_UPDATE_REQUEST");
        call(patch(path(i)).content("{\"active\":false,\"expectedVersion\":0}")).error(400, "INVALID_REQUEST_BODY");
        call(patch(path(i)).content("{\"name\":\"   \",\"expectedVersion\":0}")).error(400, "VALIDATION_ERROR");
        call(patch(path(i)).content("{\"imageUrl\":\"file:///x\",\"expectedVersion\":0}")).error(400,
                "VALIDATION_ERROR");
        call(patch(path(i)).content("{\"sku\":\"\",\"expectedVersion\":0}")).expect(200);
        assertThat(items.findById(i.id()).orElseThrow().getSku()).isNull();
    }

    @Test
    void duplicateNamesSkuReservationAndStaleEditsHaveSpecificErrors() throws Exception {
        createGroup("Drinks");
        call(post("/api/v1/menu/groups").content("{\"name\":\" drinks \"}")).error(409, "MENU_GROUP_NAME_EXISTS");
        var i = createItem(null, "abc");
        call(post("/api/v1/menu/items").content(itemBody(null, "ABC"))).error(409, "SKU_EXISTS");
        call(patch(path(i)).content("{\"name\":\"New\",\"expectedVersion\":0}")).expect(200);
        call(patch(path(i)).content("{\"name\":\"Stale\",\"expectedVersion\":0}")).error(409, "CONCURRENT_MENU_UPDATE");
        call(delete(path(i)).param("expectedVersion", "1")).expect(204);
        call(post("/api/v1/menu/items").content(itemBody(null, "abc"))).error(409, "SKU_EXISTS");
    }

    @Test
    void permissionsAndEntitlementsAreIndependentAndGrantedManagerCanCreate() throws Exception {
        var result = mvc.perform(get("/api/v1/menu/items")).andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(401);
        call(get("/api/v1/menu/items"), owner, Set.of()).error(403, "FORBIDDEN");
        call(post("/api/v1/menu/groups").content("{\"name\":\"Denied\"}"), owner, Set.of("MENU_VIEW")).error(403,
                "FORBIDDEN");
        jdbc.update(
                "update users set role_id=(select id from roles where code='MANAGER' and restaurant_id is null) where id=?",
                owner.getId());
        call(post("/api/v1/menu/groups").content("{\"name\":\"Granted manager\"}"), owner, ALL).expect(201);
        jdbc.update(
                "update restaurant_subscriptions set feature_snapshot=jsonb_set(feature_snapshot,'{features}','[]'::jsonb) where restaurant_id=?",
                owner.getRestaurantId());
        call(get("/api/v1/menu/items")).error(403, "FEATURE_NOT_ENTITLED");
        jdbc.update("update restaurant_subscriptions set status='CANCELLED' where restaurant_id=?",
                owner.getRestaurantId());
        call(get("/api/v1/menu/items")).error(403, "SUBSCRIPTION_NOT_ACTIVE");
    }

    @Test
    void queryFiltersSortingEscapedSearchAndBatchGroupReads() throws Exception {
        var g = createGroup("Group");
        var first = createItem(g.id(), "MATCH%_");
        createItem(g.id(), "MATCHxx");
        var found = call(get("/api/v1/menu/items").param("q", "%_"));
        found.expect(200);
        assertThat(found.body.path("totalElements").asInt()).isEqualTo(1);
        call(get("/api/v1/menu/items").param("sortBy", "costPrice")).error(400, "VALIDATION_ERROR");
        call(get("/api/v1/menu/items").param("size", "101")).error(400, "VALIDATION_ERROR");
        call(patch(path(first) + "/availability")
                .content("{\"availabilityStatus\":\"OUT_OF_STOCK\",\"expectedVersion\":0}")).expect(200);
        assertThat(call(get("/api/v1/menu/items").param("sellable", "true")).body.path("totalElements").asInt())
                .isEqualTo(1);
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean enabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        try {
            stats.clear();
            call(get("/api/v1/menu/items").param("size", "100")).expect(200);
            long small = stats.getPrepareStatementCount();
            for (int n = 0; n < 8; n++)
                createItem(g.id(), "BATCH" + n);
            stats.clear();
            call(get("/api/v1/menu/items").param("size", "100")).expect(200);
            assertThat(stats.getPrepareStatementCount()).isEqualTo(small);
        } finally {
            stats.setStatisticsEnabled(enabled);
        }
    }

    @Test
    void concurrentSameSkuHasExactlyOneWinner() throws Exception {
        var results = race(() -> call(post("/api/v1/menu/items").content(itemBody(null, "RACE"))),
                () -> call(post("/api/v1/menu/items").content(itemBody(null, "RACE"))));
        assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(events("MENU_ITEM_CREATED")).isEqualTo(1);
    }

    @Test
    void groupDeleteAndCreateCannotLeaveLiveItemInDeletedGroup() throws Exception {
        var g = createGroup("Race");
        var results = race(() -> call(delete(groupPath(g)).param("expectedVersion", "0")),
                () -> call(post("/api/v1/menu/items").content(itemBody(g.id(), null))));
        assertThat(results.stream().filter(r -> r.status == 201 || r.status == 204).count()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from items i join item_groups g on g.id=i.item_group_id where i.restaurant_id=? and i.deleted_at is null and g.deleted_at is not null",
                Long.class, owner.getRestaurantId())).isZero();
    }

    @Test
    void auditFailureRollsBackItemCreation() throws Exception {
        doThrow(new IllegalStateException("Simulated audit failure")).when(audit).record(eq(owner.getRestaurantId()),
                eq(owner.getId()), eq("MENU_ITEM_CREATED"), eq("items"), any(), isNull(), anyMap(), any());
        call(post("/api/v1/menu/items").content(itemBody(null, "ROLLBACK"))).expect(500);
        assertThat(jdbc.queryForObject("select count(*) from items where restaurant_id=?", Long.class,
                owner.getRestaurantId())).isZero();
    }

    @Test
    void groupDeleteAndReassignmentCannotLeaveLiveItemInDeletedGroup() throws Exception {
        var g = createGroup("Destination");
        var i = createItem(null, null);
        var results = race(() -> call(delete(groupPath(g)).param("expectedVersion", "0")),
                () -> call(put(path(i) + "/group").content("{\"groupId\":\"" + g.id() + "\",\"expectedVersion\":0}")));
        var statuses = results.stream().map(Result::status).toList();
        assertThat(statuses.equals(List.of(204, 404)) || statuses.equals(List.of(409, 200))).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from items i join item_groups g on g.id=i.item_group_id where i.restaurant_id=? and i.deleted_at is null and g.deleted_at is not null",
                Long.class, owner.getRestaurantId())).isZero();
    }

    @Test
    void noOpInformationAndGroupStatusKeepTimestampVersionAndAudit() throws Exception {
        var g = createGroup("Group");
        var i = createItem(g.id(), null);
        var unchanged = call(patch(path(i)).content("{\"salePrice\":35000,\"expectedVersion\":0}"));
        unchanged.expect(200);
        assertThat(unchanged.body.path("updatedAt")).isEqualTo(i.body.path("updatedAt"));
        assertThat(unchanged.body.path("version").asLong()).isZero();
        assertThat(events("MENU_ITEM_UPDATED")).isZero();
        var changed = call(
                patch(groupPath(g)).content("{\"name\":\"Renamed\",\"displayOrder\":2,\"expectedVersion\":0}"));
        changed.expect(200);
        call(patch(groupPath(g)).content("{\"displayOrder\":3,\"expectedVersion\":0}")).error(409,
                "CONCURRENT_MENU_UPDATE");
        var same = call(patch(groupPath(g) + "/status").content("{\"active\":true,\"expectedVersion\":0}"));
        same.expect(200);
        assertThat(same.body.path("updatedAt")).isEqualTo(changed.body.path("updatedAt"));
        assertThat(events("MENU_GROUP_ENABLED")).isZero();
        call(patch(groupPath(g) + "/status").content("{\"active\":false,\"expectedVersion\":1}")).expect(200);
        call(patch(groupPath(g) + "/status").content("{\"active\":true,\"expectedVersion\":2}")).expect(200);
        assertThat(call(get(path(i))).body.path("sellable").asBoolean()).isTrue();
        assertThat(call(get(path(i))).body.path("version").asLong()).isZero();
    }

    @Test
    void databaseChecksProtectMenuInvariants() throws Exception {
        var g = createGroup("Group");
        var i = createItem(g.id(), null);
        for (String assignment : List.of("sale_price=-1", "cost_price=-1", "track_inventory=true",
                "item_type='UNKNOWN'", "item_type='INGREDIENT'", "availability_status='UNKNOWN'",
                "metadata='[]'::jsonb")) {
            assertThatThrownBy(() -> jdbc.update("update items set " + assignment + " where id=?", i.id()))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("update item_groups set display_order=-1 where id=?", g.id()))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void realJwtRespectsManagerOverridesAndAccountRestaurantGuards() throws Exception {
        jdbc.update("update users set role_id=(select id from roles where code='MANAGER' and restaurant_id is null) where id=?", owner.getId());
        var defaults = permissions.getEffectivePermissionCodes(owner.getId());
        assertThat(defaults).doesNotContain("MENU_CREATE");
        bearer(post("/api/v1/menu/groups").content("{\"name\":\"Denied\"}"), defaults)
            .error(403, "FORBIDDEN");
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'GRANT',? from permissions where code in ('MENU_CREATE','MENU_VIEW')", owner.getId(), owner.getId());
        var granted = permissions.getEffectivePermissionCodes(owner.getId());
        assertThat(granted).contains("MENU_CREATE", "MENU_VIEW");
        bearer(post("/api/v1/menu/groups").content("{\"name\":\"Granted\"}"), granted).expect(201);
        jdbc.update("update restaurants set status='SUSPENDED' where id=?", owner.getRestaurantId());
        bearer(get("/api/v1/menu/groups"), granted).error(403, "RESTAURANT_INACTIVE");
        jdbc.update("update restaurants set status='ACTIVE' where id=?", owner.getRestaurantId());
        jdbc.update("update users set is_active=false where id=?", owner.getId());
        bearer(get("/api/v1/menu/groups"), granted).error(401, "ACCOUNT_INACTIVE");
    }

    private Result bearer(MockHttpServletRequestBuilder request, Set<String> codes) throws Exception {
        String token = tokens.createAccessToken(new CurrentUser(owner.getId(), owner.getRestaurantId(),
            owner.getEmail(), "MANAGER", codes));
        var response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .header("Authorization", "Bearer " + token)).andReturn().getResponse();
        return new Result(response.getStatus(), json.readTree(response.getContentAsString()));
    }

    private Result createGroup(String name) throws Exception {
        var r = call(post("/api/v1/menu/groups").content(json.writeValueAsString(Map.of("name", name))));
        r.expect(201);
        return r;
    }

    private Result createItem(UUID group, String sku) throws Exception {
        var r = call(post("/api/v1/menu/items").content(itemBody(group, sku)));
        r.expect(201);
        return r;
    }

    private String itemBody(UUID group, String sku) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Coffee");
        m.put("baseUnit", "cup");
        m.put("salePrice", 35000);
        m.put("groupId", group);
        m.put("sku", sku);
        return json.writeValueAsString(m);
    }

    private String path(Result r) {
        return "/api/v1/menu/items/" + r.id();
    }

    private String groupPath(Result r) {
        return "/api/v1/menu/groups/" + r.id();
    }

    private Result call(MockHttpServletRequestBuilder request) throws Exception {
        return call(request, owner, ALL);
    }

    private Result call(MockHttpServletRequestBuilder request, User user, Set<String> codes) throws Exception {
        String role = jdbc.queryForObject("select r.code from roles r join users u on u.role_id=r.id where u.id=?",
                String.class, user.getId());
        var principal = new CurrentUser(user.getId(), user.getRestaurantId(), user.getEmail(), role, codes);
        var auth = new UsernamePasswordAuthenticationToken(principal, null,
                codes.stream().map(SimpleGrantedAuthority::new).toList());
        var r = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth))).andReturn()
                .getResponse();
        return new Result(r.getStatus(),
                r.getContentAsString().isBlank() ? json.createObjectNode() : json.readTree(r.getContentAsString()));
    }

    private long events(String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",
                Long.class, owner.getRestaurantId(), action);
    }

    private User fixture() {
        return new TransactionTemplate(transactions).execute(tx -> {
            var r = new Restaurant();
            r.setCode("MENU" + UUID.randomUUID().toString().replace("-", ""));
            r.setName("Menu test");
            restaurants.saveAndFlush(r);
            tenants.add(r.getId());
            var u = new User();
            u.setRestaurantId(r.getId());
            u.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
            u.setName("Owner");
            u.setEmail(UUID.randomUUID() + "@example.com");
            u.setPasswordHash("fixture-only");
            users.saveAndFlush(u);
            var s = new RestaurantSubscription();
            s.setRestaurantId(r.getId());
            s.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
            s.setStatus(SubscriptionStatus.ACTIVE);
            s.setStartAt(clock.instant().minusSeconds(30));
            s.setEndAt(clock.instant().plusSeconds(3600));
            s.setPriceAmount(BigDecimal.ZERO);
            s.setCurrencyCode("VND");
            s.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO",
                    List.of(new SubscriptionFeatureSnapshot.FeatureGrant("MENU_MANAGEMENT", Map.of())), clock.instant())
                    .toMap());
            subscriptions.saveAndFlush(s);
            return u;
        });
    }

    private List<Result> race(Callable<Result> first, Callable<Result> second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS))
                    throw new TimeoutException();
                return first.call();
            });
            var b = pool.submit(() -> {
                ready.countDown();
                if (!go.await(10, TimeUnit.SECONDS))
                    throw new TimeoutException();
                return second.call();
            });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    private record Result(int status, JsonNode body) {
        UUID id() {
            return UUID.fromString(body.path("id").asText());
        }

        void expect(int expected) {
            assertThat(status).describedAs(body.toString()).isEqualTo(expected);
        }

        void error(int expected, String code) {
            expect(expected);
            assertThat(body.path("code").asText()).isEqualTo(code);
        }
    }
}
