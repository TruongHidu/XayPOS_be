package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.repository.RecipeItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.resource.jdbc.spi.StatementInspector;
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
    "app.cors.allowed-origins=http://localhost:3000",
    "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "com.possaas.modules.menu.RecipeCostEstimateIntegrationTest$SqlCapture"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(RecipeCostEstimateIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RecipeCostEstimateIntegrationTest {
    private static final String SCHEMA = "it_recipe_cost_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00Z");
    private static final Set<String> RECIPE_FEATURE = Set.of("RECIPE_MANAGEMENT");
    private static final Set<String> VIEW = Set.of("RECIPE_VIEW");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock recipeCostClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    /** Captures only the calling request thread; setup SQL and unrelated contexts are excluded. */
    public static class SqlCapture implements StatementInspector {
        private static final ThreadLocal<List<String>> SQL = new ThreadLocal<>();

        @Override public String inspect(String sql) {
            List<String> captured = SQL.get();
            if (captured != null) captured.add(sql.toLowerCase(Locale.ROOT));
            return sql;
        }

        static void start() { SQL.set(new ArrayList<>()); }
        static List<String> stop() {
            List<String> captured = List.copyOf(SQL.get());
            SQL.remove();
            return captured;
        }
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
    @Autowired ItemRepository items;
    @Autowired RecipeRepository recipes;
    @Autowired RecipeItemRepository recipeItems;
    @Autowired EntityManagerFactory emf;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_recipe_cost_[0-9a-f]{32}"))
            throw new IllegalStateException("Not a recipe cost test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void estimatesWholeBatchAndYieldWithRecipeEntitlementOnlyAndNoInventory() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "PHO", "Pho", "bowl");
        Item beef = ingredient(owner, "BEEF", "Beef", "g", "250.00", true);
        Item noodles = ingredient(owner, "NOODLES", "Noodles", "g", "30.00", false);
        Recipe recipe = recipe(owner, menu, "10", line(beef, "1200", "5"), line(noodles, "1800", "0"));
        assertThat(rows("warehouses", owner)).isEmpty();

        Result result = estimate(owner, menu, recipe);
        result.expect(200);
        JsonNode body = result.body;
        assertThat(body.path("menuItemId").asText()).isEqualTo(menu.getId().toString());
        assertThat(body.path("recipeId").asText()).isEqualTo(recipe.getId().toString());
        assertThat(body.path("recipeVersion").asInt()).isEqualTo(1);
        assertThat(body.path("recipeActive").asBoolean()).isTrue();
        money(body, "yieldQuantity", "10");
        assertThat(body.path("yieldUnit").asText()).isEqualTo("bowl");
        assertThat(body.path("currencyCode").asText()).isEqualTo("VND");
        assertThat(body.path("costSource").asText()).isEqualTo("INGREDIENT_COST_PRICE");
        money(body, "totalBatchCost", "369000.00");
        money(body, "costPerYieldUnit", "36900.00");
        assertThat(body.path("ingredients").size()).isEqualTo(2);
        JsonNode beefLine = ingredientLine(body, beef);
        money(beefLine, "quantity", "1200");
        money(beefLine, "wastePercent", "5");
        money(beefLine, "grossQuantity", "1260");
        money(beefLine, "unitCost", "250");
        money(beefLine, "lineCost", "315000");
        money(ingredientLine(body, noodles), "lineCost", "54000");
        assertThat(body.path("warnings").isArray()).isTrue();
        assertThat(body.path("warnings").isEmpty()).isTrue();
        assertThat(body.path("estimatedAt").asText()).isEqualTo(NOW.toString());
        assertThat(result.cacheControl).contains("no-store");
        assertThat(body.toString()).doesNotContain("restaurantId", "stockDocumentId");
    }

    @Test
    void preservesTinyGrossQuantityAndUsesRoundedLineSumForBatchCost() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "PRECISION", "Precision", "batch");
        Item tiny = ingredient(owner, "TINY", "Tiny", "g", "999999999999.99", false);
        Item first = ingredient(owner, "ROUND-A", "Round A", "g", "0.50", false);
        Item second = ingredient(owner, "ROUND-B", "Round B", "g", "0.50", true);
        Recipe recipe = recipe(owner, menu, "3", line(tiny, "0.001", "0.01"),
            line(first, "0.01", "0"), line(second, "0.01", "0"));

        Result result = estimate(owner, menu, recipe);
        result.expect(200);
        money(ingredientLine(result.body, tiny), "grossQuantity", "0.0010001");
        money(ingredientLine(result.body, tiny), "lineCost", "1000100000.00");
        money(ingredientLine(result.body, first), "lineCost", "0.01");
        money(ingredientLine(result.body, second), "lineCost", "0.01");
        money(result.body, "totalBatchCost", "1000100000.02");
        money(result.body, "costPerYieldUnit", "333366666.67");
    }

    @Test
    void estimatesRequestedInactiveVersionWithSnapshotsAndCurrentReferencePrices() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "HISTORY", "History", "bowl");
        Item beef = ingredient(owner, "OLD-SKU", "Old name", "g", "2.00", true);
        Recipe old = recipe(owner, menu, "2", line(beef, "10", "0"));
        old.deactivate(NOW);
        recipes.saveAndFlush(old);
        Recipe active = new Recipe();
        active.initialize(owner.getRestaurantId(), menu.getId(), 2, BigDecimal.ONE, "bowl", NOW);
        active = recipes.saveAndFlush(active);
        RecipeItem activeLine = new RecipeItem();
        activeLine.initialize(owner.getRestaurantId(), active.getId(), beef, new BigDecimal("99"),
            BigDecimal.ZERO, NOW);
        recipeItems.saveAndFlush(activeLine);

        Result before = estimate(owner, menu, old);
        before.expect(200);
        money(before.body, "totalBatchCost", "20");
        beef.setName("Current name");
        beef.setSku("CURRENT-SKU");
        beef.setCostPrice(new BigDecimal("3.25"));
        beef.setActive(false);
        items.saveAndFlush(beef);

        Result after = estimate(owner, menu, old);
        after.expect(200);
        assertThat(after.body.path("recipeVersion").asInt()).isEqualTo(1);
        assertThat(after.body.path("recipeActive").asBoolean()).isFalse();
        money(after.body, "totalBatchCost", "32.50");
        money(after.body, "costPerYieldUnit", "16.25");
        JsonNode detail = ingredientLine(after.body, beef);
        assertThat(detail.path("name").asText()).isEqualTo("Old name");
        assertThat(detail.path("sku").asText()).isEqualTo("OLD-SKU");
        assertThat(detail.path("unit").asText()).isEqualTo("g");
        assertThat(after.body.toString()).doesNotContain("Current name", "CURRENT-SKU");
    }

    @Test
    void historicalSoftDeletedIngredientStillUsesItsCurrentPriceWhenUnitsMatch() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "DELETED-ING", "History", "portion");
        Item ingredient = ingredient(owner, "SOFT-DELETED", "Snapshot name", "g", "7.50", false);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "4", "0"));
        recipe.deactivate(NOW);
        recipes.saveAndFlush(recipe);
        ingredient.setDeletedAt(NOW);
        ingredient.setActive(false);
        items.saveAndFlush(ingredient);

        Result result = estimate(owner, menu, recipe);
        result.expect(200);
        money(result.body, "totalBatchCost", "30");
        assertThat(ingredientLine(result.body, ingredient).path("name").asText()).isEqualTo("Snapshot name");
    }

    @Test
    void inactiveOrOutOfStockMenuDoesNotPreventReadOnlyEstimate() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "OFF-MENU", "Off menu", "portion");
        Item ingredient = ingredient(owner, "OFF-I", "Ingredient", "g", "2", false);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "2", "0"));
        menu.setActive(false);
        menu.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK);
        items.saveAndFlush(menu);
        estimate(owner, menu, recipe).expect(200);
    }

    @Test
    void currencyComesFromRestaurantRatherThanCalculatorDefault() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        jdbc.update("update restaurants set currency_code='USD' where id=?", owner.getRestaurantId());
        Item menu = menu(owner, "USD-M", "USD menu", "portion");
        Item ingredient = ingredient(owner, "USD-I", "USD ingredient", "g", "0.25", false);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "2", "0"));
        Result result = estimate(owner, menu, recipe);
        result.expect(200);
        assertThat(result.body.path("currencyCode").asText()).isEqualTo("USD");
        money(result.body, "totalBatchCost", "0.50");
    }

    @Test
    void zeroPricesAreValidAndEachIngredientHasExactlyOneWarning() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "ZERO-M", "Zero menu", "portion");
        Item first = ingredient(owner, "ZERO-A", "Zero A", "g", "0", true);
        Item second = ingredient(owner, "ZERO-B", "Zero B", "ml", "0", false);
        Recipe recipe = recipe(owner, menu, "10", line(first, "100", "5"), line(second, "10", "0"));
        Result result = estimate(owner, menu, recipe);
        result.expect(200);
        money(result.body, "totalBatchCost", "0");
        money(result.body, "costPerYieldUnit", "0");
        List<String> ids = new ArrayList<>();
        for (JsonNode warning : result.body.path("warnings")) {
            assertThat(warning.path("code").asText()).isEqualTo("ZERO_INGREDIENT_REFERENCE_COST");
            assertThat(warning.path("message").asText()).isNotBlank();
            ids.add(warning.path("ingredientId").asText());
        }
        assertThat(ids).containsExactlyInAnyOrder(first.getId().toString(), second.getId().toString());
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    void changedIngredientUnitRejectsWholeEstimateInsteadOfMispricingHistory() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "UNIT-M", "Unit menu", "portion");
        Item ingredient = ingredient(owner, "UNIT-I", "Ingredient", "g", "250", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "1000", "0"));
        recipe.deactivate(NOW);
        recipes.saveAndFlush(recipe);
        ingredient.setBaseUnit("kg");
        items.saveAndFlush(ingredient);
        Result result = estimate(owner, menu, recipe);
        result.error(409, "INGREDIENT_COST_UNIT_MISMATCH");
        assertThat(result.body.has("totalBatchCost")).isFalse();
    }

    @Test
    void invalidIngredientSourceIsNotSilentlyPricedAtZeroOrOmitted() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "SOURCE-M", "Source menu", "portion");
        Item ingredient = ingredient(owner, "SOURCE-I", "Source", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
        // The FK still exists; the estimate must nevertheless require an INGREDIENT price source.
        jdbc.update("update items set item_type='MENU_ITEM',track_inventory=false where id=?", ingredient.getId());
        Result result = estimate(owner, menu, recipe);
        result.error(409, "INGREDIENT_COST_UNAVAILABLE");
        assertThat(result.body.has("totalBatchCost")).isFalse();
    }

    @Test
    void requiresAuthenticationAndRecipeViewPermission() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "AUTH-M", "Auth menu", "portion");
        Item ingredient = ingredient(owner, "AUTH-I", "Auth ingredient", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
        var unauthenticated = mvc.perform(get(path(menu, recipe)).accept(MediaType.APPLICATION_JSON))
            .andReturn().getResponse();
        assertThat(unauthenticated.getStatus()).isEqualTo(401);
        call(get(path(menu, recipe)), owner, Set.of()).error(403, "FORBIDDEN");
        call(get(path(menu, recipe)), owner, Set.of("RECIPE_CREATE", "INVENTORY_VIEW"))
            .error(403, "FORBIDDEN");
        estimate(owner, menu, recipe).expect(200);
    }

    @Test
    void recipeFeatureIsRequiredAndInventoryFeatureCannotReplaceIt() throws Exception {
        for (Set<String> features : List.of(Set.<String>of(), Set.of("INVENTORY_MANAGEMENT"))) {
            User owner = fixture(features, true);
            Item menu = menu(owner, "FEATURE-M", "Feature menu", "portion");
            Item ingredient = ingredient(owner, "FEATURE-I", "Ingredient", "g", "2", true);
            Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
            estimate(owner, menu, recipe).error(403, "FEATURE_NOT_ENTITLED");
        }
    }

    @Test
    void cancelledAndExpiredSubscriptionCannotReadEstimates() throws Exception {
        User owner = fixture(RECIPE_FEATURE, false);
        Item menu = menu(owner, "SUB-M", "Subscription menu", "portion");
        Item ingredient = ingredient(owner, "SUB-I", "Ingredient", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
        estimate(owner, menu, recipe).error(403, "SUBSCRIPTION_NOT_ACTIVE");
        jdbc.update("update restaurant_subscriptions set status='ACTIVE',start_at=?,end_at=? where restaurant_id=?",
            Timestamp.from(NOW.minusSeconds(7200)), Timestamp.from(NOW.minusSeconds(3600)), owner.getRestaurantId());
        estimate(owner, menu, recipe).error(403, "SUBSCRIPTION_NOT_ACTIVE");
    }

    @Test
    void recipeAndMenuIdentifiersAreScopedToTenantAndAggregate() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        User other = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "TENANT-M", "Menu", "portion");
        Item anotherMenu = menu(owner, "OTHER-M", "Other menu", "portion");
        Item ingredient = ingredient(owner, "TENANT-I", "Ingredient", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
        Recipe anotherRecipe = recipe(owner, anotherMenu, "1", line(ingredient, "20", "0"));
        estimate(other, menu, recipe).error(404, "MENU_ITEM_NOT_FOUND");
        call(get(path(menu, anotherRecipe)), owner, VIEW).error(404, "RECIPE_NOT_FOUND");
        call(get("/api/v1/menu/items/" + menu.getId() + "/recipes/" + UUID.randomUUID() + "/cost-estimate"),
            owner, VIEW).error(404, "RECIPE_NOT_FOUND");

        Item otherMenu = menu(other, "FOREIGN-M", "Foreign menu", "portion");
        Item otherIngredient = ingredient(other, "FOREIGN-I", "Foreign ingredient", "g", "3", false);
        Recipe otherRecipe = recipe(other, otherMenu, "1", line(otherIngredient, "2", "0"));
        call(get(path(menu, otherRecipe)), owner, VIEW).error(404, "RECIPE_NOT_FOUND");
    }

    @Test
    void deletedMenuAndIngredientUsedAsMenuAreNotFound() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "DELETE-M", "Deleted menu", "portion");
        Item ingredient = ingredient(owner, "DELETE-I", "Ingredient", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "1", line(ingredient, "10", "0"));
        menu.setDeletedAt(NOW);
        items.saveAndFlush(menu);
        estimate(owner, menu, recipe).error(404, "MENU_ITEM_NOT_FOUND");
        call(get(path(ingredient, recipe)), owner, VIEW).error(404, "MENU_ITEM_NOT_FOUND");
    }

    @Test
    void invalidUuidReturnsValidationErrorInsteadOfServerFailure() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Result result = call(get("/api/v1/menu/items/not-a-uuid/recipes/" + UUID.randomUUID() + "/cost-estimate"),
            owner, VIEW);
        result.expect(400);
        assertThat(result.body.path("code").asText()).isNotBlank();
    }

    @Test
    void estimateDoesNotMutateRecipesItemsInventoryAuditOrEntityVersions() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item menu = menu(owner, "READ-M", "Read only menu", "portion");
        Item ingredient = ingredient(owner, "READ-I", "Read only ingredient", "g", "2", true);
        Recipe recipe = recipe(owner, menu, "2", line(ingredient, "10", "5"));
        seedInventory(owner, ingredient);
        Map<String, List<String>> before = tenantSnapshot(owner);
        var statistics = emf.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            Result first = estimate(owner, menu, recipe);
            first.expect(200);
            Result second = estimate(owner, menu, recipe);
            second.expect(200);
            assertThat(second.body).isEqualTo(first.body);
            assertThat(statistics.getEntityInsertCount()).isZero();
            assertThat(statistics.getEntityUpdateCount()).isZero();
            assertThat(statistics.getEntityDeleteCount()).isZero();
        } finally {
            statistics.setStatisticsEnabled(wasEnabled);
        }
        assertThat(tenantSnapshot(owner)).isEqualTo(before);
        assertThat(items.findById(ingredient.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("2");
    }

    @Test
    void batchesPriceReadsAndPreservesStableIngredientOrdering() throws Exception {
        User owner = fixture(RECIPE_FEATURE, true);
        Item smallMenu = menu(owner, "SMALL-M", "Small", "portion");
        Item largeMenu = menu(owner, "LARGE-M", "Large", "portion");
        List<Line> lines = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            Item ingredient = ingredient(owner, "BATCH-" + index, "Ingredient " + index, "g", "2", index % 2 == 0);
            lines.add(line(ingredient, "1", "0"));
        }
        Recipe small = recipe(owner, smallMenu, "1", lines.getFirst());
        Recipe large = recipe(owner, largeMenu, "1", lines.toArray(Line[]::new));
        SqlCapture.start();
        List<String> smallSql;
        try { estimate(owner, smallMenu, small).expect(200); }
        finally { smallSql = SqlCapture.stop(); }
        SqlCapture.start();
        List<String> largeSql;
        Result result;
        try { result = estimate(owner, largeMenu, large); result.expect(200); }
        finally { largeSql = SqlCapture.stop(); }
        long smallPriceReads = smallSql.stream().filter(sql -> sql.contains("select") && sql.contains("cost_price")).count();
        long largePriceReads = largeSql.stream().filter(sql -> sql.contains("select") && sql.contains("cost_price")).count();
        assertThat(smallPriceReads).isPositive();
        assertThat(largePriceReads).isEqualTo(smallPriceReads).isLessThanOrEqualTo(2);
        assertThat(largeSql).noneMatch(sql -> sql.contains("for update") || sql.contains("for no key update"));
        assertThat(result.body.path("ingredients").size()).isEqualTo(10);
        money(result.body, "totalBatchCost", "20");
        List<String> ids = new ArrayList<>();
        result.body.path("ingredients").forEach(node -> ids.add(node.path("ingredientId").asText()));
        assertThat(ids).isSorted();
        assertThat(estimate(owner, largeMenu, large).body.path("ingredients")).isEqualTo(result.body.path("ingredients"));
    }

    private String path(Item menu, Recipe recipe) {
        return "/api/v1/menu/items/" + menu.getId() + "/recipes/" + recipe.getId() + "/cost-estimate";
    }

    private Result estimate(User owner, Item menu, Recipe recipe) throws Exception {
        return call(get(path(menu, recipe)), owner, VIEW);
    }

    private Result call(MockHttpServletRequestBuilder request, User owner, Set<String> permissions) throws Exception {
        CurrentUser principal = new CurrentUser(owner.getId(), owner.getRestaurantId(), owner.getEmail(), "OWNER", permissions);
        var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
        var authenticationToken = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        var response = mvc.perform(request.accept(MediaType.APPLICATION_JSON).with(authentication(authenticationToken)))
            .andReturn().getResponse();
        return new Result(response.getStatus(), response.getContentAsString().isBlank()
            ? json.createObjectNode() : json.readTree(response.getContentAsString()), response.getHeader("Cache-Control"));
    }

    private static void money(JsonNode node, String field, String expected) {
        assertThat(node.path(field).isNumber()).describedAs("numeric field %s in %s", field, node).isTrue();
        assertThat(node.path(field).decimalValue()).isEqualByComparingTo(expected);
    }

    private JsonNode ingredientLine(JsonNode response, Item ingredient) {
        for (JsonNode line : response.path("ingredients"))
            if (line.path("ingredientId").asText().equals(ingredient.getId().toString())) return line;
        throw new AssertionError("Missing ingredient " + ingredient.getId() + " in " + response);
    }

    private Item menu(User owner, String sku, String name, String unit) {
        return item(owner, sku, name, unit, ItemType.MENU_ITEM, "0", false);
    }

    private Item ingredient(User owner, String sku, String name, String unit, String cost, boolean tracking) {
        return item(owner, sku, name, unit, ItemType.INGREDIENT, cost, tracking);
    }

    private Item item(User owner, String sku, String name, String unit, ItemType type, String cost, boolean tracking) {
        Item item = new Item();
        item.initialize(owner.getRestaurantId(), NOW);
        item.setItemType(type);
        item.setSku(sku);
        item.setName(name);
        item.setBaseUnit(unit);
        item.setSalePrice(type == ItemType.MENU_ITEM ? new BigDecimal("100") : BigDecimal.ZERO);
        item.setCostPrice(new BigDecimal(cost));
        item.setTrackInventory(tracking);
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        item.setActive(true);
        return items.saveAndFlush(item);
    }

    private Line line(Item ingredient, String quantity, String waste) {
        return new Line(ingredient, new BigDecimal(quantity), new BigDecimal(waste));
    }

    private Recipe recipe(User owner, Item menu, String yield, Line... lines) {
        Recipe recipe = new Recipe();
        recipe.initialize(owner.getRestaurantId(), menu.getId(), 1, new BigDecimal(yield), menu.getBaseUnit(), NOW);
        recipe = recipes.saveAndFlush(recipe);
        for (Line line : lines) {
            RecipeItem detail = new RecipeItem();
            detail.initialize(owner.getRestaurantId(), recipe.getId(), line.ingredient, line.quantity, line.waste, NOW);
            recipeItems.saveAndFlush(detail);
        }
        return recipe;
    }

    private User fixture(Set<String> featureCodes, boolean activeSubscription) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("COST" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Recipe cost test");
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
        subscription.setStartAt(NOW.minusSeconds(30));
        subscription.setEndAt(NOW.plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO", featureCodes.stream()
            .map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(), NOW).toMap());
        subscriptions.saveAndFlush(subscription);
        return user;
    }

    private List<String> rows(String table, User owner) {
        return jdbc.queryForList("select to_jsonb(t)::text from " + table
            + " t where restaurant_id=? order by id", String.class, owner.getRestaurantId());
    }

    private Map<String, List<String>> tenantSnapshot(User owner) {
        Map<String, List<String>> snapshot = new LinkedHashMap<>();
        for (String table : List.of("items", "recipes", "recipe_items", "warehouses", "inventory_balances",
            "stock_documents", "stock_document_items", "inventory_transactions", "audit_logs"))
            snapshot.put(table, rows(table, owner));
        return snapshot;
    }

    private void seedInventory(User owner, Item ingredient) {
        UUID warehouse = UUID.randomUUID();
        UUID document = UUID.randomUUID();
        UUID documentItem = UUID.randomUUID();
        Timestamp now = Timestamp.from(NOW);
        jdbc.update("insert into warehouses(id,restaurant_id,code,name,is_default,created_at,updated_at) values (?,?,?,?,true,?,?)",
            warehouse, owner.getRestaurantId(), "STOCK", "Stock", now, now);
        jdbc.update("insert into inventory_balances(restaurant_id,warehouse_id,item_id,quantity,min_quantity,created_at,updated_at)"
            + " values (?,?,?,100,10,?,?)", owner.getRestaurantId(), warehouse, ingredient.getId(), now, now);
        jdbc.update("insert into stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,"
            + "idempotency_key,creation_request_hash,created_by,confirmed_by,confirmed_at,line_count,total_quantity,total_cost,"
            + "created_at,updated_at) values (?,?,?,'RCV-FIXTURE','RECEIPT','CONFIRMED','fixture','fixture',?,?,?,1,100,200,?,?)",
            document, owner.getRestaurantId(), warehouse, owner.getId(), owner.getId(), now, now, now);
        jdbc.update("insert into stock_document_items(id,restaurant_id,stock_document_id,ingredient_id,ingredient_sku,"
            + "ingredient_name,unit,quantity,unit_cost,total_cost,created_at,updated_at) values (?,?,?,?,?,?,?,100,2,200,?,?)",
            documentItem, owner.getRestaurantId(), document, ingredient.getId(), ingredient.getSku(), ingredient.getName(),
            ingredient.getBaseUnit(), now, now);
        jdbc.update("insert into inventory_transactions(restaurant_id,warehouse_id,item_id,transaction_type,stock_document_id,"
            + "stock_document_item_id,quantity_change,before_quantity,after_quantity,unit,unit_cost,idempotency_key,created_by,created_at)"
            + " values (?,?,?,'RECEIPT',?,?,100,0,100,?,2,'fixture',?,?)", owner.getRestaurantId(), warehouse,
            ingredient.getId(), document, documentItem, ingredient.getBaseUnit(), owner.getId(), now);
    }

    private record Line(Item ingredient, BigDecimal quantity, BigDecimal waste) {}

    private record Result(int status, JsonNode body, String cacheControl) {
        void expect(int expected) { assertThat(status).describedAs(body.toString()).isEqualTo(expected); }
        void error(int expected, String code) {
            expect(expected);
            assertThat(body.path("code").asText()).isEqualTo(code);
        }
    }
}
