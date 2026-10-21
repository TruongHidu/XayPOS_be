package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.jpa.show-sql=false",
        "app.jobs.subscription-expiration.enabled=false",
        "app.bootstrap.super-admin.enabled=false",
        "logging.level.root=WARN",
        "app.cors.allowed-origins=http://localhost:3000"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IngredientIntegrationTest {
    private static final String SCHEMA = "it_ingredient_" + UUID.randomUUID().toString().replace("-", "");
    private static final String BASE = "/api/v1/menu/ingredients";
    private static final Set<String> ALL = Set.of("INGREDIENT_VIEW", "INGREDIENT_CREATE", "INGREDIENT_UPDATE",
            "INGREDIENT_DELETE");

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
    @Autowired ItemRepository items;
    @Autowired Clock clock;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_ingredient_[0-9a-f]{32}"))
            throw new IllegalStateException("Not an ingredient test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void createUsesIngredientStrategyDefaultsAllowlistAndAudit() throws Exception {
        User owner = fixture("PRO", Set.of("RECIPE_MANAGEMENT"), true);
        Result minimal = call(post(BASE).content("{\"name\":\" Salt \",\"baseUnit\":\" g \"}"), owner, ALL);
        minimal.expect(201);
        assertThat(minimal.body.path("name").asText()).isEqualTo("Salt");
        assertThat(minimal.body.path("baseUnit").asText()).isEqualTo("g");
        assertThat(minimal.body.path("costPrice").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(minimal.body.path("trackInventory").asBoolean()).isFalse();
        assertThat(minimal.body.path("active").asBoolean()).isTrue();
        assertThat(minimal.body.toString()).doesNotContain("restaurantId", "itemType", "group", "groupId",
                "salePrice", "imageUrl", "availabilityStatus", "metadata", "deletedAt");
        Item saved = items.findById(minimal.id()).orElseThrow();
        assertThat(saved.getItemType()).isEqualTo(ItemType.INGREDIENT);
        assertThat(saved.getGroupId()).isNull();
        assertThat(saved.getSalePrice()).isEqualByComparingTo("0.00");
        assertThat(saved.getAvailabilityStatus()).isEqualTo(AvailabilityStatus.AVAILABLE);

        Result full = call(post(BASE).content("""
                {"sku":" nl-beef ","name":"Beef","baseUnit":"g","description":"Fresh",
                 "costPrice":250.00,"trackInventory":true,"active":false}
                """), owner, ALL);
        full.expect(201);
        assertThat(full.body.path("sku").asText()).isEqualTo("NL-BEEF");
        assertThat(full.body.path("costPrice").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(full.body.path("trackInventory").asBoolean()).isTrue();
        assertThat(full.body.path("active").asBoolean()).isFalse();
        assertThat(events(owner, "INGREDIENT_CREATED")).isEqualTo(2);

        call(post(BASE).content("{\"name\":\"Bad\",\"baseUnit\":\"g\",\"itemType\":\"MENU_ITEM\"}"),
                owner, ALL).error(400, "INVALID_REQUEST_BODY");
        assertThat(events(owner, "INGREDIENT_CREATED")).isEqualTo(2);
    }

    @Test
    void listSearchFiltersSortsAndKeepsMenuBoundary() throws Exception {
        User owner = fixture("PRO", Set.of("RECIPE_MANAGEMENT", "MENU_MANAGEMENT"), true);
        Result literal = create(owner, "A%_1", "Sugar %_", true, true);
        create(owner, "A002", "Sugar regular", false, false);
        Item menu = menuItem(owner.getRestaurantId(), "MENU-ONLY", "Coffee");

        Result escaped = call(get(BASE).param("q", "%_").param("sortBy", "costPrice"), owner, ALL);
        escaped.expect(200);
        assertThat(escaped.body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(escaped.body.at("/content/0/id").asText()).isEqualTo(literal.id().toString());
        assertThat(call(get(BASE).param("active", "false"), owner, ALL).body.path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(call(get(BASE).param("trackInventory", "true"), owner, ALL).body.path("totalElements").asInt())
                .isEqualTo(1);
        assertThat(call(get(BASE).param("size", "1").param("page", "1").param("sortBy", "name")
                .param("direction", "asc"), owner, ALL).body.path("content").size()).isEqualTo(1);
        assertThat(call(get("/api/v1/menu/items").param("size", "100"), owner, Set.of("MENU_VIEW"))
                .body.path("content").toString()).contains(menu.getId().toString()).doesNotContain(literal.id().toString());
        call(get(BASE + "/" + menu.getId()), owner, ALL).error(404, "INGREDIENT_NOT_FOUND");
    }

    @Test
    void updateStatusAndDeleteUseVersionAndIdempotentAudit() throws Exception {
        User owner = fixture("PREMIUM", Set.of("INVENTORY_MANAGEMENT"), true);
        Result ingredient = create(owner, "EDIT", "Editable", false, true);
        Result changed = call(patch(BASE + "/" + ingredient.id()).content("""
                {"costPrice":88.25,"trackInventory":true,"sku":"","description":"","expectedVersion":0}
                """), owner, ALL);
        changed.expect(200);
        assertThat(changed.body.path("sku").isNull()).isTrue();
        assertThat(changed.body.path("description").isNull()).isTrue();
        assertThat(changed.body.path("version").asLong()).isEqualTo(1);
        assertThat(events(owner, "INGREDIENT_UPDATED")).isEqualTo(1);
        call(patch(BASE + "/" + ingredient.id()).content("{\"expectedVersion\":1}"), owner, ALL)
                .error(400, "EMPTY_UPDATE_REQUEST");
        call(patch(BASE + "/" + ingredient.id()).content("{\"name\":\"Stale\",\"expectedVersion\":0}"),
                owner, ALL).error(409, "CONCURRENT_INGREDIENT_UPDATE");

        Result disabled = call(patch(BASE + "/" + ingredient.id() + "/status")
                .content("{\"active\":false,\"expectedVersion\":1}"), owner, ALL);
        disabled.expect(200);
        call(patch(BASE + "/" + ingredient.id() + "/status")
                .content("{\"active\":false,\"expectedVersion\":0}"), owner, ALL).expect(200);
        assertThat(events(owner, "INGREDIENT_DISABLED")).isEqualTo(1);

        call(delete(BASE + "/" + ingredient.id()).param("expectedVersion", "2"), owner, ALL).expect(204);
        call(delete(BASE + "/" + ingredient.id()).param("expectedVersion", "0"), owner, ALL).expect(204);
        assertThat(events(owner, "INGREDIENT_DELETED")).isEqualTo(1);
        call(get(BASE + "/" + ingredient.id()), owner, ALL).error(404, "INGREDIENT_NOT_FOUND");
        assertThat(call(get(BASE).param("q", "Editable"), owner, ALL).body.path("totalElements").asLong()).isZero();
    }

    @Test
    void skuTenantPermissionFeatureAndSubscriptionRulesAreIndependent() throws Exception {
        User first = fixture("PRO", Set.of("RECIPE_MANAGEMENT"), true);
        User second = fixture("PREMIUM", Set.of("INVENTORY_MANAGEMENT"), true);
        Result ingredient = create(first, "SHARED", "First", false, true);
        create(second, "SHARED", "Second", false, true);
        call(get(BASE + "/" + ingredient.id()), second, ALL).error(404, "INGREDIENT_NOT_FOUND");
        call(patch(BASE + "/" + ingredient.id()).content("{\"name\":\"Other\",\"expectedVersion\":0}"),
                second, ALL).error(404, "INGREDIENT_NOT_FOUND");

        call(post(BASE).content(body("SHARED", "Duplicate", false, true)), first, ALL).error(409, "SKU_EXISTS");
        menuItem(first.getRestaurantId(), "MENU-SKU", "Menu");
        call(post(BASE).content(body("MENU-SKU", "Collision", false, true)), first, ALL).error(409, "SKU_EXISTS");

        call(get(BASE), first, Set.of()).error(403, "FORBIDDEN");
        call(post(BASE).content(body("DENIED", "Denied", false, true)), first, Set.of("INGREDIENT_VIEW"))
                .error(403, "FORBIDDEN");
        call(patch(BASE + "/" + ingredient.id()).content("{\"name\":\"Denied\",\"expectedVersion\":0}"),
                first, Set.of("INGREDIENT_VIEW")).error(403, "FORBIDDEN");
        call(delete(BASE + "/" + ingredient.id()).param("expectedVersion", "0"), first,
                Set.of("INGREDIENT_VIEW")).error(403, "FORBIDDEN");

        User noFeature = fixture("BASIC", Set.of(), true);
        call(get(BASE), noFeature, ALL).error(403, "FEATURE_NOT_ENTITLED");
        User inactiveSubscription = fixture("PRO", Set.of("RECIPE_MANAGEMENT"), false);
        call(get(BASE), inactiveSubscription, ALL).error(403, "SUBSCRIPTION_NOT_ACTIVE");
        CurrentUser system = new CurrentUser(UUID.randomUUID(), null, "admin@example.com", "SUPER_ADMIN", ALL);
        call(get(BASE), system).error(403, "TENANT_ACCESS_DENIED");
    }

    @Test
    void invalidMoneyAndReadOnlyFieldsAreRejectedWithoutAudit() throws Exception {
        User owner = fixture("PRO", Set.of("RECIPE_MANAGEMENT"), true);
        for (String price : List.of("-1", "1.001", "1000000000000"))
            call(post(BASE).content("{\"name\":\"Bad\",\"baseUnit\":\"g\",\"costPrice\":" + price + "}"),
                    owner, ALL).error(400, "VALIDATION_ERROR");
        for (String forbidden : List.of("\"restaurantId\":\"" + owner.getRestaurantId() + "\"",
                "\"groupId\":null", "\"salePrice\":1", "\"imageUrl\":null",
                "\"availabilityStatus\":\"AVAILABLE\"", "\"metadata\":{}"))
            call(post(BASE).content("{\"name\":\"Bad\",\"baseUnit\":\"g\"," + forbidden + "}"), owner, ALL)
                    .error(400, "INVALID_REQUEST_BODY");
        assertThat(events(owner, "INGREDIENT_CREATED")).isZero();
    }

    @Test
    void concurrentUpdatesHaveOneWinner() throws Exception {
        User owner = fixture("PRO", Set.of("RECIPE_MANAGEMENT"), true);
        Result ingredient = create(owner, "RACE", "Race", false, true);
        List<Result> results = race(
                () -> call(patch(BASE + "/" + ingredient.id())
                        .content("{\"name\":\"Winner A\",\"expectedVersion\":0}"), owner, ALL),
                () -> call(patch(BASE + "/" + ingredient.id())
                        .content("{\"name\":\"Winner B\",\"expectedVersion\":0}"), owner, ALL));
        assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(events(owner, "INGREDIENT_UPDATED")).isEqualTo(1);
    }

    private Result create(User user, String sku, String name, boolean trackInventory, boolean active) throws Exception {
        Result result = call(post(BASE).content(body(sku, name, trackInventory, active)), user, ALL);
        result.expect(201);
        return result;
    }

    private String body(String sku, String name, boolean trackInventory, boolean active) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sku", sku);
        body.put("name", name);
        body.put("baseUnit", "g");
        body.put("costPrice", "10.00");
        body.put("trackInventory", trackInventory);
        body.put("active", active);
        return json.writeValueAsString(body);
    }

    private Item menuItem(UUID restaurantId, String sku, String name) {
        Item item = new Item();
        item.initialize(restaurantId, clock.instant());
        item.setItemType(ItemType.MENU_ITEM);
        item.setSku(sku);
        item.setName(name);
        item.setBaseUnit("portion");
        item.setSalePrice(new BigDecimal("100.00"));
        item.setCostPrice(BigDecimal.ZERO);
        item.setTrackInventory(false);
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        return items.saveAndFlush(item);
    }

    private User fixture(String packageCode, Set<String> featureCodes, boolean activeSubscription) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("ING" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Ingredient test");
        restaurant = restaurants.saveAndFlush(restaurant);
        User user = new User();
        user.setRestaurantId(restaurant.getId());
        user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Owner");
        user.setEmail(UUID.randomUUID() + "@example.com");
        user.setPasswordHash("fixture-only");
        user = users.saveAndFlush(user);
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode(packageCode).orElseThrow().getId());
        subscription.setStatus(activeSubscription ? SubscriptionStatus.ACTIVE : SubscriptionStatus.CANCELLED);
        subscription.setStartAt(clock.instant().minusSeconds(30));
        subscription.setEndAt(clock.instant().plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, packageCode,
                featureCodes.stream().map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(),
                clock.instant()).toMap());
        subscriptions.saveAndFlush(subscription);
        return user;
    }

    private Result call(MockHttpServletRequestBuilder request, User user, Set<String> permissions) throws Exception {
        String role = jdbc.queryForObject("select r.code from roles r join users u on u.role_id=r.id where u.id=?",
                String.class, user.getId());
        return call(request, new CurrentUser(user.getId(), user.getRestaurantId(), user.getEmail(), role, permissions));
    }

    private Result call(MockHttpServletRequestBuilder request, CurrentUser principal) throws Exception {
        var authorities = principal.permissions().stream().map(SimpleGrantedAuthority::new).toList();
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        var response = mvc.perform(request.contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON)
                .with(authentication(auth))).andReturn().getResponse();
        return new Result(response.getStatus(), response.getContentAsString().isBlank()
                ? json.createObjectNode() : json.readTree(response.getContentAsString()));
    }

    private long events(User user, String action) {
        return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",
                Long.class, user.getRestaurantId(), action);
    }

    private List<Result> race(Callable<Result> first, Callable<Result> second) throws Exception {
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { ready.countDown(); go.await(10, TimeUnit.SECONDS); return first.call(); });
            var two = pool.submit(() -> { ready.countDown(); go.await(10, TimeUnit.SECONDS); return second.call(); });
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
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
