package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.*;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.restaurant.entity.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
        "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN", "app.cors.allowed-origins=http://localhost:3000"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PublicRestaurantMenuIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PublicRestaurantMenuIntegrationTest {
    private static final String SCHEMA = "it_restaurant_menu_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String PUBLIC = "/api/v1/public/menu/restaurants/";
    private static final String LINK = "/api/v1/restaurants/me/menu-link";
    private static final String PERMISSION = "RESTAURANT_PROFILE_UPDATE";

    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock restaurantMenuClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry properties) {
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
    @Autowired PermissionService permissions;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemGroupRepository groups;
    @MockitoSpyBean ItemRepository items;
    @MockitoSpyBean PublicLinkTokenGenerator tokens;
    @MockitoSpyBean AuditService audit;
    @Autowired RestaurantTableRepository tables;
    @Autowired TableAreaRepository areas;
    @Autowired JwtTokenService jwt;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    @Autowired EntityManagerFactory emf;
    private Restaurant restaurant;
    private User owner;
    private RestaurantSubscription subscription;
    private ItemGroup group;

    @BeforeEach void fixture() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        restaurant = new Restaurant(); restaurant.setCode("MENU" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Takeaway Restaurant"); restaurant.setPublicOrderToken(tokens.generate());
        restaurant = restaurants.saveAndFlush(restaurant);
        owner = new User(); owner.setRestaurantId(restaurant.getId());
        owner.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        owner.setName("Fixture owner"); owner.setEmail(UUID.randomUUID() + "@example.test");
        owner.setPasswordHash("unused-fixture-hash"); owner = users.saveAndFlush(owner);
        subscription = new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId()); subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(NOW.minusSeconds(60)); subscription.setEndAt(NOW.plusSeconds(3600));
        subscription.setActivatedAt(NOW.minusSeconds(60)); subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(snapshot(List.of("QR_MENU_VIEW"))); subscription = subscriptions.saveAndFlush(subscription);
        group = group("Main", 0);
    }
    @AfterAll void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_restaurant_menu_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test void anonymousContextAndItemsHaveStrictPublicAllowlistWithoutTables() throws Exception {
        item("Pho", group.getId()); var context = body(get(path()), 200);
        assertThat(fields(context)).containsExactlyInAnyOrder("restaurant", "groups");
        assertThat(fields(context.path("restaurant"))).containsExactlyInAnyOrder("name", "currencyCode");
        assertThat(fields(context.path("groups").get(0))).containsExactlyInAnyOrder("id", "name", "displayOrder");
        var result = body(get(path() + "/items"), 200);
        assertThat(fields(result)).containsExactlyInAnyOrder("content", "page", "size", "totalElements", "totalPages");
        assertThat(fields(result.path("content").get(0))).containsExactlyInAnyOrder("id", "group", "name", "description",
                "imageUrl", "baseUnit", "salePrice", "availabilityStatus");
        assertThat(result.path("content").get(0).path("salePrice").decimalValue()).isEqualByComparingTo("50000.00");
        assertThat(jdbc.queryForObject("select count(*) from restaurant_tables where restaurant_id=?", Long.class, restaurant.getId())).isZero();
    }

    @Test void inactiveTablesAndAreasDoNotGateRestaurantMenu() throws Exception {
        var area = new TableArea(); area.initialize(restaurant.getId(), NOW); area.setName("Closed area"); area.setActive(false);
        area = areas.saveAndFlush(area); var table = table(); table.setAreaId(area.getId()); table.setStatus(TableStatus.INACTIVE);
        tables.saveAndFlush(table);
        body(get(path()), 200); body(get(path() + "/items"), 200);
    }

    @Test void tableAndRestaurantTokensNeverResolveThroughTheOtherEndpoint() throws Exception {
        var table = table();
        error(get(PUBLIC + table.getQrToken()), 404, "PUBLIC_MENU_NOT_FOUND");
        error(get(PUBLIC + table.getQrToken() + "/items"), 404, "PUBLIC_MENU_NOT_FOUND");
        error(get("/api/v1/public/menu/tables/" + restaurant.getPublicOrderToken()), 404, "QR_MENU_NOT_FOUND");
        body(get(path()), 200); body(get("/api/v1/public/menu/tables/" + table.getQrToken()), 200);
    }

    @Test void tokenValidationAndRestaurantEligibilityUseGeneric404() throws Exception {
        for (String token : List.of("invalid", "a".repeat(43))) error(get(PUBLIC + token), 404, "PUBLIC_MENU_NOT_FOUND");
        for (String status : List.of("INACTIVE", "SUSPENDED")) {
            jdbc.update("update restaurants set status=? where id=?", status, restaurant.getId());
            error(get(path()), 404, "PUBLIC_MENU_NOT_FOUND");
        }
        jdbc.update("update restaurants set status='ACTIVE', deleted_at=? where id=?", java.sql.Timestamp.from(NOW), restaurant.getId());
        error(get(path() + "/items"), 404, "PUBLIC_MENU_NOT_FOUND");
    }

    @Test void subscriptionEndBoundaryAndOtherIneffectivePeriodsAreDeniedWithoutWrites() throws Exception {
        subscription.setEndAt(NOW); subscription = subscriptions.saveAndFlush(subscription);
        error(get(path()), 403, "PUBLIC_MENU_UNAVAILABLE"); error(get(path() + "/items"), 403, "PUBLIC_MENU_UNAVAILABLE");
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        subscription.setEndAt(NOW.plusSeconds(3600)); subscription.setStartAt(NOW.plusSeconds(1));
        subscription = subscriptions.saveAndFlush(subscription); error(get(path()), 403, "PUBLIC_MENU_UNAVAILABLE");
        subscription.setStartAt(NOW.minusSeconds(60));
        for (SubscriptionStatus status : List.of(SubscriptionStatus.PENDING, SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED)) {
            subscription.setStatus(status); subscription = subscriptions.saveAndFlush(subscription);
            error(get(path()), 403, "PUBLIC_MENU_UNAVAILABLE");
        }
        subscriptions.deleteById(subscription.getId()); error(get(path()), 403, "PUBLIC_MENU_UNAVAILABLE");
    }

    @Test void snapshotGrantNotPackageCatalogDeterminesAccess() throws Exception {
        var plan = packages.findById(subscription.getPackageId()).orElseThrow(); boolean active = plan.isActive();
        var before = subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot();
        try {
            plan.setActive(false); packages.saveAndFlush(plan); body(get(path()), 200);
            assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot()).isEqualTo(before);
        } finally { var restore = packages.findById(plan.getId()).orElseThrow(); restore.setActive(active); packages.saveAndFlush(restore); }
        subscription.setFeatureSnapshot(snapshot(List.of())); subscriptions.saveAndFlush(subscription);
        error(get(path()), 403, "PUBLIC_MENU_UNAVAILABLE");
    }

    @Test void visibilityFilteringPrecedesPaginationAndKeepsOutOfStockUngroupedItems() throws Exception {
        item("Visible", group.getId()); var out = item("Out", null); out.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); items.saveAndFlush(out);
        var inactive = item("Inactive", null); inactive.setActive(false); items.saveAndFlush(inactive);
        var deleted = item("Deleted", null); deleted.setDeletedAt(NOW); items.saveAndFlush(deleted);
        var ingredient = item("Ingredient", null); ingredient.setItemType(ItemType.INGREDIENT); items.saveAndFlush(ingredient);
        var hidden = group("Hidden", 1); hidden.setActive(false); groups.saveAndFlush(hidden); item("Hidden item", hidden.getId());
        var removed = group("Removed", 2); removed.setDeletedAt(NOW); groups.saveAndFlush(removed); item("Removed item", removed.getId());
        var first = body(get(path() + "/items").param("size", "1"), 200);
        assertThat(first.path("totalElements").asLong()).isEqualTo(2); assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("content").get(0).path("group").isNull()).isTrue();
        assertThat(first.path("content").get(0).path("availabilityStatus").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(body(get(path() + "/items").param("size", "1").param("page", "1"), 200)
                .path("content").get(0).path("name").asText()).isEqualTo("Visible");
        assertThat(body(get(path()), 200).path("groups").size()).isEqualTo(1);
    }

    @Test void groupsHaveStableOrderingAndCanBeEmpty() throws Exception {
        group.setDisplayOrder(1); groups.saveAndFlush(group); var first = group("First", 0); var last = group("Last", 2);
        var result = body(get(path()), 200).path("groups");
        assertThat(result.get(0).path("id").asText()).isEqualTo(first.getId().toString());
        assertThat(result.get(2).path("id").asText()).isEqualTo(last.getId().toString());
        var page = body(get(path() + "/items"), 200); assertThat(page.path("content").size()).isZero();
        assertThat(page.path("totalPages").asInt()).isZero();
    }

    @Test void nameSearchUsesLiteralPercentUnderscoreAndBackslash() throws Exception {
        item("Pho 100%_\\", group.getId()); var other = item("Other", group.getId()); other.setDescription("Pho 100%_\\"); items.saveAndFlush(other);
        for (String q : List.of("pHO", "%", "_", "\\"))
            assertThat(body(get(path() + "/items").param("q", q), 200).path("totalElements").asInt()).isEqualTo(1);
    }

    @Test void validGroupAndPriceSortKeepIdTieBreaker() throws Exception {
        var a = item("Same", group.getId()); var b = item("Same", group.getId()); item("Other", group("Other", 1).getId());
        var result = body(get(path() + "/items").param("groupId", group.getId().toString()).param("sortBy", "salePrice").param("direction", "desc"), 200);
        var expected = List.of(a.getId().toString(), b.getId().toString()).stream().sorted().toList();
        assertThat(result.path("totalElements").asLong()).isEqualTo(2);
        assertThat(result.path("content").get(0).path("id").asText()).isEqualTo(expected.get(0));
    }

    @Test void foreignTenantItemsAndHiddenOrForeignGroupFiltersAreRejected() throws Exception {
        var ownRestaurant = restaurant; var ownGroup = group; fixture(); var foreignGroup = group; item("Foreign", foreignGroup.getId());
        restaurant = ownRestaurant; group = ownGroup;
        assertThat(body(get(path() + "/items"), 200).path("totalElements").asInt()).isZero();
        for (UUID id : List.of(foreignGroup.getId(), UUID.randomUUID()))
            error(get(path() + "/items").param("groupId", id.toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
        group.setActive(false); group = groups.saveAndFlush(group);
        error(get(path() + "/items").param("groupId", group.getId().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
        group.setActive(true); group.setDeletedAt(NOW); groups.saveAndFlush(group);
        error(get(path() + "/items").param("groupId", group.getId().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
    }

    @Test void invalidQueriesAreSanitized400(CapturedOutput output) throws Exception {
        for (var parameter : Map.of("page", "-1", "size", "101", "sortBy", "costPrice", "direction", "bad", "groupId", token(), "q", "x".repeat(101)).entrySet())
            error(get(path() + "/items").param(parameter.getKey(), parameter.getValue()), 400, "VALIDATION_ERROR");
        error(get(path() + "/items").param("page", "bad"), 400, "VALIDATION_ERROR");
        error(get(path() + "/items").param("size", "0"), 400, "VALIDATION_ERROR");
        assertThat(output.getAll()).doesNotContain(token());
    }

    @Test void foreignExpiredMalformedAndInactiveBearersDoNotGatePublicGet() throws Exception {
        owner.setActive(false); users.saveAndFlush(owner);
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("pos-saas-be").subject(owner.getId().toString())
                .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String expired = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
        String inactive = jwt.createAccessToken(actor(Set.of(PERMISSION)));
        String foreign = jwt.createAccessToken(new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "foreign@example.test", "OWNER", Set.of()));
        for (String bearer : List.of("bad", expired, inactive, foreign)) {
            body(get(path()).header("Authorization", "Bearer " + bearer), 200);
            body(get(path() + "/items").header("Authorization", "Bearer " + bearer), 200);
        }
        assertThat(call(get(LINK).header("Authorization", "Bearer " + inactive)).getStatus()).isEqualTo(401);
    }

    @Test void publicReadsNeverMutateOrInitializeToken() throws Exception {
        var before = jdbc.queryForMap("select updated_at, public_order_token from restaurants where id=?", restaurant.getId());
        var snapshot = subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot();
        body(get(path()), 200); body(get(path() + "/items"), 200);
        assertThat(jdbc.queryForMap("select updated_at, public_order_token from restaurants where id=?", restaurant.getId())).isEqualTo(before);
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot()).isEqualTo(snapshot);
        assertThat(events()).isZero();
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where restaurant_id=?", Long.class, restaurant.getId())).isZero();
        String old = token(); jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        error(get(PUBLIC + old), 404, "PUBLIC_MENU_NOT_FOUND"); assertThat(storedToken()).isNull();
    }

    @Test void queriesBatchGroupsInsteadOfNPlusOne() throws Exception {
        for (int i = 0; i < 20; i++) item("Item " + i, group("Group " + i, i).getId());
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.setStatisticsEnabled(true);
        try {
            statistics.clear(); body(get(path() + "/items").param("size", "5"), 200); long small = statistics.getPrepareStatementCount();
            statistics.clear(); body(get(path() + "/items").param("size", "20"), 200);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(small).isLessThanOrEqualTo(7);
        } finally { statistics.setStatisticsEnabled(false); }
    }

    @Test void corsPreflightAndProtectedRoutesKeepTheirBoundaries() throws Exception {
        var allowed = call(get(path()).header("Origin", "http://localhost:3000"));
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(call(get(path()).header("Origin", "https://untrusted.example")).getStatus()).isEqualTo(403);
        assertThat(call(options(path()).header("Origin", "http://localhost:3000").header("Access-Control-Request-Method", "GET")).getStatus()).isEqualTo(200);
        for (var request : List.of(post(path()), patch(path()), delete(path()), head(path()), get(path() + "/extra"),
                get(LINK), post(LINK), post(LINK + "/rotate"), get("/api/v1/menu/items"), get("/api/v1/tables"), get("/api/v1/admin/restaurants"),
                get("/api/v1/public/menu/" + token()), get("/api/v1/public/qr-menu/" + token())))
            assertThat(call(request).getStatus()).isIn(401, 403);
    }

    @Test void databaseAndCorruptSnapshotFailuresAre500WithoutTokenLeak(CapturedOutput output) throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("private " + token()))
                .when(items).findAll(any(Specification.class), any(Pageable.class));
        error(get(path() + "/items"), 500, "INTERNAL_ERROR");
        subscription.setFeatureSnapshot(Map.of("schemaVersion", 2, "features", List.of(), "capturedAt", NOW.toString()));
        subscriptions.saveAndFlush(subscription); error(get(path()), 500, "INTERNAL_ERROR");
        assertThat(output.getAll()).doesNotContain(token());
    }

    @Test void managerAndOwnerDefaultsCanReadLinkWithoutQrFeatureOrSubscription() throws Exception {
        subscriptions.deleteById(subscription.getId());
        for (String role : List.of("OWNER", "MANAGER")) {
            owner.setRoleId(roles.findByCodeAndRestaurantIdIsNull(role).orElseThrow().getId()); owner = users.saveAndFlush(owner);
            var user = new CurrentUser(owner.getId(), restaurant.getId(), owner.getEmail(), role, permissions.getEffectivePermissionCodes(owner.getId()));
            var result = body(get(LINK).header("Authorization", "Bearer " + jwt.createAccessToken(user)), 200);
            assertThat(fields(result)).containsExactlyInAnyOrder("menuToken", "menuPath");
            assertThat(result.path("menuToken").asText()).isEqualTo(token());
            assertThat(result.path("menuPath").asText()).isEqualTo("/menu/" + token());
        }
        assertThat(events()).isZero();
    }

    @Test void linkRequiresPermissionAndTenantForEveryOperation() throws Exception {
        for (var request : List.of(get(LINK), post(LINK), post(LINK + "/rotate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedToken", token()))))) {
            error(auth(request, actor(Set.of())), 403, "FORBIDDEN");
        }
        var system = new CurrentUser(UUID.randomUUID(), null, "system@example.test", "SUPER_ADMIN", Set.of(PERMISSION));
        error(auth(get(LINK), system), 403, "TENANT_ACCESS_DENIED");
        error(auth(post(LINK), system), 403, "TENANT_ACCESS_DENIED");
        error(auth(rotate(token()), system), 403, "TENANT_ACCESS_DENIED");
        assertThat(events()).isZero();
    }

    @Test void ownLinkCannotBeSelectedUsingForeignTenantHeaderOrQuery() throws Exception {
        var own = restaurant; var actor = actor(Set.of(PERMISSION)); fixture(); var foreign = restaurant;
        var result = body(auth(get(LINK).param("restaurantId", foreign.getId().toString()).header("X-Restaurant-Id", foreign.getId()), actor), 200);
        assertThat(result.path("menuToken").asText()).isEqualTo(own.getPublicOrderToken());
    }

    @Test void getNullTokenIsReadOnlyThenInitializeIsIdempotentAndAuditSafe() throws Exception {
        jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        error(own(get(LINK)), 404, "PUBLIC_MENU_LINK_NOT_INITIALIZED"); assertThat(storedToken()).isNull(); assertThat(events()).isZero();
        var first = body(own(post(LINK)), 200); var second = body(own(post(LINK)), 200);
        assertThat(second).isEqualTo(first); assertThat(first.path("menuToken").asText()).matches("[A-Za-z0-9_-]{43}");
        assertThat(events("RESTAURANT_MENU_LINK_INITIALIZED")).isEqualTo(1);
        assertSafeAudit(first.path("menuToken").asText());
    }

    @Test void initializingExistingTokenDoesNotRotateOrAudit() throws Exception {
        assertThat(body(own(post(LINK)), 200).path("menuToken").asText()).isEqualTo(token()); assertThat(events()).isZero();
    }

    @Test void twoConcurrentInitializationsShareOneTokenAndAudit() throws Exception {
        jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        var results = race(() -> body(own(post(LINK)), 200), () -> body(own(post(LINK)), 200));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(events("RESTAURANT_MENU_LINK_INITIALIZED")).isEqualTo(1);
    }

    @Test void rotationRejectsStaleTokenAndChangesOnlyRestaurantLink(CapturedOutput output) throws Exception {
        var table = table(); String previous = token(); var beforeSnapshot = subscription.getFeatureSnapshot();
        var result = body(own(rotate(previous)), 200); String next = result.path("menuToken").asText();
        assertThat(next).isNotEqualTo(previous); error(get(PUBLIC + previous), 404, "PUBLIC_MENU_NOT_FOUND"); body(get(PUBLIC + next), 200);
        error(own(rotate(previous)), 409, "CONCURRENT_MENU_LINK_UPDATE");
        assertThat(tables.findById(table.getId()).orElseThrow().getQrToken()).isEqualTo(table.getQrToken());
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot()).isEqualTo(beforeSnapshot);
        assertThat(events("RESTAURANT_MENU_LINK_ROTATED")).isEqualTo(1); assertSafeAudit(previous, next);
        assertThat(output.getAll()).doesNotContain(previous, next);
    }

    @Test void twoConcurrentRotationsWithSameExpectedTokenHaveOneWinner() throws Exception {
        String previous = token();
        var results = race(() -> call(own(rotate(previous))), () -> call(own(rotate(previous))));
        assertThat(results).extracting(org.springframework.mock.web.MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(events("RESTAURANT_MENU_LINK_ROTATED")).isEqualTo(1);
    }

    @Test void failedAuditRollsBackRotationAndInitialization() throws Exception {
        String previous = token();
        doThrow(new IllegalStateException("private " + previous)).when(audit).record(eq(restaurant.getId()), eq(owner.getId()),
                anyString(), eq("restaurants"), eq(restaurant.getId()), anyMap(), anyMap(), any());
        error(own(rotate(previous)), 500, "INTERNAL_ERROR"); assertThat(storedToken()).isEqualTo(previous); assertThat(events()).isZero();
        jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        error(own(post(LINK)), 500, "INTERNAL_ERROR"); assertThat(storedToken()).isNull(); assertThat(events()).isZero();
    }

    @Test void uniqueConstraintFailureIs409AndDoesNotPartiallySaveOrAudit(CapturedOutput output) throws Exception {
        var foreign = new Restaurant(); foreign.setCode("OTHER" + UUID.randomUUID().toString().replace("-", "")); foreign.setName("Other");
        foreign.setPublicOrderToken(tokens.generate()); foreign = restaurants.saveAndFlush(foreign);
        String collision = foreign.getPublicOrderToken(); String previous = token(); doReturn(collision).when(tokens).generate();
        error(own(rotate(previous)), 409, "PUBLIC_MENU_TOKEN_CONFLICT"); assertThat(storedToken()).isEqualTo(previous); assertThat(events()).isZero();
        jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        error(own(post(LINK)), 409, "PUBLIC_MENU_TOKEN_CONFLICT"); assertThat(storedToken()).isNull(); assertThat(events()).isZero();
        assertThat(output.getAll()).doesNotContain(collision, previous);
    }

    @Test void rotationMustBeInitializedAndHaveValidSupportedBody() throws Exception {
        for (String payload : List.of("{}", "{\"expectedToken\":\"\"}", "{\"expectedToken\":null}"))
            error(own(post(LINK + "/rotate").contentType(MediaType.APPLICATION_JSON).content(payload)), 400, "VALIDATION_ERROR");
        error(own(post(LINK + "/rotate").contentType(MediaType.APPLICATION_JSON).content("{\"expectedToken\":\"" + token() + "\",\"restaurantId\":\"" + restaurant.getId() + "\"}")),
                400, "INVALID_REQUEST_BODY");
        error(own(post(LINK + "/rotate").contentType(MediaType.APPLICATION_JSON).content("{")), 400, "INVALID_REQUEST_BODY");
        error(own(post(LINK + "/rotate")), 400, "INVALID_REQUEST_BODY");
        jdbc.update("update restaurants set public_order_token=null where id=?", restaurant.getId());
        error(own(rotate(token())), 404, "PUBLIC_MENU_LINK_NOT_INITIALIZED"); assertThat(events()).isZero();
    }

    @Test void repeatedGeneratorTokenFailsBoundedlyWithoutAudit() throws Exception {
        String previous = token(); doReturn(previous).when(tokens).generate(); clearInvocations(tokens);
        error(own(rotate(previous)), 409, "PUBLIC_MENU_TOKEN_CONFLICT"); assertThat(storedToken()).isEqualTo(previous);
        verify(tokens, times(3)).generate(); assertThat(events()).isZero();
    }

    private Map<String, Object> snapshot(List<String> features) {
        return new SubscriptionFeatureSnapshot(1, "PRO", features.stream().map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(), NOW).toMap();
    }
    private ItemGroup group(String name, int order) {
        var value = new ItemGroup(); value.initialize(restaurant.getId(), NOW); value.setName(name); value.setDisplayOrder(order); return groups.saveAndFlush(value);
    }
    private Item item(String name, UUID groupId) {
        var value = new Item(); value.initialize(restaurant.getId(), NOW); value.setName(name); value.setGroupId(groupId); value.setItemType(ItemType.MENU_ITEM);
        value.setBaseUnit("portion"); value.setSalePrice(new BigDecimal("50000.00")); value.setCostPrice(new BigDecimal("12000.00"));
        value.setSku(UUID.randomUUID().toString()); value.setMetadata(Map.of("private", "hidden")); return items.saveAndFlush(value);
    }
    private RestaurantTable table() {
        var value = new RestaurantTable(); value.initialize(restaurant.getId(), NOW); value.setCode("B" + UUID.randomUUID().toString().substring(0, 8));
        value.setName("Table"); value.setQrToken(tokens.generate()); return tables.saveAndFlush(value);
    }
    private CurrentUser actor(Set<String> granted) { return new CurrentUser(owner.getId(), restaurant.getId(), owner.getEmail(), "OWNER", granted); }
    private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, CurrentUser actor) {
        return request.with(authentication(new UsernamePasswordAuthenticationToken(actor, null,
                actor.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
    }
    private MockHttpServletRequestBuilder own(MockHttpServletRequestBuilder request) { return auth(request, actor(Set.of(PERMISSION))); }
    private MockHttpServletRequestBuilder rotate(String expected) throws Exception {
        return post(LINK + "/rotate").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("expectedToken", expected)));
    }
    private String token() { return restaurant.getPublicOrderToken(); }
    private String path() { return PUBLIC + token(); }
    private String storedToken() { return restaurants.findById(restaurant.getId()).orElseThrow().getPublicOrderToken(); }
    private long events() { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=?", Long.class, restaurant.getId()); }
    private long events(String code) { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?", Long.class, restaurant.getId(), code); }
    private void assertSafeAudit(String... secrets) {
        String records = jdbc.queryForList("select before_data, after_data from audit_logs where restaurant_id=?", restaurant.getId()).toString();
        assertThat(records).doesNotContain(secrets).doesNotContain("menuToken", "expectedToken", "menuPath");
    }
    private org.springframework.mock.web.MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception { return mvc.perform(request).andReturn().getResponse(); }
    private JsonNode body(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = call(request); assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store"); return json.readTree(response.getContentAsString());
    }
    private void error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var result = body(request, status); assertThat(result.path("code").asText()).isEqualTo(code);
        assertThat(result.toString()).doesNotContain(token(), "stackTrace", "SQLException");
    }
    private Set<String> fields(JsonNode node) { var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names; }
    private <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        var ready = new CountDownLatch(2); var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race timeout"); return first.call(); });
            var b = pool.submit(() -> { ready.countDown(); if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Race timeout"); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue(); start.countDown(); return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
