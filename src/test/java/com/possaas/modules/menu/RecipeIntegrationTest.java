package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.dao.DataIntegrityViolationException;
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
class RecipeIntegrationTest {
    private static final String SCHEMA = "it_recipe_" + UUID.randomUUID().toString().replace("-", "");
    private static final Set<String> RECIPE_PERMISSIONS = Set.of(
            "RECIPE_VIEW", "RECIPE_CREATE", "RECIPE_UPDATE", "RECIPE_DELETE");

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
        if (!SCHEMA.matches("it_recipe_[0-9a-f]{32}"))
            throw new IllegalStateException("Not a recipe test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void createsInitialRecipeForInactiveMenuAndReadsSnapshotHistory() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "PHO", "Pho", "portion", false);
        Item beef = ingredient(owner.getRestaurantId(), "BEEF", "Beef", "g", true, false);
        Item noodle = ingredient(owner.getRestaurantId(), "NOODLE", "Noodle", "g", true, true);

        Result created = call(post(base(menu)).content(recipeBody("10.000",
                line(beef, "1200.000", "5.00"), line(noodle, "1800.000", null))), owner,
                RECIPE_PERMISSIONS);
        created.expect(201);
        assertThat(created.body.path("recipeVersion").asInt()).isEqualTo(1);
        assertThat(created.body.path("version").asLong()).isZero();
        assertThat(created.body.path("yieldUnit").asText()).isEqualTo("portion");
        assertThat(created.body.path("active").asBoolean()).isTrue();
        assertThat(created.body.path("ingredients").size()).isEqualTo(2);
        assertThat(created.body.toString()).doesNotContain("restaurantId", "costPrice", "trackInventory");
        assertThat(events(owner, "RECIPE_CREATED")).isEqualTo(1);

        Result active = call(get(base(menu) + "/active"), owner, RECIPE_PERMISSIONS);
        active.expect(200);
        assertThat(active.id()).isEqualTo(created.id());
        Result detail = call(get(base(menu) + "/" + created.id()), owner, RECIPE_PERMISSIONS);
        detail.expect(200);
        assertThat(detail.body.path("ingredients").toString()).contains("Beef", "BEEF", "g");
        Result history = call(get(base(menu)).param("sortBy", "recipeVersion").param("direction", "desc"),
                owner, RECIPE_PERMISSIONS);
        history.expect(200);
        assertThat(history.body.at("/content/0/ingredientCount").asLong()).isEqualTo(2);
        assertThat(history.body.path("totalElements").asLong()).isEqualTo(1);
    }

