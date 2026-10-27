package com.possaas.modules.inventory;

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
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.menu.entity.AvailabilityStatus;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.menu.repository.RecipeRepository;
import com.possaas.modules.menu.repository.RecipeItemRepository;
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
class InventoryIntegrationTest {
    private static final String SCHEMA = "it_inventory_" + UUID.randomUUID().toString().replace("-", "");
    private static final String WAREHOUSES = "/api/v1/inventory/warehouses";
    private static final String STOCK_DOCUMENTS = "/api/v1/inventory/stock-documents";
    private static final Set<String> ALL = Set.of("INVENTORY_VIEW", "WAREHOUSE_CREATE", "WAREHOUSE_UPDATE",
            "WAREHOUSE_DELETE", "INVENTORY_THRESHOLD_UPDATE", "INGREDIENT_UPDATE", "INGREDIENT_DELETE",
            "INVENTORY_RECEIVE", "INVENTORY_ISSUE", "INVENTORY_ADJUST");

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
    @Autowired InventoryBalanceRepository balances;
    @Autowired Clock clock;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_inventory_[0-9a-f]{32}"))
            throw new IllegalStateException("Not an inventory test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void warehouseLifecycleNormalizesDefaultsVersionsAndAudit() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT", "LOW_STOCK_ALERT"), true);
        Result first = call(post(WAREHOUSES).content("{\"code\":\" kho-chinh \",\"name\":\" Kho chính \"}"),
                owner, ALL).expect(201);
        assertThat(first.body.at("/code").asText()).isEqualTo("KHO-CHINH");
        assertThat(first.body.at("/name").asText()).isEqualTo("Kho chính");
        assertThat(first.body.at("/isDefault").asBoolean()).isTrue();
        Result second = call(post(WAREHOUSES).content("{\"code\":\"phu\",\"name\":\"Kho phụ\"}"),
                owner, ALL).expect(201);
        assertThat(second.body.at("/isDefault").asBoolean()).isFalse();
        call(post(WAREHOUSES).content("{\"code\":\"KHO-CHINH\",\"name\":\"Trùng\"}"), owner, ALL)
                .error(409, "WAREHOUSE_CODE_EXISTS");

        Result unchanged = call(patch(WAREHOUSES + "/" + second.id()).content(
                "{\"name\":\" Kho phụ \",\"expectedVersion\":0}"), owner, ALL).expect(200);
        assertThat(unchanged.body.at("/version").asLong()).isZero();
        Result renamed = call(patch(WAREHOUSES + "/" + second.id()).content(
                "{\"name\":\"Kho dự phòng\",\"expectedVersion\":0}"), owner, ALL).expect(200);
        assertThat(renamed.body.at("/version").asLong()).isEqualTo(1);
        call(patch(WAREHOUSES + "/" + second.id()).content(
                "{\"name\":\"Stale\",\"expectedVersion\":0}"), owner, ALL)
                .error(409, "CONCURRENT_WAREHOUSE_UPDATE");
        call(patch(WAREHOUSES + "/" + first.id() + "/status").content(
                "{\"active\":false,\"expectedVersion\":0}"), owner, ALL)
                .error(409, "DEFAULT_WAREHOUSE_CANNOT_BE_DISABLED");

        Result madeDefault = call(post(WAREHOUSES + "/" + second.id() + "/make-default").content(
                "{\"expectedVersion\":1}"), owner, ALL).expect(200);
        assertThat(madeDefault.body.at("/isDefault").asBoolean()).isTrue();
        long version = madeDefault.body.at("/version").asLong();
        Result replay = call(post(WAREHOUSES + "/" + second.id() + "/make-default").content(
                "{\"expectedVersion\":0}"), owner, ALL).expect(200);
        assertThat(replay.body.at("/version").asLong()).isEqualTo(version);
        assertThat(events(owner, "WAREHOUSE_DEFAULT_CHANGED")).isEqualTo(1);

        Result page = call(get(WAREHOUSES).param("q", "kho").param("sortBy", "name"), owner, ALL).expect(200);
        assertThat(page.body.at("/totalElements").asInt()).isEqualTo(2);
        Result currentFirst = call(get(WAREHOUSES + "/" + first.id()), owner, ALL).expect(200);
        call(patch(WAREHOUSES + "/" + first.id() + "/status").content(
                "{\"active\":false,\"expectedVersion\":" + currentFirst.body.at("/version").asLong() + "}"),
                owner, ALL).expect(200);
        Result disabled = call(get(WAREHOUSES + "/" + first.id()), owner, ALL).expect(200);
        call(delete(WAREHOUSES + "/" + first.id()).param("expectedVersion",
                disabled.body.at("/version").asText()), owner, ALL).expect(204);
        call(delete(WAREHOUSES + "/" + first.id()).param("expectedVersion", "0"), owner, ALL).expect(204);
        assertThat(events(owner, "WAREHOUSE_DELETED")).isEqualTo(1);
    }

    @Test
    void sparseBalanceThresholdAndLowStockStayReadOnly() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT", "LOW_STOCK_ALERT"), true);
        Result warehouse = createWarehouse(owner, "MAIN");
        Item ingredient = ingredient(owner.getRestaurantId(), "BEEF", "Thịt bò", true, false);
        ingredient(owner.getRestaurantId(), "NO-TRACK", "Không theo dõi", false, true);

        String balancesUrl = WAREHOUSES + "/" + warehouse.id() + "/balances";
        Result logical = call(get(balancesUrl), owner, ALL).expect(200);
        assertThat(logical.body.at("/totalElements").asInt()).isEqualTo(1);
        assertThat(logical.body.at("/content/0/configured").asBoolean()).isFalse();
        assertThat(logical.body.at("/content/0/quantity").decimalValue()).isEqualByComparingTo("0.000");
        assertThat(logical.body.at("/content/0/ingredientActive").asBoolean()).isFalse();
        assertThat(balanceRows(owner.getRestaurantId())).isZero();

        String thresholdUrl = balancesUrl + "/" + ingredient.getId() + "/threshold";
        Result zero = call(patch(thresholdUrl).content("{\"expectedVersion\":null,\"minQuantity\":0}"),
                owner, ALL).expect(200);
        assertThat(zero.body.at("/configured").asBoolean()).isFalse();
        assertThat(balanceRows(owner.getRestaurantId())).isZero();
        Result configured = call(patch(thresholdUrl).content(
                "{\"expectedVersion\":null,\"minQuantity\":10.000}"), owner, ALL).expect(200);
        assertThat(configured.body.at("/configured").asBoolean()).isTrue();
        assertThat(configured.body.at("/lowStock").asBoolean()).isTrue();
        call(patch(thresholdUrl).content("{\"expectedVersion\":null,\"minQuantity\":11}"), owner, ALL)
                .error(409, "CONCURRENT_INVENTORY_UPDATE");
        call(patch(thresholdUrl).content("{\"expectedVersion\":0,\"minQuantity\":1.0001}"), owner, ALL)
                .error(400, "VALIDATION_ERROR");
        Result low = call(get("/api/v1/inventory/low-stock"), owner, ALL).expect(200);
        assertThat(low.body.at("/totalElements").asInt()).isEqualTo(1);

        long rowsBefore = balanceRows(owner.getRestaurantId());
        call(get(balancesUrl), owner, ALL).expect(200);
        call(get(balancesUrl + "/" + ingredient.getId()), owner, ALL).expect(200);
        assertThat(balanceRows(owner.getRestaurantId())).isEqualTo(rowsBefore);
        call(patch(thresholdUrl).content("{\"quantity\":9,\"expectedVersion\":0,\"minQuantity\":2}"),
                owner, ALL).error(400, "INVALID_REQUEST_BODY");
    }

    @Test
    void tenantFeaturePermissionAndIngredientStockGuardsAreEnforced() throws Exception {
        User first = fixture(Set.of("INVENTORY_MANAGEMENT", "LOW_STOCK_ALERT"), true);
        User second = fixture(Set.of("INVENTORY_MANAGEMENT", "LOW_STOCK_ALERT"), true);
        Result warehouse = createWarehouse(first, "SHARED");
        createWarehouse(second, "SHARED");
        call(get(WAREHOUSES + "/" + warehouse.id()), second, ALL).error(404, "WAREHOUSE_NOT_FOUND");
        call(get(WAREHOUSES), first, Set.of()).error(403, "FORBIDDEN");

        User noFeature = fixture(Set.of(), true);
        call(get(WAREHOUSES), noFeature, ALL).error(403, "FEATURE_NOT_ENTITLED");
        User inventoryOnly = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        call(get("/api/v1/inventory/low-stock"), inventoryOnly, ALL).error(403, "FEATURE_NOT_ENTITLED");

        Item ingredient = ingredient(first.getRestaurantId(), "STOCKED", "Có tồn", true, true);
        String threshold = WAREHOUSES + "/" + warehouse.id() + "/balances/" + ingredient.getId() + "/threshold";
        call(patch(threshold).content("{\"minQuantity\":1}"), first, ALL).expect(200);
        jdbc.update("update inventory_balances set quantity=5 where restaurant_id=? and item_id=?",
                first.getRestaurantId(), ingredient.getId());
        call(patch("/api/v1/menu/ingredients/" + ingredient.getId()).content(
                "{\"trackInventory\":false,\"expectedVersion\":0}"), first, ALL)
                .error(409, "INGREDIENT_HAS_STOCK");
        call(patch("/api/v1/menu/ingredients/" + ingredient.getId()).content(
                "{\"baseUnit\":\"kg\",\"expectedVersion\":0}"), first, ALL)
                .error(409, "INGREDIENT_HAS_STOCK");
        call(delete("/api/v1/menu/ingredients/" + ingredient.getId()).param("expectedVersion", "0"), first, ALL)
                .error(409, "INGREDIENT_HAS_STOCK");
        call(patch(WAREHOUSES + "/" + warehouse.id() + "/status").content(
                "{\"active\":false,\"expectedVersion\":0}"), first, ALL)
                .error(409, "DEFAULT_WAREHOUSE_CANNOT_BE_DISABLED");
        call(delete(WAREHOUSES + "/" + warehouse.id()).param("expectedVersion", "0"), first, ALL)
                .error(409, "WAREHOUSE_HAS_STOCK");
    }

    @Test
    void concurrentLogicalThresholdCreationHasOneWinner() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "RACE");
        Item ingredient = ingredient(owner.getRestaurantId(), "RACE", "Race", true, true);
        String url = WAREHOUSES + "/" + warehouse.id() + "/balances/" + ingredient.getId() + "/threshold";
        List<Result> results = race(
                () -> call(patch(url).content("{\"minQuantity\":1}"), owner, ALL),
                () -> call(patch(url).content("{\"minQuantity\":2}"), owner, ALL));
        assertThat(results.stream().map(Result::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject(
                "select count(*) from inventory_balances where restaurant_id=? and item_id=?",
                Long.class, owner.getRestaurantId(), ingredient.getId())).isEqualTo(1);
        assertThat(events(owner, "INVENTORY_THRESHOLD_UPDATED")).isEqualTo(1);
    }

    @Test
    void receiptConfirmationPostsBalanceAndImmutableLedgerExactlyOnce() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "POSTING");
        Item ingredient = ingredient(owner.getRestaurantId(), "RICE", "Gạo", true, true);
        String body = """
                {"documentType":"RECEIPT","warehouseId":"%s","note":"Nhập đầu kỳ","items":[
                  {"ingredientId":"%s","quantity":5.000,"unitCost":18000.00}
                ]}
                """.formatted(warehouse.id(), ingredient.getId());

        Result draft = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "receipt-001").content(body),
                owner, ALL).expect(201);
        assertThat(draft.body.at("/status").asText()).isEqualTo("DRAFT");
        assertThat(draft.body.at("/lineCount").asInt()).isEqualTo(1);
        assertThat(draft.body.at("/totalCost").decimalValue()).isEqualByComparingTo("90000.00");
        Result replay = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "receipt-001").content(body),
                owner, ALL).expect(201);
        assertThat(replay.id()).isEqualTo(draft.id());
        call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "receipt-001").content(body.replace("5.000", "6.000")),
                owner, ALL).error(409, "IDEMPOTENCY_KEY_REUSED");

        Result confirmed = call(post(STOCK_DOCUMENTS + "/" + draft.id() + "/confirm").content(
                "{\"expectedVersion\":" + draft.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
        assertThat(confirmed.body.at("/status").asText()).isEqualTo("CONFIRMED");
        Result confirmReplay = call(post(STOCK_DOCUMENTS + "/" + draft.id() + "/confirm").content(
                "{\"expectedVersion\":0}"), owner, ALL).expect(200);
        assertThat(confirmReplay.body.at("/status").asText()).isEqualTo("CONFIRMED");

        Result balance = call(get(WAREHOUSES + "/" + warehouse.id() + "/balances/" + ingredient.getId()),
                owner, ALL).expect(200);
        assertThat(balance.body.at("/quantity").decimalValue()).isEqualByComparingTo("5.000");
        Result ledger = call(get("/api/v1/inventory/transactions").param("documentId", draft.id().toString()),
                owner, ALL).expect(200);
        assertThat(ledger.body.at("/totalElements").asInt()).isEqualTo(1);
        assertThat(ledger.body.at("/content/0/quantityChange").decimalValue()).isEqualByComparingTo("5.000");
        assertThat(events(owner, "STOCK_DOCUMENT_CONFIRMED")).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("update inventory_transactions set quantity_change=9 where stock_document_id=?",
                draft.id())).hasMessageContaining("append-only");
    }

    @Test
    void insufficientIssueRollsBackWholePostingAndDraftCanBeCancelledIdempotently() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "ROLLBACK");
        Item first = ingredient(owner.getRestaurantId(), "FIRST", "Một", true, true);
        Item second = ingredient(owner.getRestaurantId(), "SECOND", "Hai", true, true);
        String receiptBody = """
                {"documentType":"RECEIPT","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":5,"unitCost":1},
                  {"ingredientId":"%s","quantity":2,"unitCost":1}
                ]}
                """.formatted(warehouse.id(), first.getId(), second.getId());
        Result receipt = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "seed-stock").content(receiptBody),
                owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + receipt.id() + "/confirm").content(
                "{\"expectedVersion\":" + receipt.body.at("/version").asLong() + "}"), owner, ALL).expect(200);

        String issueBody = """
                {"documentType":"ISSUE","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":1},
                  {"ingredientId":"%s","quantity":3}
                ]}
                """.formatted(warehouse.id(), first.getId(), second.getId());
        Result issue = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "issue-too-much").content(issueBody),
                owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + issue.id() + "/confirm").content(
                "{\"expectedVersion\":" + issue.body.at("/version").asLong() + "}"), owner, ALL)
                .error(409, "INSUFFICIENT_STOCK");
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",
                BigDecimal.class, owner.getRestaurantId(), warehouse.id(), first.getId())).isEqualByComparingTo("5.000");
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",
                BigDecimal.class, owner.getRestaurantId(), warehouse.id(), second.getId())).isEqualByComparingTo("2.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",
                Long.class, issue.id())).isZero();
        Result stillDraft = call(get(STOCK_DOCUMENTS + "/" + issue.id()), owner, ALL).expect(200);
        assertThat(stillDraft.body.at("/status").asText()).isEqualTo("DRAFT");
        Result cancelled = call(post(STOCK_DOCUMENTS + "/" + issue.id() + "/cancel").content(
                "{\"expectedVersion\":" + stillDraft.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
        call(post(STOCK_DOCUMENTS + "/" + issue.id() + "/cancel").content("{\"expectedVersion\":0}"),
                owner, ALL).expect(200);
        assertThat(cancelled.body.at("/status").asText()).isEqualTo("CANCELLED");
        assertThat(events(owner, "STOCK_DOCUMENT_CANCELLED")).isEqualTo(1);
    }

    @Test
    void stockDocumentTypeRequiresItsSpecificPermission() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "PERMISSION");
        Item ingredient = ingredient(owner.getRestaurantId(), "PERM", "Permission", true, true);
        String body = """
                {"documentType":"ISSUE","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":1}
                ]}
                """.formatted(warehouse.id(), ingredient.getId());
        call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "permission-issue").content(body),
                owner, Set.of("INVENTORY_RECEIVE")).error(403, "FORBIDDEN");
        assertThat(jdbc.queryForObject("select count(*) from stock_documents where restaurant_id=?",
                Long.class, owner.getRestaurantId())).isZero();
    }

    @Test
    void adjustmentPostsSignedMovementsAndPreservesExistingThreshold() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "ADJUST");
        Item decrease = ingredient(owner.getRestaurantId(), "ADJ-DOWN", "Giảm", true, true);
        Item increase = ingredient(owner.getRestaurantId(), "ADJ-UP", "Tăng", true, true);
        call(patch(WAREHOUSES + "/" + warehouse.id() + "/balances/" + decrease.getId() + "/threshold")
                .content("{\"minQuantity\":2}"), owner, ALL).expect(200);

        String receiptBody = """
                {"documentType":"RECEIPT","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":10,"unitCost":1}
                ]}
                """.formatted(warehouse.id(), decrease.getId());
        Result receipt = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "adjust-seed")
                .content(receiptBody), owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + receipt.id() + "/confirm").content(
                "{\"expectedVersion\":" + receipt.body.at("/version").asLong() + "}"), owner, ALL).expect(200);

        String adjustmentBody = """
                {"documentType":"ADJUSTMENT","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":3,"adjustmentDirection":"DECREASE"},
                  {"ingredientId":"%s","quantity":4,"adjustmentDirection":"INCREASE"}
                ]}
                """.formatted(warehouse.id(), decrease.getId(), increase.getId());
        Result adjustment = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "adjust-mixed")
                .content(adjustmentBody), owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + adjustment.id() + "/confirm").content(
                "{\"expectedVersion\":" + adjustment.body.at("/version").asLong() + "}"), owner, ALL).expect(200);

        Result down = call(get(WAREHOUSES + "/" + warehouse.id() + "/balances/" + decrease.getId()),
                owner, ALL).expect(200);
        Result up = call(get(WAREHOUSES + "/" + warehouse.id() + "/balances/" + increase.getId()),
                owner, ALL).expect(200);
        assertThat(down.body.at("/quantity").decimalValue()).isEqualByComparingTo("7.000");
        assertThat(down.body.at("/minQuantity").decimalValue()).isEqualByComparingTo("2.000");
        assertThat(up.body.at("/quantity").decimalValue()).isEqualByComparingTo("4.000");
        assertThat(jdbc.queryForList("select transaction_type from inventory_transactions where stock_document_id=?",
                String.class, adjustment.id())).containsExactlyInAnyOrder(
                        "ADJUSTMENT_DECREASE", "ADJUSTMENT_INCREASE");
    }

    @Test
    void inactiveIngredientCannotBeReceivedButCanBeIssuedFromExistingStock() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "INACTIVE");
        Item ingredient = ingredient(owner.getRestaurantId(), "INACTIVE", "Ngừng dùng", true, true);
        String receiptBody = """
                {"documentType":"RECEIPT","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":5}
                ]}
                """.formatted(warehouse.id(), ingredient.getId());
        Result receipt = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "inactive-seed")
                .content(receiptBody), owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + receipt.id() + "/confirm").content(
                "{\"expectedVersion\":" + receipt.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
        ingredient.setActive(false);
        items.saveAndFlush(ingredient);

        call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "inactive-receipt").content(receiptBody),
                owner, ALL).error(409, "INGREDIENT_INACTIVE_FOR_RECEIPT");
        String issueBody = receiptBody.replace("RECEIPT", "ISSUE").replace("\"quantity\":5", "\"quantity\":2");
        Result issue = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "inactive-issue")
                .content(issueBody), owner, ALL).expect(201);
        call(post(STOCK_DOCUMENTS + "/" + issue.id() + "/confirm").content(
                "{\"expectedVersion\":" + issue.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",
                BigDecimal.class, owner.getRestaurantId(), warehouse.id(), ingredient.getId()))
                .isEqualByComparingTo("3.000");
    }

    @Test
    void concurrentConfirmIsIdempotentAndConcurrentIssuesCannotOversell() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "DOC-RACE");
        Item ingredient = ingredient(owner.getRestaurantId(), "DOC-RACE", "Tranh chấp", true, true);
        String receiptBody = """
                {"documentType":"RECEIPT","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":5}
                ]}
                """.formatted(warehouse.id(), ingredient.getId());
        Result receipt = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "race-receipt")
                .content(receiptBody), owner, ALL).expect(201);
        String receiptConfirm = "{\"expectedVersion\":" + receipt.body.at("/version").asLong() + "}";
        List<Result> receiptResults = race(
                () -> call(post(STOCK_DOCUMENTS + "/" + receipt.id() + "/confirm").content(receiptConfirm), owner, ALL),
                () -> call(post(STOCK_DOCUMENTS + "/" + receipt.id() + "/confirm").content(receiptConfirm), owner, ALL));
        assertThat(receiptResults).allMatch(result -> result.status() == 200);
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",
                Long.class, receipt.id())).isEqualTo(1);
        assertThat(events(owner, "STOCK_DOCUMENT_CONFIRMED")).isEqualTo(1);

        String issueBody = receiptBody.replace("RECEIPT", "ISSUE").replace("\"quantity\":5", "\"quantity\":4");
        Result first = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "race-issue-1")
                .content(issueBody), owner, ALL).expect(201);
        Result second = call(post(STOCK_DOCUMENTS).header("Idempotency-Key", "race-issue-2")
                .content(issueBody), owner, ALL).expect(201);
        List<Result> issueResults = race(
                () -> call(post(STOCK_DOCUMENTS + "/" + first.id() + "/confirm").content(
                        "{\"expectedVersion\":" + first.body.at("/version").asLong() + "}"), owner, ALL),
                () -> call(post(STOCK_DOCUMENTS + "/" + second.id() + "/confirm").content(
                        "{\"expectedVersion\":" + second.body.at("/version").asLong() + "}"), owner, ALL));
        assertThat(issueResults.stream().map(Result::status)).containsExactlyInAnyOrder(200, 409);
        assertThat(issueResults.stream().filter(result -> result.status() == 409)
                .map(result -> result.body().path("code").asText())).containsExactly("INSUFFICIENT_STOCK");
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",
                BigDecimal.class, owner.getRestaurantId(), warehouse.id(), ingredient.getId()))
                .isEqualByComparingTo("1.000");
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where transaction_type='ISSUE' and restaurant_id=?",
                Long.class, owner.getRestaurantId())).isEqualTo(1);
    }

    @Test
    void firstReceiptUpdatesReferenceCostOnlyAtConfirmAndReplayDoesNotRecalculate() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-FIRST");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-FIRST", "Giá vốn", true, true);
        ingredient.setCostPrice(new BigDecimal("50.00"));ingredient.setCostConfigured(true);
        ingredient = items.saveAndFlush(ingredient);
        long oldVersion = ingredient.getVersion();
        Result draft = draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "130", null);
        assertThat(cost(ingredient)).isEqualByComparingTo("50.00");
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isZero();

        // Derived price update requires only the existing receipt permission, not INGREDIENT_UPDATE.
        Result confirmed = call(post(STOCK_DOCUMENTS + "/" + draft.id() + "/confirm")
                .content("{\"expectedVersion\":" + draft.body.at("/version").asLong() + "}"),
                owner, Set.of("INVENTORY_RECEIVE")).expect(200);
        Item updated = items.findById(ingredient.getId()).orElseThrow();
        assertThat(updated.getCostPrice()).isEqualByComparingTo("130.00");
        assertThat(updated.getVersion()).isEqualTo(oldVersion + 1);
        call(post(STOCK_DOCUMENTS + "/" + draft.id() + "/confirm").content("{\"expectedVersion\":0}"),
                owner, ALL).expect(200);
        assertThat(cost(ingredient)).isEqualByComparingTo("130.00");
        assertThat(items.findById(ingredient.getId()).orElseThrow().getVersion()).isEqualTo(updated.getVersion());
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(1);
        assertThat(confirmed.body.at("/items/0/unitCost").decimalValue()).isEqualByComparingTo("130.00");
        assertThat(jdbc.queryForObject("select after_data->>'sourceDocumentId' from audit_logs "
                + "where restaurant_id=? and action_code='INGREDIENT_COST_RECALCULATED'",
                String.class, owner.getRestaurantId())).isEqualTo(draft.id().toString());
    }

    @Test
    void subsequentReceiptUsesWeightedCostAndEstimateReadsNewPriceWithoutChangingRecipeOrMenu() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT", "RECIPE_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-AVERAGE");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-AVERAGE", "Nguyên liệu", true, true);
        Result first = draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null);
        confirm(owner, first);
        Item menu = ingredient(owner.getRestaurantId(), "COST-MENU", "Món", false, true);
        menu.setItemType(ItemType.MENU_ITEM);
        menu.setSalePrice(new BigDecimal("700.00"));
        menu.setCostPrice(new BigDecimal("300.00"));menu.setCostConfigured(true);
        menu = items.saveAndFlush(menu);
        Recipe recipe = new Recipe();
        recipe.initialize(owner.getRestaurantId(), menu.getId(), 1, BigDecimal.ONE, "g", clock.instant());
        recipe = recipes.saveAndFlush(recipe);
        RecipeItem line = new RecipeItem();
        line.initialize(owner.getRestaurantId(), recipe.getId(), ingredient, new BigDecimal("2"),
                BigDecimal.ZERO, clock.instant());
        recipeItems.saveAndFlush(line);
        String estimatePath = "/api/v1/menu/items/" + menu.getId() + "/recipes/" + recipe.getId() + "/cost-estimate";
        assertThat(call(get(estimatePath), owner, Set.of("RECIPE_VIEW")).expect(200)
                .body.at("/costPerYieldUnit").decimalValue()).isEqualByComparingTo("200.00");

        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "130", null));
        assertThat(cost(ingredient)).isEqualByComparingTo("110.00");
        assertThat(call(get(estimatePath), owner, Set.of("RECIPE_VIEW")).expect(200)
                .body.at("/costPerYieldUnit").decimalValue()).isEqualByComparingTo("220.00");
        Item unchangedMenu = items.findById(menu.getId()).orElseThrow();
        assertThat(unchangedMenu.getCostPrice()).isEqualByComparingTo("300.00");
        assertThat(unchangedMenu.getSalePrice()).isEqualByComparingTo("700.00");
        assertThat(unchangedMenu.getVersion()).isEqualTo(menu.getVersion());
        assertThat(recipes.findById(recipe.getId()).orElseThrow().getVersion()).isEqualTo(recipe.getVersion());
        assertThat(recipeItems.findById(line.getId()).orElseThrow().getQuantity()).isEqualByComparingTo("2");
        assertThat(jdbc.queryForObject("select unit_cost from inventory_transactions where stock_document_id=?",
                BigDecimal.class, first.id())).isEqualByComparingTo("100.00");
    }

    @Test
    void referenceCostIncludesPhysicalStockAcrossAllWarehouses() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result first = createWarehouse(owner, "COST-W1");
        Result second = createWarehouse(owner, "COST-W2");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-MULTI", "Nhiều kho", true, true);
        confirm(owner, draftMovement(owner, first.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        confirm(owner, draftMovement(owner, second.id(), ingredient.getId(), "RECEIPT", "5", "130", null));
        assertThat(cost(ingredient)).isEqualByComparingTo("110.00");
        assertThat(jdbc.queryForObject("select sum(quantity) from inventory_balances where restaurant_id=?",
                BigDecimal.class, owner.getRestaurantId())).isEqualByComparingTo("15.000");
    }

    @Test
    void issueAdjustmentAndDraftCancellationDoNotRepriceIngredients() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-NON-RECEIPT");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-NON-RECEIPT", "Giữ giá", true, true);
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        long version = items.findById(ingredient.getId()).orElseThrow().getVersion();
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "ISSUE", "2", "999", null));
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "ADJUSTMENT", "3", "900", "INCREASE"));
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "ADJUSTMENT", "1", "800", "DECREASE"));
        Result cancelled = draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "999", null);
        call(post(STOCK_DOCUMENTS + "/" + cancelled.id() + "/cancel").content(
                "{\"expectedVersion\":" + cancelled.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
        assertThat(cost(ingredient)).isEqualByComparingTo("100.00");
        assertThat(items.findById(ingredient.getId()).orElseThrow().getVersion()).isEqualTo(version);
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(1);
    }

    @Test
    void receivingAfterStockIsExhaustedUsesNewReceiptCostAndAcceptsZeroPricedReceipts() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-ZERO");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-ZERO", "Hết tồn", true, true);
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "ISSUE", "10", "0", null));
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "130", null));
        assertThat(cost(ingredient)).isEqualByComparingTo("130.00");
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "0", null));
        assertThat(cost(ingredient)).isEqualByComparingTo("65.00");
    }

    @Test
    void samePriceReceiptPostsStockWithoutNeedlessIngredientVersionOrCostAudit() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-UNCHANGED");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-UNCHANGED", "Cùng giá", true, true);
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        Item before = items.findById(ingredient.getId()).orElseThrow();
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "100", null));
        Item after = items.findById(ingredient.getId()).orElseThrow();
        assertThat(after.getCostPrice()).isEqualByComparingTo("100.00");
        assertThat(after.getVersion()).isEqualTo(before.getVersion());
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(1);
        assertThat(events(owner, "STOCK_DOCUMENT_CONFIRMED")).isEqualTo(2);
    }

    @Test
    void concurrentReceiptsAcrossWarehousesDoNotLoseReferenceCostUpdates() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result firstWarehouse = createWarehouse(owner, "COST-RACE-1");
        Result secondWarehouse = createWarehouse(owner, "COST-RACE-2");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-RACE", "Giá đồng thời", true, true);
        Result first = draftMovement(owner, firstWarehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null);
        Result second = draftMovement(owner, secondWarehouse.id(), ingredient.getId(), "RECEIPT", "10", "200", null);
        assertThat(race(() -> confirm(owner, first), () -> confirm(owner, second)))
                .allMatch(result -> result.status() == 200);
        assertThat(cost(ingredient)).isEqualByComparingTo("150.00");
        assertThat(jdbc.queryForObject("select sum(quantity) from inventory_balances where restaurant_id=?",
                BigDecimal.class, owner.getRestaurantId())).isEqualByComparingTo("20.000");
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(2);
    }

    @Test
    void concurrentConfirmationOfSameReceiptRepricesOnlyOnce() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-REPLAY");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-REPLAY", "Giá một lần", true, true);
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        Result next = draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "130", null);
        assertThat(race(() -> confirm(owner, next), () -> confirm(owner, next)))
                .allMatch(result -> result.status() == 200);
        assertThat(cost(ingredient)).isEqualByComparingTo("110.00");
        assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",
                Long.class, next.id())).isEqualTo(1);
    }

    @Test
    void postingFailureRollsBackReferenceCostVersionStockLedgerAndAudit() throws Exception {
        User owner = fixture(Set.of("INVENTORY_MANAGEMENT"), true);
        Result warehouse = createWarehouse(owner, "COST-ROLLBACK");
        Item ingredient = ingredient(owner.getRestaurantId(), "COST-ROLLBACK", "Rollback giá", true, true);
        confirm(owner, draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "10", "100", null));
        Item before = items.findById(ingredient.getId()).orElseThrow();
        Result next = draftMovement(owner, warehouse.id(), ingredient.getId(), "RECEIPT", "5", "130", null);
        // A constraint failure after prices/balances are updated must roll back the entire transaction.
        jdbc.execute("""
                create function reject_cost_test_receipt() returns trigger language plpgsql as $$
                begin
                  if NEW.stock_document_id = '%s'::uuid then
                    raise exception 'Forced test posting failure' using errcode = '23514';
                  end if;
                  return NEW;
                end $$
                """.formatted(next.id()));
        jdbc.execute("create trigger reject_cost_test_receipt before insert on inventory_transactions "
                + "for each row execute function reject_cost_test_receipt()");
        try {
            call(post(STOCK_DOCUMENTS + "/" + next.id() + "/confirm").content(
                    "{\"expectedVersion\":" + next.body.at("/version").asLong() + "}"), owner, ALL)
                    .error(409, "CONCURRENT_STOCK_DOCUMENT_UPDATE");
            Item after = items.findById(ingredient.getId()).orElseThrow();
            assertThat(after.getCostPrice()).isEqualByComparingTo(before.getCostPrice());
            assertThat(after.getVersion()).isEqualTo(before.getVersion());
            assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
            assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and item_id=?",
                    BigDecimal.class, owner.getRestaurantId(), ingredient.getId())).isEqualByComparingTo("10");
            assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",
                    Long.class, next.id())).isZero();
            assertThat(events(owner, "INGREDIENT_COST_RECALCULATED")).isEqualTo(1);
            assertThat(events(owner, "STOCK_DOCUMENT_CONFIRMED")).isEqualTo(1);
            assertThat(call(get(STOCK_DOCUMENTS + "/" + next.id()), owner, ALL).expect(200)
                    .body.path("status").asText()).isEqualTo("DRAFT");
        } finally {
            jdbc.execute("drop trigger reject_cost_test_receipt on inventory_transactions");
            jdbc.execute("drop function reject_cost_test_receipt()");
        }
        confirm(owner, next);
        assertThat(cost(ingredient)).isEqualByComparingTo("110.00");
    }

    private BigDecimal cost(Item ingredient) {
        return items.findById(ingredient.getId()).orElseThrow().getCostPrice();
    }

    private Result draftMovement(User owner, UUID warehouseId, UUID ingredientId, String type,
            String quantity, String unitCost, String direction) throws Exception {
        String body = """
                {"documentType":"%s","warehouseId":"%s","items":[
                  {"ingredientId":"%s","quantity":%s,"unitCost":%s%s}
                ]}
                """.formatted(type, warehouseId, ingredientId, quantity, unitCost,
                        direction == null ? "" : ",\"adjustmentDirection\":\"" + direction + "\"");
        return call(post(STOCK_DOCUMENTS).header("Idempotency-Key", UUID.randomUUID().toString())
                .content(body), owner, ALL).expect(201);
    }

    private Result confirm(User owner, Result draft) throws Exception {
        return call(post(STOCK_DOCUMENTS + "/" + draft.id() + "/confirm").content(
                "{\"expectedVersion\":" + draft.body.at("/version").asLong() + "}"), owner, ALL).expect(200);
    }

    private Result createWarehouse(User user, String code) throws Exception {
        return call(post(WAREHOUSES).content("{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}"),
                user, ALL).expect(201);
    }

    private Item ingredient(UUID restaurantId, String sku, String name, boolean track, boolean active) {
        Item item = new Item();
        item.initialize(restaurantId, clock.instant());
        item.setItemType(ItemType.INGREDIENT);
        item.setSku(sku);
        item.setName(name);
        item.setBaseUnit("g");
        item.setSalePrice(BigDecimal.ZERO);
        item.setCostPrice(BigDecimal.ZERO);item.setCostConfigured(true);
        item.setTrackInventory(track);
        item.setActive(active);
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);
        return items.saveAndFlush(item);
    }

    private User fixture(Set<String> featureCodes, boolean activeSubscription) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("INV" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Inventory test");
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
        subscription.setPackageId(packages.findByCode("PREMIUM").orElseThrow().getId());
        subscription.setStatus(activeSubscription ? SubscriptionStatus.ACTIVE : SubscriptionStatus.CANCELLED);
        subscription.setStartAt(clock.instant().minusSeconds(30));
        subscription.setEndAt(clock.instant().plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "TEST",
                featureCodes.stream().map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(),
                clock.instant()).toMap());
        subscriptions.saveAndFlush(subscription);
        return user;
    }

    private Result call(MockHttpServletRequestBuilder request, User user, Set<String> permissions) throws Exception {
        String role = jdbc.queryForObject("select r.code from roles r join users u on u.role_id=r.id where u.id=?",
                String.class, user.getId());
        CurrentUser principal = new CurrentUser(user.getId(), user.getRestaurantId(), user.getEmail(), role, permissions);
        var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
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

    private long balanceRows(UUID restaurantId) {
        return jdbc.queryForObject("select count(*) from inventory_balances where restaurant_id=?",
                Long.class, restaurantId);
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

        Result expect(int expected) {
            assertThat(status).describedAs(body.toString()).isEqualTo(expected);
            return this;
        }

        void error(int expected, String code) {
            expect(expected);
            assertThat(body.path("code").asText()).isEqualTo(code);
        }
    }
}
