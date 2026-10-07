package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import com.possaas.modules.table.service.TableQrTokenGenerator;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
        "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN", "app.cors.allowed-origins=http://localhost:3000"})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(PublicQrMenuIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PublicQrMenuIntegrationTest {
    private static final String SCHEMA = "it_public_qr_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-07T00:00:00Z");
    private static final String BASE = "/api/v1/public/qr-menu/";
    @TestConfiguration static class FixedTime {
        @Bean @Primary Clock publicQrClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
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
    @Autowired RestaurantTableRepository tables;
    @Autowired TableAreaRepository areas;
    @Autowired ItemGroupRepository groups;
    @MockitoSpyBean ItemRepository items;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired TableQrTokenGenerator qrTokens;
    @Autowired JwtTokenService jwt;
    @Autowired EntityManagerFactory emf;
    @Autowired com.possaas.modules.user.repository.UserRepository users;
    @Autowired com.possaas.modules.authorization.repository.RoleRepository roles;
    @Autowired TableSessionRepository sessions;
    @Autowired org.springframework.security.oauth2.jwt.JwtEncoder encoder;
    private Restaurant restaurant;
    private RestaurantTable table;
    private RestaurantSubscription subscription;
    private ItemGroup group;

    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        restaurant = new Restaurant(); restaurant.setCode("QR" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Public Restaurant"); restaurant.setPublicOrderToken(qrTokens.generate());
        restaurant = restaurants.saveAndFlush(restaurant);
        table = new RestaurantTable(); table.initialize(restaurant.getId(), NOW);
        table.setCode("B01"); table.setName("Ban 1"); table.setQrToken(qrTokens.generate()); table = tables.saveAndFlush(table);
        subscription = new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setStartAt(NOW.minusSeconds(60));
        subscription.setEndAt(NOW.plusSeconds(3600)); subscription.setActivatedAt(NOW.minusSeconds(60));
        subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO",
                List.of(new SubscriptionFeatureSnapshot.FeatureGrant("QR_MENU_VIEW", Map.of())), NOW).toMap());
        subscription = subscriptions.saveAndFlush(subscription);
        group = group("Main", 0);
    }
    @AfterAll void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_public_qr_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test void anonymousContextUsesPublicAllowlistAndDoesNotRequireOpenSession() throws Exception {
        var response = call(get(path()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        var body = json.readTree(response.getContentAsString());
        assertThat(fields(body)).containsExactlyInAnyOrder("restaurant", "table", "groups");
        assertThat(fields(body.path("restaurant"))).containsExactlyInAnyOrder("name", "currencyCode");
        assertThat(fields(body.path("table"))).containsExactlyInAnyOrder("code", "name");
        assertThat(fields(body.path("groups").get(0))).containsExactlyInAnyOrder("id", "name", "displayOrder");
        assertThat(body.path("table").path("code").asText()).isEqualTo("B01");
        assertThat(body.path("restaurant").path("currencyCode").asText()).isEqualTo("VND");
    }

    @Test void emptyMenuReturnsValidEmptyPage() throws Exception {
        var body = body(get(path() + "/items"), 200);
        assertThat(body.path("content").size()).isZero();
        assertThat(body.path("totalElements").asLong()).isZero();
        assertThat(body.path("totalPages").asInt()).isZero();
    }

    @Test void itemResponseNeverExposesPrivateFields() throws Exception {
        item("Pho", group.getId());
        var result = body(get(path() + "/items"), 200).path("content").get(0);
        assertThat(fields(result)).containsExactlyInAnyOrder("id", "group", "name", "description", "imageUrl",
                "baseUnit", "salePrice", "availabilityStatus");
        assertThat(fields(result.path("group"))).containsExactlyInAnyOrder("id", "name");
        assertThat(result.path("salePrice").decimalValue()).isEqualByComparingTo("50000.00");
    }

    @Test void visibilityFilteringOccursBeforePagination() throws Exception {
        item("Visible", group.getId());
        var out = item("Out", null); out.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); items.saveAndFlush(out);
        var inactive = item("Inactive", group.getId()); inactive.setActive(false); items.saveAndFlush(inactive);
        var deleted = item("Deleted", group.getId()); deleted.setDeletedAt(NOW); items.saveAndFlush(deleted);
        var ingredient = item("Ingredient", null); ingredient.setItemType(ItemType.INGREDIENT); items.saveAndFlush(ingredient);
        var hiddenGroup = group("Hidden", 1); hiddenGroup.setActive(false); groups.saveAndFlush(hiddenGroup);
        item("Hidden group item", hiddenGroup.getId());
        var removedGroup = group("Removed", 2); removedGroup.setDeletedAt(NOW); groups.saveAndFlush(removedGroup);
        item("Removed group item", removedGroup.getId());
        var first = body(get(path() + "/items").param("size", "1"), 200);
        assertThat(first.path("totalElements").asLong()).isEqualTo(2);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("content").get(0).path("name").asText()).isEqualTo("Out");
        assertThat(first.path("content").get(0).path("group").isNull()).isTrue();
        assertThat(first.path("content").get(0).path("availabilityStatus").asText()).isEqualTo("OUT_OF_STOCK");
        assertThat(body(get(path() + "/items").param("size", "1").param("page", "1"), 200)
                .path("content").get(0).path("name").asText()).isEqualTo("Visible");
    }

    @Test void groupsAreOrderedAndEmptyGroupsAreAllowed() throws Exception {
        group.setDisplayOrder(1); group = groups.saveAndFlush(group);
        var last = group("Last", 2); var first = group("First", 0);
        var inactive = group("Inactive", 0); inactive.setActive(false); groups.saveAndFlush(inactive);
        var deleted = group("Deleted", 0); deleted.setDeletedAt(NOW); groups.saveAndFlush(deleted);
        var response = body(get(path()), 200).path("groups");
        assertThat(response.size()).isEqualTo(3);
        assertThat(response.get(0).path("id").asText()).isEqualTo(first.getId().toString());
        assertThat(response.get(2).path("id").asText()).isEqualTo(last.getId().toString());
    }

    @Test void searchIsCaseInsensitiveNameOnlyAndEscapesWildcards() throws Exception {
        item("Pho 100%_\\", group.getId()); var other = item("Other", group.getId());
        other.setDescription("Pho 100%_\\"); items.saveAndFlush(other);
        for (String q : List.of("pHO", "%", "_", "\\")) {
            var body = body(get(path() + "/items").param("q", q), 200);
            assertThat(body.path("totalElements").asInt()).isEqualTo(1);
            assertThat(body.path("content").get(0).path("name").asText()).startsWith("Pho");
        }
    }

    @Test void validGroupFilterAndStablePriceSorting() throws Exception {
        var second = group("Second", 1); item("Other", second.getId());
        var a = item("Same", group.getId()); var b = item("Same", group.getId());
        var result = body(get(path() + "/items").param("groupId", group.getId().toString())
                .param("sortBy", "salePrice").param("direction", "desc"), 200);
        assertThat(result.path("totalElements").asInt()).isEqualTo(2);
        var expected = List.of(a.getId().toString(), b.getId().toString()).stream().sorted().toList();
        assertThat(result.path("content").get(0).path("id").asText()).isEqualTo(expected.get(0));
        assertThat(result.path("content").get(1).path("id").asText()).isEqualTo(expected.get(1));
    }

    @Test void invalidInputsAre400AndDoNotEchoRejectedValues() throws Exception {
        for (var parameter : Map.of("page", "-1", "size", "101", "sortBy", "costPrice", "direction", "bad",
                "groupId", table.getQrToken(), "q", "x".repeat(101)).entrySet()) {
            error(get(path() + "/items").param(parameter.getKey(), parameter.getValue()), 400, "VALIDATION_ERROR");
        }
        error(get(path() + "/items").param("size", "0"), 400, "VALIDATION_ERROR");
        error(get(path() + "/items").param("page", "not-number"), 400, "VALIDATION_ERROR");
    }

    @Test void foreignTenantDataAndGroupsAreNeverReturned() throws Exception {
        var ownTable = table; var ownRestaurant = restaurant; var ownGroup = group;
        setup(); var foreignGroup = group; item("Foreign", foreignGroup.getId());
        table = ownTable; restaurant = ownRestaurant; group = ownGroup;
        assertThat(body(get(path() + "/items"), 200).path("totalElements").asInt()).isZero();
        error(get(path() + "/items").param("groupId", foreignGroup.getId().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
        error(get(path() + "/items").param("groupId", UUID.randomUUID().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
        group.setActive(false); group = groups.saveAndFlush(group);
        error(get(path() + "/items").param("groupId", group.getId().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
        group.setActive(true); group.setDeletedAt(NOW); groups.saveAndFlush(group);
        error(get(path() + "/items").param("groupId", group.getId().toString()), 404, "PUBLIC_MENU_GROUP_NOT_FOUND");
    }

    @Test void invalidUnknownAndRestaurantTokensAreGeneric404() throws Exception {
        for (String token : List.of("bad", "a".repeat(43), restaurant.getPublicOrderToken())) {
            error(get(BASE + token), 404, "QR_MENU_NOT_FOUND");
            error(get(BASE + token + "/items"), 404, "QR_MENU_NOT_FOUND");
        }
    }

    @Test void tokenRotationImmediatelyInvalidatesOldLink() throws Exception {
        var oldPath = path(); table.setQrToken(qrTokens.generate()); tables.saveAndFlush(table);
        error(get(oldPath), 404, "QR_MENU_NOT_FOUND");
        error(get(oldPath + "/items"), 404, "QR_MENU_NOT_FOUND");
        body(get(path()), 200);
    }

    @Test void disabledAndDeletedTablesAreUnavailable() throws Exception {
        table.setStatus(TableStatus.INACTIVE); table = tables.saveAndFlush(table);
        error(get(path()), 404, "QR_MENU_NOT_FOUND");
        table.setStatus(TableStatus.AVAILABLE); table.setDeletedAt(NOW); tables.saveAndFlush(table);
        error(get(path() + "/items"), 404, "QR_MENU_NOT_FOUND");
    }

    @Test void inactiveAndDeletedAreasAreUnavailable() throws Exception {
        var area = new TableArea(); area.initialize(restaurant.getId(), NOW); area.setName("Floor");
        area = areas.saveAndFlush(area); table.setAreaId(area.getId()); tables.saveAndFlush(table);
        body(get(path()), 200);
        area.setActive(false); area = areas.saveAndFlush(area); error(get(path()), 404, "QR_MENU_NOT_FOUND");
        area.setActive(true); area.setDeletedAt(NOW); areas.saveAndFlush(area);
        error(get(path() + "/items"), 404, "QR_MENU_NOT_FOUND");
    }

    @Test void suspendedAndDeletedRestaurantsAreUnavailable() throws Exception {
        jdbc.update("update restaurants set status='INACTIVE' where id=?", restaurant.getId());
        error(get(path()), 404, "QR_MENU_NOT_FOUND");
        jdbc.update("update restaurants set status='SUSPENDED' where id=?", restaurant.getId());
        error(get(path()), 404, "QR_MENU_NOT_FOUND");
        jdbc.update("update restaurants set status='ACTIVE', deleted_at=? where id=?", java.sql.Timestamp.from(NOW), restaurant.getId());
        error(get(path() + "/items"), 404, "QR_MENU_NOT_FOUND");
    }

    @Test void expiredActiveIsDeniedWithoutReconciliationWrites() throws Exception {
        subscription.setEndAt(NOW); subscriptions.saveAndFlush(subscription);
        error(get(path()), 403, "QR_MENU_UNAVAILABLE");
        error(get(path() + "/items"), 403, "QR_MENU_UNAVAILABLE");
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test void futureAndNonActiveSubscriptionsAreDenied() throws Exception {
        subscription.setStartAt(NOW.plusSeconds(1)); subscription = subscriptions.saveAndFlush(subscription);
        error(get(path()), 403, "QR_MENU_UNAVAILABLE");
        subscription.setStartAt(NOW.minusSeconds(60));
        for (SubscriptionStatus status : List.of(SubscriptionStatus.PENDING, SubscriptionStatus.CANCELLED, SubscriptionStatus.EXPIRED)) {
            subscription.setStatus(status); subscription = subscriptions.saveAndFlush(subscription);
            error(get(path()), 403, "QR_MENU_UNAVAILABLE");
        }
        subscriptions.deleteById(subscription.getId());
        error(get(path()), 403, "QR_MENU_UNAVAILABLE");
    }

    @Test void entitlementComesFromSnapshotNotPackageCatalog() throws Exception {
        // PRO catalog has QR_MENU_VIEW, but an empty historical snapshot must not gain that feature.
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO", List.of(), NOW).toMap());
        subscriptions.saveAndFlush(subscription);
        error(get(path()), 403, "QR_MENU_UNAVAILABLE");
    }

    @Test void catalogChangesDoNotRewriteExistingSnapshot() throws Exception {
        var plan = packages.findById(subscription.getPackageId()).orElseThrow();
        boolean active = plan.isActive(); var snapshot = subscription.getFeatureSnapshot();
        try {
            plan.setActive(false); packages.saveAndFlush(plan);
            body(get(path()), 200); body(get(path() + "/items"), 200);
            assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot()).isEqualTo(snapshot);
        } finally {
            var restore = packages.findById(subscription.getPackageId()).orElseThrow();
            restore.setActive(active); packages.saveAndFlush(restore);
        }
    }

    @Test void bearerTokensCannotChooseTenantOrBlockPublicGet() throws Exception {
        String foreign = jwt.createAccessToken(new CurrentUser(UUID.randomUUID(), UUID.randomUUID(), "foreign@example.test", "OWNER", Set.of()));
        for (String bearer : List.of("broken", foreign, "eyJhbGciOiJIUzI1NiJ9.eyJleHAiOjF9.invalid")) {
            assertThat(body(get(path()).header("Authorization", "Bearer " + bearer), 200)
                    .path("restaurant").path("name").asText()).isEqualTo("Public Restaurant");
            body(get(path() + "/items").header("Authorization", "Bearer " + bearer), 200);
        }
    }

    @Test void internalRoutesAndOtherMethodsRemainProtected() throws Exception {
        for (var request : List.of(get("/api/v1/menu/items"), get("/api/v1/tables"), post(path()), patch(path()),
                delete(path()), get(path() + "/extra"), get("/api/v1/admin/restaurants"), head(path()))) {
            assertThat(call(request).getStatus()).isIn(401, 403);
        }
    }

    @Test void readsCreateNoSessionsAndDoNotMutateVersionSnapshotOrAudit() throws Exception {
        var before = jdbc.queryForMap("select version, updated_at from restaurant_tables where id=?", table.getId());
        var snapshot = subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot();
        var audits = jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=?", Long.class, restaurant.getId());
        body(get(path()), 200); body(get(path() + "/items"), 200);
        assertThat(jdbc.queryForMap("select version, updated_at from restaurant_tables where id=?", table.getId())).isEqualTo(before);
        assertThat(subscriptions.findById(subscription.getId()).orElseThrow().getFeatureSnapshot()).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where restaurant_id=?", Long.class, restaurant.getId())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=?", Long.class, restaurant.getId())).isEqualTo(audits);
    }

    @Test void occupiedTableAndInactiveStaffBearerDoNotBlockPublicMenu() throws Exception {
        var user = new com.possaas.modules.user.entity.User(); user.setRestaurantId(restaurant.getId());
        user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Fixture owner"); user.setEmail(UUID.randomUUID() + "@example.test");
        user.setPasswordHash("unused-test-password-hash"); user.setActive(false); user = users.saveAndFlush(user);
        var session = new TableSession(); session.initialize(restaurant.getId(), NOW); session.setTableId(table.getId());
        session.setSessionCode("TEST-OPEN"); session.setOpenedBy(user.getId()); session.setOpenedAt(NOW);
        session = sessions.saveAndFlush(session);
        var bearer = jwt.createAccessToken(new CurrentUser(user.getId(), restaurant.getId(), user.getEmail(), "OWNER", Set.of()));
        body(get(path()).header("Authorization", "Bearer " + bearer), 200);
        body(get(path() + "/items").header("Authorization", "Bearer " + bearer), 200);
        assertThat(sessions.findById(session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(call(get("/api/v1/tables").header("Authorization", "Bearer " + bearer)).getStatus()).isEqualTo(401);
    }

    @Test void genuinelySignedExpiredBearerIsIgnored() throws Exception {
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().issuer("pos-saas-be")
                .subject(UUID.randomUUID().toString()).issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();
        var header = org.springframework.security.oauth2.jwt.JwsHeader.with(org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build();
        String token = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(header, claims)).getTokenValue();
        body(get(path()).header("Authorization", "Bearer " + token), 200);
        body(get(path() + "/items").header("Authorization", "Bearer " + token), 200);
    }

    @Test void corsKeepsConfiguredOriginBoundary() throws Exception {
        var allowed = call(get(path()).header("Origin", "http://localhost:3000"));
        assertThat(allowed.getStatus()).isEqualTo(200);
        assertThat(allowed.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        var forbidden = call(get(path()).header("Origin", "https://untrusted.example"));
        assertThat(forbidden.getStatus()).isEqualTo(403);
        assertThat(forbidden.getHeader("Access-Control-Allow-Origin")).isNull();
        var preflight = call(options(path() + "/items").header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "GET").header("Access-Control-Request-Headers", "Accept"));
        assertThat(preflight.getStatus()).isEqualTo(200);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
    }

    @Test void queryCountDoesNotGrowPerItemGroup() throws Exception {
        for (int i = 0; i < 20; i++) item("Item " + i, group("Group " + i, i).getId());
        var statistics = emf.unwrap(SessionFactory.class).getStatistics(); statistics.setStatisticsEnabled(true);
        try {
            statistics.clear(); body(get(path() + "/items").param("size", "5"), 200);
            long small = statistics.getPrepareStatementCount();
            statistics.clear(); body(get(path() + "/items").param("size", "20"), 200);
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(small).isLessThanOrEqualTo(8);
        } finally { statistics.setStatisticsEnabled(false); }
    }

    @Test void unexpectedDatabaseFailureIs500NotFakeFeatureDenial(CapturedOutput output) throws Exception {
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("sensitive " + table.getQrToken()))
                .when(items).findAll(any(Specification.class), any(Pageable.class));
        error(get(path() + "/items"), 500, "INTERNAL_ERROR");
        assertThat(output.getAll()).doesNotContain(table.getQrToken());
    }

    @Test void rejectedTokenIsNotLoggedOrEchoed(CapturedOutput output) throws Exception {
        error(get(path() + "/items").param("groupId", table.getQrToken()), 400, "VALIDATION_ERROR");
        String old = table.getQrToken(); table.setQrToken(qrTokens.generate()); tables.saveAndFlush(table);
        var result = call(get(BASE + old));
        assertThat(result.getStatus()).isEqualTo(404);
        assertThat(result.getContentAsString()).doesNotContain(old);
        assertThat(output.getAll()).doesNotContain(old);
    }

    private ItemGroup group(String name, int order) {
        var value = new ItemGroup(); value.initialize(restaurant.getId(), NOW); value.setName(name); value.setDisplayOrder(order);
        return groups.saveAndFlush(value);
    }
    private Item item(String name, UUID groupId) {
        var value = new Item(); value.initialize(restaurant.getId(), NOW); value.setName(name); value.setGroupId(groupId);
        value.setItemType(ItemType.MENU_ITEM); value.setBaseUnit("portion"); value.setSalePrice(new BigDecimal("50000.00"));
        value.setCostPrice(new BigDecimal("12000.00")); value.setSku(UUID.randomUUID().toString());
        value.setMetadata(Map.of("private", "not public")); return items.saveAndFlush(value);
    }
    private String path() { return BASE + table.getQrToken(); }
    private org.springframework.mock.web.MockHttpServletResponse call(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request).andReturn().getResponse();
    }
    private JsonNode body(MockHttpServletRequestBuilder request, int status) throws Exception {
        var response = call(request); assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(status);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return json.readTree(response.getContentAsString());
    }
    private void error(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        var result = body(request, status); assertThat(result.path("code").asText()).isEqualTo(code);
        assertThat(result.toString()).doesNotContain(table.getQrToken(), "stackTrace", "SQLException");
    }
    private Set<String> fields(JsonNode node) {
        var names = new HashSet<String>(); node.fieldNames().forEachRemaining(names::add); return names;
    }
}