    @Test
    void validatesMenuIngredientTenantAggregateAndReadOnlyFields() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        User other = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "DISH", "Dish", "plate", true);
        Item ingredient = ingredient(owner.getRestaurantId(), "SALT", "Salt", "g", true, false);
        Item foreign = ingredient(other.getRestaurantId(), "FOREIGN", "Foreign", "g", true, true);
        Item inactive = ingredient(owner.getRestaurantId(), "OFF", "Off", "g", false, true);
        Item deleted = ingredient(owner.getRestaurantId(), "DELETED", "Deleted", "g", true, true);
        deleted.setDeletedAt(clock.instant());
        items.saveAndFlush(deleted);
        Item wrongType = menuItem(owner.getRestaurantId(), "WRONG", "Wrong", "unit", true);

        call(get(base(menu) + "/active"), owner, RECIPE_PERMISSIONS)
                .error(404, "ACTIVE_RECIPE_NOT_FOUND");
        call(post(base(menu)).content(recipeBody("1", line(foreign, "1", "0"))), owner,
                RECIPE_PERMISSIONS).error(404, "INGREDIENT_NOT_FOUND");
        call(post(base(menu)).content(recipeBody("1", line(wrongType, "1", "0"))), owner,
                RECIPE_PERMISSIONS).error(404, "INGREDIENT_NOT_FOUND");
        call(post(base(menu)).content(recipeBody("1", line(deleted, "1", "0"))), owner,
                RECIPE_PERMISSIONS).error(404, "INGREDIENT_NOT_FOUND");
        call(post(base(menu)).content(recipeBody("1", line(inactive, "1", "0"))), owner,
                RECIPE_PERMISSIONS).error(409, "INGREDIENT_INACTIVE");
        call(post(base(menu)).content("{\"yieldQuantity\":1,\"ingredients\":[]}"), owner,
                RECIPE_PERMISSIONS).error(400, "VALIDATION_ERROR");
        String tooMany = java.util.stream.IntStream.range(0, 101)
                .mapToObj(index -> line(UUID.nameUUIDFromBytes(("recipe-line-" + index)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)), "1", "0"))
                .collect(java.util.stream.Collectors.joining(","));
        call(post(base(menu)).content("{\"yieldQuantity\":1,\"ingredients\":[" + tooMany + "]}"), owner,
                RECIPE_PERMISSIONS).error(400, "VALIDATION_ERROR");
        call(post(base(menu)).content(recipeBody("1", line(ingredient, "1", "0"),
                line(ingredient, "2", "0"))), owner, RECIPE_PERMISSIONS)
                .error(409, "DUPLICATE_RECIPE_INGREDIENT");
        call(post(base(menu)).content("{\"yieldQuantity\":1,\"ingredients\":["
                + line(ingredient, "1", "0") + "],\"active\":true}"), owner,
                RECIPE_PERMISSIONS).error(400, "INVALID_REQUEST_BODY");

        for (String invalidYield : List.of("0", "-1", "1.0001"))
            call(post(base(menu)).content(recipeBody(invalidYield, line(ingredient, "1", "0"))), owner,
                    RECIPE_PERMISSIONS).error(400, "VALIDATION_ERROR");
        for (String invalidQuantity : List.of("0", "-1", "1.0001"))
            call(post(base(menu)).content(recipeBody("1", line(ingredient, invalidQuantity, "0"))), owner,
                    RECIPE_PERMISSIONS).error(400, "VALIDATION_ERROR");
        for (String invalidWaste : List.of("-1", "100.01", "1.001"))
            call(post(base(menu)).content(recipeBody("1", line(ingredient, "1", invalidWaste))), owner,
                    RECIPE_PERMISSIONS).error(400, "VALIDATION_ERROR");

        call(get(base(menu)), other, RECIPE_PERMISSIONS).error(404, "MENU_ITEM_NOT_FOUND");
        call(get(base(ingredient)), owner, RECIPE_PERMISSIONS).error(404, "MENU_ITEM_NOT_FOUND");
        menu.setDeletedAt(clock.instant());
        items.saveAndFlush(menu);
        call(get(base(menu)), owner, RECIPE_PERMISSIONS).error(404, "MENU_ITEM_NOT_FOUND");
        assertThat(events(owner, "RECIPE_CREATED")).isZero();
    }

    @Test
    void createsImmutableVersionsAndRejectsSameOrderOnlyStaleAndInactiveSource() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "SOUP", "Soup", "bowl", true);
        Item first = ingredient(owner.getRestaurantId(), "A", "Original A", "g", true, true);
        Item second = ingredient(owner.getRestaurantId(), "B", "Original B", "ml", true, true);
        Result v1 = call(post(base(menu)).content(recipeBody("2", line(first, "10", "1"),
                line(second, "20", "0"))), owner, RECIPE_PERMISSIONS);
        v1.expect(201);

        first.setName("Renamed A");
        first.setSku("A-NEW");
        items.saveAndFlush(first);
        Result oldBeforeVersion = call(get(base(menu) + "/" + v1.id()), owner, RECIPE_PERMISSIONS);
        assertThat(oldBeforeVersion.body.path("ingredients").toString()).contains("Original A", "\"A\"")
                .doesNotContain("Renamed A", "A-NEW");

        Result v2 = call(post(base(menu) + "/" + v1.id() + "/versions").content(versionBody(0, "2",
                line(second, "20", "0"), line(first, "12", "1"))), owner, RECIPE_PERMISSIONS);
        v2.expect(201);
        assertThat(v2.body.path("recipeVersion").asInt()).isEqualTo(2);
        assertThat(v2.body.path("active").asBoolean()).isTrue();
        Result old = call(get(base(menu) + "/" + v1.id()), owner, RECIPE_PERMISSIONS);
        assertThat(old.body.path("active").asBoolean()).isFalse();
        assertThat(old.body.at("/ingredients/0/quantity").decimalValue()
                .add(old.body.at("/ingredients/1/quantity").decimalValue())).isEqualByComparingTo("30");
        assertThat(events(owner, "RECIPE_VERSION_CREATED")).isEqualTo(1);

        call(post(base(menu) + "/" + v2.id() + "/versions").content(versionBody(0, "2",
                line(first, "12", "1"), line(second, "20", "0"))), owner, RECIPE_PERMISSIONS)
                .error(409, "SAME_RECIPE_VERSION_NOT_ALLOWED");
        call(post(base(menu) + "/" + v2.id() + "/versions").content(versionBody(1, "3",
                line(first, "12", "1"), line(second, "20", "0"))), owner, RECIPE_PERMISSIONS)
                .error(409, "CONCURRENT_RECIPE_UPDATE");
        call(post(base(menu) + "/" + v1.id() + "/versions").content(versionBody(1, "3",
                line(first, "12", "1"), line(second, "20", "0"))), owner, RECIPE_PERMISSIONS)
                .error(409, "RECIPE_NOT_ACTIVE");
        call(post(base(menu)).content(recipeBody("1", line(first, "1", "0"))), owner,
                RECIPE_PERMISSIONS).error(409, "ACTIVE_RECIPE_ALREADY_EXISTS");
    }

    @Test
    void deactivateIsIdempotentAndOldRetryNeverAffectsNewActiveRecipe() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "CURRY", "Curry", "plate", true);
        Item spice = ingredient(owner.getRestaurantId(), "SPICE", "Spice", "g", true, true);
        Result v1 = call(post(base(menu)).content(recipeBody("1", line(spice, "5", "0"))), owner,
                RECIPE_PERMISSIONS);
        v1.expect(201);
        call(delete(base(menu) + "/" + v1.id()).param("expectedVersion", "0"), owner,
                RECIPE_PERMISSIONS).expect(204);
        call(delete(base(menu) + "/" + v1.id()).param("expectedVersion", "999"), owner,
                RECIPE_PERMISSIONS).expect(204);
        assertThat(events(owner, "RECIPE_DEACTIVATED")).isEqualTo(1);

        Result v2 = call(post(base(menu)).content(recipeBody("1", line(spice, "6", "0"))), owner,
                RECIPE_PERMISSIONS);
        v2.expect(201);
        assertThat(v2.body.path("recipeVersion").asInt()).isEqualTo(2);
        call(delete(base(menu) + "/" + v1.id()).param("expectedVersion", "0"), owner,
                RECIPE_PERMISSIONS).expect(204);
        assertThat(call(get(base(menu) + "/active"), owner, RECIPE_PERMISSIONS).id()).isEqualTo(v2.id());
    }

    @Test
    void activeRecipeGuardsIngredientAndMenuChangesButInactiveHistoryDoesNot() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT", "MENU_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "GUARD-M", "Guard menu", "plate", true);
        Item ingredient = ingredient(owner.getRestaurantId(), "GUARD-I", "Guard ingredient", "g", true, true);
        Result recipe = call(post(base(menu)).content(recipeBody("1", line(ingredient, "5", "0"))), owner,
                RECIPE_PERMISSIONS);
        recipe.expect(201);

        call(patch("/api/v1/menu/ingredients/" + ingredient.getId() + "/status")
                .content("{\"active\":false,\"expectedVersion\":0}"), owner, Set.of("INGREDIENT_UPDATE"))
                .error(409, "INGREDIENT_USED_BY_ACTIVE_RECIPE");
        call(patch("/api/v1/menu/ingredients/" + ingredient.getId())
                .content("{\"baseUnit\":\"kg\",\"expectedVersion\":0}"), owner, Set.of("INGREDIENT_UPDATE"))
                .error(409, "INGREDIENT_UNIT_IN_USE");
        call(delete("/api/v1/menu/ingredients/" + ingredient.getId()).param("expectedVersion", "0"), owner,
                Set.of("INGREDIENT_DELETE")).error(409, "INGREDIENT_USED_BY_ACTIVE_RECIPE");
        call(patch("/api/v1/menu/items/" + menu.getId())
                .content("{\"baseUnit\":\"tray\",\"expectedVersion\":0}"), owner, Set.of("MENU_UPDATE"))
                .error(409, "MENU_ITEM_UNIT_IN_USE");
        call(delete("/api/v1/menu/items/" + menu.getId()).param("expectedVersion", "0"), owner,
                Set.of("MENU_DELETE")).error(409, "MENU_ITEM_HAS_ACTIVE_RECIPE");

        call(delete(base(menu) + "/" + recipe.id()).param("expectedVersion", "0"), owner,
                RECIPE_PERMISSIONS).expect(204);
        call(patch("/api/v1/menu/ingredients/" + ingredient.getId())
                .content("{\"baseUnit\":\"kg\",\"expectedVersion\":0}"), owner, Set.of("INGREDIENT_UPDATE"))
                .expect(200);
        call(delete("/api/v1/menu/ingredients/" + ingredient.getId()).param("expectedVersion", "1"), owner,
                Set.of("INGREDIENT_DELETE")).expect(204);
        Result history = call(get(base(menu) + "/" + recipe.id()), owner, RECIPE_PERMISSIONS);
        history.expect(200);
        assertThat(history.body.at("/ingredients/0/unit").asText()).isEqualTo("g");
    }

    @Test
    void permissionFeatureSubscriptionAndTenantSecurityAreIndependent() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "SEC", "Sec", "unit", true);
        Item ingredient = ingredient(owner.getRestaurantId(), "SEC-I", "Sec I", "g", true, true);
        call(get(base(menu)), owner, Set.of()).error(403, "FORBIDDEN");
        call(post(base(menu)).content(recipeBody("1", line(ingredient, "1", "0"))), owner,
                Set.of("RECIPE_VIEW")).error(403, "FORBIDDEN");

        User inventoryOnly = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Item inventoryMenu = menuItem(inventoryOnly.getRestaurantId(), "INV", "Inv", "unit", true);
        call(get(base(inventoryMenu)), inventoryOnly, RECIPE_PERMISSIONS).error(403, "FEATURE_NOT_ENTITLED");
        User inactive = fixture(Set.of("RECIPE_MANAGEMENT"), false);
        Item inactiveMenu = menuItem(inactive.getRestaurantId(), "OFF-SUB", "Off sub", "unit", true);
        call(get(base(inactiveMenu)), inactive, RECIPE_PERMISSIONS).error(403, "SUBSCRIPTION_NOT_ACTIVE");
        CurrentUser system = new CurrentUser(UUID.randomUUID(), null, "admin@example.com", "SUPER_ADMIN",
                RECIPE_PERMISSIONS);
        call(get(base(menu)), system).error(403, "TENANT_ACCESS_DENIED");
    }

    @Test
    void databaseEnforcesOneActiveAndUniqueBusinessVersion() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "DB", "DB", "unit", true);
        Item ingredient = ingredient(owner.getRestaurantId(), "DB-I", "DB I", "g", true, true);
        Result created = call(post(base(menu)).content(recipeBody("1", line(ingredient, "1", "0"))), owner,
                RECIPE_PERMISSIONS);
        created.expect(201);
        String insert = "insert into recipes(restaurant_id,menu_item_id,recipe_version,yield_quantity,yield_unit,"
                + "is_active,lock_version,created_at,updated_at) values (?,?,?,?,?, ?,0,now(),now())";
        assertThatThrownBy(() -> jdbc.update(insert, owner.getRestaurantId(), menu.getId(), 2, 1, "unit", true))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(insert, owner.getRestaurantId(), menu.getId(), 1, 1, "unit", false))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentCreateAndCreateVersionHaveOneWinner() throws Exception {
        User owner = fixture(Set.of("RECIPE_MANAGEMENT"), true);
        Item menu = menuItem(owner.getRestaurantId(), "RACE", "Race", "unit", true);
        Item ingredient = ingredient(owner.getRestaurantId(), "RACE-I", "Race I", "g", true, true);
        String initial = recipeBody("1", line(ingredient, "1", "0"));
        List<Result> createResults = race(
                () -> call(post(base(menu)).content(initial), owner, RECIPE_PERMISSIONS),
                () -> call(post(base(menu)).content(initial), owner, RECIPE_PERMISSIONS));
        assertThat(createResults.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
        Result active = call(get(base(menu) + "/active"), owner, RECIPE_PERMISSIONS);
        List<Result> versionResults = race(
                () -> call(post(base(menu) + "/" + active.id() + "/versions")
                        .content(versionBody(0, "1", line(ingredient, "2", "0"))), owner, RECIPE_PERMISSIONS),
                () -> call(post(base(menu) + "/" + active.id() + "/versions")
                        .content(versionBody(0, "1", line(ingredient, "3", "0"))), owner, RECIPE_PERMISSIONS));
        assertThat(versionResults.stream().map(Result::status)).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from recipes where restaurant_id=? and menu_item_id=? and is_active",
                Long.class, owner.getRestaurantId(), menu.getId())).isEqualTo(1);
    }

    private String base(Item menu) {
        return "/api/v1/menu/items/" + menu.getId() + "/recipes";
    }

    private String line(Item ingredient, String quantity, String waste) {
        return line(ingredient.getId(), quantity, waste);
    }

    private String line(UUID ingredientId, String quantity, String waste) {
        return "{\"ingredientId\":\"" + ingredientId + "\",\"quantity\":" + quantity
                + (waste == null ? "" : ",\"wastePercent\":" + waste) + "}";
    }

    private String recipeBody(String yield, String... lines) {
        return "{\"yieldQuantity\":" + yield + ",\"ingredients\":[" + String.join(",", lines) + "]}";
    }

    private String versionBody(long expectedVersion, String yield, String... lines) {
        return "{\"expectedVersion\":" + expectedVersion + ",\"yieldQuantity\":" + yield
                + ",\"ingredients\":[" + String.join(",", lines) + "]}";
    }

    private Item menuItem(UUID restaurantId, String sku, String name, String unit, boolean active) {
        return item(restaurantId, sku, name, unit, ItemType.MENU_ITEM, active, false);
    }

    private Item ingredient(UUID restaurantId, String sku, String name, String unit, boolean active,
            boolean trackInventory) {
        return item(restaurantId, sku, name, unit, ItemType.INGREDIENT, active, trackInventory);
    }

    private Item item(UUID restaurantId, String sku, String name, String unit, ItemType type, boolean active,
            boolean trackInventory) {
        Item item = new Item();
        item.initialize(restaurantId, clock.instant());
        item.setItemType(type);
        item.setSku(sku);
        item.setName(name);
        item.setBaseUnit(unit);
        item.setSalePrice(type == ItemType.MENU_ITEM ? new BigDecimal("100.00") : BigDecimal.ZERO);
        item.setCostPrice(BigDecimal.ZERO);item.setCostConfigured(true);
        item.setTrackInventory(trackInventory);
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        item.setActive(active);
        return items.saveAndFlush(item);
    }

    private User fixture(Set<String> featureCodes, boolean activeSubscription) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("REC" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Recipe test");
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
        subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(activeSubscription ? SubscriptionStatus.ACTIVE : SubscriptionStatus.CANCELLED);
        subscription.setStartAt(clock.instant().minusSeconds(30));
        subscription.setEndAt(clock.instant().plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO",
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
