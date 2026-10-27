package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.inventory.entity.InventoryBalance;
import com.possaas.modules.inventory.entity.StockDocument;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.entity.StockDocumentType;
import com.possaas.modules.inventory.entity.Warehouse;
import com.possaas.modules.inventory.repository.InventoryBalanceRepository;
import com.possaas.modules.inventory.repository.WarehouseRepository;
import com.possaas.modules.inventory.repository.StockDocumentRepository;
import com.possaas.modules.inventory.repository.StockDocumentItemRepository;
import com.possaas.modules.menu.entity.Item;
import com.possaas.modules.menu.entity.ItemType;
import com.possaas.modules.menu.entity.Recipe;
import com.possaas.modules.menu.entity.RecipeItem;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand;
import com.possaas.modules.order.application.port.OrderInventoryAllocationCommand.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL constraints are verified at statement and transaction-commit boundaries. */
@SpringBootTest(properties = {"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
        "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InventoryAllocationPersistenceIntegrationTest {
    private static final String SCHEMA = "it_allocation_db_" + UUID.randomUUID().toString().replace("-", "");
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

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired RestaurantRepository restaurants;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository items;
    @Autowired RecipeRepository recipes;
    @Autowired RecipeItemRepository recipeItems;
    @Autowired OrderRepository orders;
    @Autowired OrderItemRepository orderItems;
    @Autowired OrderItemRecipeSnapshotRepository snapshots;
    @Autowired OrderItemRecipeUsageRepository usages;
    @Autowired WarehouseRepository warehouses;
    @Autowired InventoryBalanceRepository balances;
    @Autowired StockDocumentRepository documents;
    @Autowired StockDocumentItemRepository documentItems;
    @Autowired OrderInventoryAllocationCommand inventory;
    @Autowired Clock clock;
    private TransactionTemplate tx;
    private Fixture fixture;

    @BeforeEach
    void setup() {
        assertThat(jdbc.queryForObject("select current_schema()", String.class)).isEqualTo(SCHEMA);
        tx = new TransactionTemplate(transactionManager);
        fixture = createFixture();
    }

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_allocation_db_[0-9a-f]{32}")) throw new IllegalStateException("Unexpected schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }

    @Test
    void allocationReferencesEnforceTenantWarehouseOrderAndExactSnapshotLine() {
        Fixture foreign = createFixture();
        reject(fixture, () -> insertHeader(fixture, fixture.order(), fixture.snapshot(), foreign.warehouse()),
                "fk_order_inventory_warehouse");
        Fixture sibling = anotherSource(fixture);
        reject(fixture, () -> insertHeader(fixture, sibling.order(), fixture.snapshot(), fixture.warehouse()),
                "fk_order_inventory_line");
        reject(fixture, () -> insertHeader(fixture, fixture.order(), sibling.snapshot(), fixture.warehouse()),
                "ck_order_inventory_identity");
        assertThat(allocationCount(fixture)).isZero();
    }

    @Test
    void oneAllocationPerItemAndOneReservationPerRecipeUsageAreDatabaseConstraints() {
        reject(fixture, () -> {
            UUID header = insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse());
            Usage usage = fixture.tracked().getFirst();
            insertDetail(fixture, header, usage, usage.quantity());
            insertDetail(fixture, header, usage, usage.quantity());
        }, "uq_inventory_reservation_");
        reserve(fixture);
        reject(fixture, () -> insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse()),
                "uq_order_inventory_item");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid=c.conrelid
                JOIN pg_namespace n ON n.oid=t.relnamespace
                WHERE n.nspname=? AND c.conname IN ('uq_order_inventory_item','uq_inventory_reservation_usage',
                    'uq_recipe_use_reservation') AND c.contype='u'
                """, Long.class, SCHEMA)).isEqualTo(3);
        assertThat(allocationCount(fixture)).isEqualTo(1);
        assertThat(reservationCount(fixture)).isEqualTo(2);
    }

    @Test
    void reservationMustMatchTrackedUsageQuantityAndSnapshotExactly() {
        reject(fixture, () -> {
            UUID header = insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse());
            insertDetail(fixture, header, fixture.tracked().getFirst(), new BigDecimal("100.001"));
        }, "ck_inventory_reservation_identity");
        reject(fixture, () -> {
            UUID header = insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse());
            insertDetail(fixture, header, fixture.untracked(), fixture.untracked().quantity());
        }, "ck_inventory_reservation_identity");
        Fixture sibling = anotherSource(fixture);
        reject(fixture, () -> {
            UUID header = insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse());
            insertDetail(fixture, header, sibling.tracked().getFirst(), sibling.tracked().getFirst().quantity());
        }, "ck_inventory_reservation_identity");
        assertThat(allocationCount(fixture)).isZero();
        assertThat(reservationCount(fixture)).isZero();
    }

    @Test
    void incompleteTrackedSetFailsAtCommitAndRollsBackHeaderAndDetails() {
        AtomicBoolean statementsFinished = new AtomicBoolean();
        reject(fixture, () -> {
            UUID header = insertHeader(fixture, fixture.order(), fixture.snapshot(), fixture.warehouse());
            insertDetail(fixture, header, fixture.tracked().getFirst(), fixture.tracked().getFirst().quantity());
            statementsFinished.set(true);
        }, "ck_order_inventory_history");
        assertThat(statementsFinished).isTrue();
        assertThat(allocationCount(fixture)).isZero();
        assertThat(reservationCount(fixture)).isZero();
    }

    @Test
    void committedIdentityAndDetailsCannotChangeBeDeletedOrReceiveLateIngredients() {
        reserve(fixture);
        UUID allocation = allocationId(fixture);
        UUID reservation = reservationId(fixture);
        reject(fixture, () -> jdbc.update("update order_item_inventory_allocations set order_quantity_snapshot=2 where id=?", allocation),
                "ck_order_inventory_identity");
        reject(fixture, () -> jdbc.update("delete from order_item_inventory_allocations where id=?", allocation),
                "ck_order_inventory_identity");
        reject(fixture, () -> jdbc.update("update inventory_reservations set quantity=1 where id=?", reservation),
                "ck_inventory_reservation_identity");
        reject(fixture, () -> jdbc.update("delete from inventory_reservations where id=?", reservation),
                "ck_inventory_reservation_identity");
        reject(fixture, () -> insertDetail(fixture, allocation, fixture.tracked().getFirst(), fixture.tracked().getFirst().quantity()),
                "ck_inventory_reservation_identity");
        assertThat(reservationCount(fixture)).isEqualTo(2);
    }

    @Test
    void consumedHeaderRequiresWholeLedgerAndReservedHeaderCannotCommitRecipeUse() {
        reserve(fixture);
        AtomicBoolean statementsFinished = new AtomicBoolean();
        reject(fixture, () -> {
            jdbc.update("update order_item_inventory_allocations set status='CONSUMED',consumed_at=? where id=?",
                    timestamp(), allocationId(fixture));
            statementsFinished.set(true);
        }, "ck_order_inventory_history");
        assertThat(statementsFinished).isTrue();
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), reservationId(fixture),
                fixture.tracked().getFirst(), new BigDecimal("-100"), "g", null, "ORDER_ITEM"),
                "ck_order_inventory_history");
        assertThat(status(fixture)).isEqualTo("RESERVED");
        assertThat(recipeUseCount(fixture)).isZero();
        assertCache(fixture, "100.000");
    }

    @Test
    void recipeLedgerRequiresExactReservationTenantOrderQuantityUnitAndNullCost() {
        reserve(fixture);
        UUID detail = reservationId(fixture);
        Usage usage = fixture.tracked().getFirst();
        Fixture foreign = createFixture(); reserve(foreign);
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), reservationId(foreign),
                usage, new BigDecimal("-100"), "g", null, "ORDER_ITEM"), "fk_inventory_recipe_use_reservation");
        Fixture sibling = anotherSource(fixture);
        reject(fixture, () -> appendRecipeUse(fixture, sibling.order(), sibling.item(), detail,
                usage, new BigDecimal("-100"), "g", null, "ORDER_ITEM"), "ck_inventory_transaction_source");
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), detail,
                usage, new BigDecimal("-99"), "g", null, "ORDER_ITEM"), "ck_inventory_transaction_source");
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), detail,
                usage, new BigDecimal("-100"), "kg", null, "ORDER_ITEM"), "ck_inventory_transaction_source");
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), detail,
                usage, new BigDecimal("-100"), "g", BigDecimal.ZERO, "ORDER_ITEM"), "ck_inventory_transaction_source");
        reject(fixture, () -> appendRecipeUse(fixture, fixture.order(), fixture.item(), detail,
                usage, new BigDecimal("-100"), "g", null, "STOCK_DOCUMENT"), "ck_inventory_transaction_source");
        assertThat(recipeUseCount(fixture)).isZero();
    }

    @Test
    void stockDocumentLedgerStillRequiresValidDocumentReferencesAndNonNullCost() {
        UUID[] source = tx.execute(ignored -> {
            restaurants.findByIdForUpdate(fixture.tenant()).orElseThrow();
            StockDocument document = new StockDocument();
            document.initialize(fixture.tenant(), fixture.warehouse(), UUID.randomUUID().toString(),
                    StockDocumentType.RECEIPT, UUID.randomUUID().toString(), "0".repeat(64), null,
                    fixture.actor().userId(), clock.instant()); document = documents.saveAndFlush(document);
            Usage usage = fixture.tracked().getFirst();
            StockDocumentItem detail = new StockDocumentItem();
            detail.initialize(fixture.tenant(), document.getId(), usage.ingredientId(), usage.sku(), usage.name(), "g",
                    BigDecimal.ONE, null, BigDecimal.TEN, null, clock.instant()); detail = documentItems.saveAndFlush(detail);
            return new UUID[]{document.getId(), detail.getId()};
        });
        reject(fixture, () -> jdbc.update("""
                INSERT INTO inventory_transactions (restaurant_id,warehouse_id,item_id,transaction_type,source_type,
                    stock_document_id,stock_document_item_id,quantity_change,before_quantity,after_quantity,
                    unit,unit_cost,idempotency_key,created_by,created_at)
                VALUES (?,?,?,'RECEIPT','STOCK_DOCUMENT',?,?,1,1000,1001,'g',NULL,?,?,?)
                """, fixture.tenant(), fixture.warehouse(), fixture.tracked().getFirst().ingredientId(), source[0], source[1],
                UUID.randomUUID().toString(), fixture.actor().userId(), timestamp()), "ck_inventory_transaction_source");
        reject(fixture, () -> jdbc.update("""
                INSERT INTO inventory_transactions (restaurant_id,warehouse_id,item_id,transaction_type,source_type,
                    quantity_change,before_quantity,after_quantity,unit,unit_cost,idempotency_key,created_by,created_at)
                VALUES (?,?,?,'RECEIPT','STOCK_DOCUMENT',1,1000,1001,'g',10,?,?,?)
                """, fixture.tenant(), fixture.warehouse(), fixture.tracked().getFirst().ingredientId(),
                UUID.randomUUID().toString(), fixture.actor().userId(), timestamp()), "ck_inventory_transaction_source");
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",
                Long.class, source[0])).isZero();
    }

    @Test
    void consumedLedgerRemainsAppendOnlyAndUniquePerReservationWithDifferentIdempotencyKey() {
        reserve(fixture);
        locked(fixture, () -> inventory.consume(fixture.actor(), fixture.order(), List.of(fixture.item()), clock.instant(), null));
        assertThat(recipeUseCount(fixture)).isEqualTo(2);
        reject(fixture, () -> jdbc.update("""
                INSERT INTO inventory_transactions (restaurant_id,warehouse_id,item_id,transaction_type,source_type,
                    order_id,order_item_id,reservation_id,recipe_usage_id,quantity_change,before_quantity,after_quantity,
                    unit,unit_cost,idempotency_key,created_by,created_at)
                SELECT restaurant_id,warehouse_id,item_id,transaction_type,source_type,order_id,order_item_id,
                    reservation_id,recipe_usage_id,quantity_change,before_quantity,after_quantity,unit,unit_cost,?,created_by,created_at
                FROM inventory_transactions WHERE reservation_id=?
                """, UUID.randomUUID().toString(), reservationId(fixture)), "uq_recipe_use_reservation");
        reject(fixture, () -> jdbc.update("update inventory_transactions set unit_cost=0 where order_item_id=?", fixture.item()),
                "append-only");
        reject(fixture, () -> jdbc.update("delete from inventory_transactions where order_item_id=?", fixture.item()),
                "append-only");
        assertThat(recipeUseCount(fixture)).isEqualTo(2);
    }

    @Test
    void corruptedReservedCacheFailsValidationAndConsumeWithoutRepairOrPartialEffects() {
        reserve(fixture);
        jdbc.update("update inventory_balances set reserved_quantity=99 where restaurant_id=? and warehouse_id=? and item_id=?",
                fixture.tenant(), fixture.warehouse(), fixture.tracked().getFirst().ingredientId());
        assertCode(() -> locked(fixture, () -> inventory.validate(fixture.tenant(), fixture.order(),
                List.of(new LineState(fixture.item(), "PENDING", true, BigDecimal.ONE, "portion")))),
                "INVENTORY_RESERVATION_INVARIANT_VIOLATION");
        assertCode(() -> locked(fixture, () -> inventory.consume(fixture.actor(), fixture.order(),
                List.of(fixture.item()), clock.instant(), null)), "INVENTORY_RESERVATION_INVARIANT_VIOLATION");
        assertThat(status(fixture)).isEqualTo("RESERVED");
        assertThat(recipeUseCount(fixture)).isZero();
        assertCache(fixture, "99.000");
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where restaurant_id=? and item_id=?",
                BigDecimal.class, fixture.tenant(), fixture.tracked().getFirst().ingredientId())).isEqualByComparingTo("1000");
    }

    @Test
    void terminalReplaysValidateHistoryAndRejectConsumeAfterReleaseOrReleaseAfterConsume() {
        reserve(fixture);
        locked(fixture, () -> inventory.release(fixture.actor(), fixture.order(), List.of(fixture.item()), clock.instant(), null));
        locked(fixture, () -> inventory.release(fixture.actor(), fixture.order(), List.of(fixture.item()), clock.instant(), null));
        assertCode(() -> locked(fixture, () -> inventory.consume(fixture.actor(), fixture.order(), List.of(fixture.item()),
                clock.instant(), null)), "INVALID_INVENTORY_ALLOCATION_TRANSITION");
        assertThat(status(fixture)).isEqualTo("RELEASED"); assertThat(recipeUseCount(fixture)).isZero(); assertCache(fixture, "0.000");
        Fixture second = anotherSource(fixture); reserve(second);
        locked(second, () -> inventory.consume(second.actor(), second.order(), List.of(second.item()), clock.instant(), null));
        locked(second, () -> inventory.consume(second.actor(), second.order(), List.of(second.item()), clock.instant(), null));
        assertCode(() -> locked(second, () -> inventory.release(second.actor(), second.order(), List.of(second.item()),
                clock.instant(), null)), "INVALID_INVENTORY_ALLOCATION_TRANSITION");
        assertThat(recipeUseCount(second)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='ORDER_ITEM_INVENTORY_CONSUMED'",
                Long.class, second.tenant())).isEqualTo(1);
    }

    private Fixture createFixture() {
        return tx.execute(ignored -> {
            Instant now = clock.instant();
            Restaurant restaurant = new Restaurant(); restaurant.setCode("DB" + UUID.randomUUID().toString().replace("-", ""));
            restaurant.setName("Allocation persistence"); restaurant = restaurants.saveAndFlush(restaurant);
            User user = new User(); user.setRestaurantId(restaurant.getId());
            user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
            user.setName("Owner"); user.setEmail(UUID.randomUUID() + "@test.example"); user.setPasswordHash("fixture"); user = users.saveAndFlush(user);
            RestaurantSubscription subscription = new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId());
            subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId()); subscription.setStatus(SubscriptionStatus.ACTIVE);
            subscription.setStartAt(now.minusSeconds(60)); subscription.setEndAt(now.plusSeconds(7200));
            subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND");
            subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1, "PRO", List.of(
                    new SubscriptionFeatureSnapshot.FeatureGrant("ORDER_MANAGEMENT", Map.of()),
                    new SubscriptionFeatureSnapshot.FeatureGrant("RECIPE_MANAGEMENT", Map.of()),
                    new SubscriptionFeatureSnapshot.FeatureGrant("INVENTORY_MANAGEMENT", Map.of())), now).toMap());
            subscriptions.saveAndFlush(subscription);
            CurrentUser actor = new CurrentUser(user.getId(), restaurant.getId(), user.getEmail(), "OWNER", Set.of());
            Item menu = item(restaurant.getId(), "Menu", "portion", ItemType.MENU_ITEM, false);
            Item first = item(restaurant.getId(), "First", "g", ItemType.INGREDIENT, true);
            Item second = item(restaurant.getId(), "Second", "g", ItemType.INGREDIENT, true);
            Item untracked = item(restaurant.getId(), "Untracked", "g", ItemType.INGREDIENT, false);
            Recipe recipe = new Recipe(); recipe.initialize(restaurant.getId(), menu.getId(), 1, BigDecimal.ONE, "portion", now);
            recipe = recipes.saveAndFlush(recipe);
            for (Item ingredient : List.of(first, second, untracked)) {
                RecipeItem component = new RecipeItem(); component.initialize(restaurant.getId(), recipe.getId(), ingredient,
                        BigDecimal.ONE, BigDecimal.ZERO, now); recipeItems.saveAndFlush(component);
            }
            Warehouse warehouse = new Warehouse(); warehouse.initialize(restaurant.getId(), "MAIN", "Main", true, now);
            warehouse = warehouses.saveAndFlush(warehouse);
            for (Item ingredient : List.of(first, second)) {
                InventoryBalance balance = new InventoryBalance(); balance.initialize(restaurant.getId(), warehouse.getId(),
                        ingredient.getId(), BigDecimal.ZERO.setScale(3), now); balance.applyQuantityChange(new BigDecimal("1000.000"), now);
                balances.saveAndFlush(balance);
            }
            return createSource(actor, warehouse.getId(), menu.getId(), recipe.getId(),
                    List.of(new Usage(null, first.getId(), first.getSku(), first.getName(), "g", new BigDecimal("100.000")),
                            new Usage(null, second.getId(), second.getSku(), second.getName(), "g", new BigDecimal("50.000"))),
                    new Usage(null, untracked.getId(), untracked.getSku(), untracked.getName(), "g", new BigDecimal("7.000")));
        });
    }

    private Fixture anotherSource(Fixture template) {
        return tx.execute(ignored -> {
            restaurants.findByIdForUpdate(template.tenant()).orElseThrow();
            return createSource(template.actor(), template.warehouse(), template.menu(), template.recipe(), template.tracked(), template.untracked());
        });
    }

    private Fixture createSource(CurrentUser actor, UUID warehouse, UUID menu, UUID recipe, List<Usage> definitions, Usage untracked) {
        Instant now = clock.instant();
        Order order = new Order(); order.initialize(actor.restaurantId(), now); order.setOrderCode(UUID.randomUUID().toString());
        order.setServiceType(ServiceType.TAKEAWAY); order.setSourceChannel(SourceChannel.CASHIER); order.setCurrencyCode("VND");
        order.setIdempotencyKey(UUID.randomUUID().toString()); order.setRequestHash("0".repeat(64)); order.setCreatedBy(actor.userId());
        order = orders.saveAndFlush(order);
        OrderItem line = new OrderItem(); line.initialize(actor.restaurantId(), now); line.setOrderId(order.getId());
        line.setItemId(menu); line.setItemName("Menu snapshot"); line.setUnit("portion"); line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(BigDecimal.ZERO); line.setLineTotal(BigDecimal.ZERO); line.setLineNumber(1); line = orderItems.saveAndFlush(line);
        OrderItemRecipeSnapshot snapshot = snapshots.saveAndFlush(new OrderItemRecipeSnapshot(line, RecipeSnapshotStatus.CAPTURED,
                recipe, 1, BigDecimal.ONE, "portion", now));
        List<Usage> tracked = new ArrayList<>();
        for (Usage definition : definitions) tracked.add(captureUsage(actor.restaurantId(), snapshot.getId(), definition, true, now));
        Usage capturedUntracked = captureUsage(actor.restaurantId(), snapshot.getId(), untracked, false, now);
        return new Fixture(actor, order.getId(), line.getId(), snapshot.getId(), warehouse, menu, recipe, List.copyOf(tracked), capturedUntracked);
    }

    private Usage captureUsage(UUID tenant, UUID snapshot, Usage definition, boolean tracked, Instant now) {
        OrderItemRecipeUsage usage = usages.saveAndFlush(new OrderItemRecipeUsage(tenant, snapshot, definition.ingredientId(),
                definition.name(), definition.sku(), definition.unit(), definition.quantity(), BigDecimal.ZERO, tracked, definition.quantity(), now));
        return new Usage(usage.getId(), definition.ingredientId(), definition.sku(), definition.name(), definition.unit(), definition.quantity());
    }

    private Item item(UUID tenant, String name, String unit, ItemType type, boolean tracked) {
        Item item = new Item(); item.initialize(tenant, clock.instant()); item.setName(name); item.setBaseUnit(unit);
        item.setItemType(type); item.setTrackInventory(tracked); item.setSalePrice(BigDecimal.ZERO); item.setCostPrice(BigDecimal.TEN);item.setCostConfigured(true);
        return items.saveAndFlush(item);
    }

    private void reserve(Fixture source) {
        locked(source, () -> {
            inventory.reserve(source.actor(), source.order(), List.of(new AcceptedItem(source.item(), source.snapshot(), "CAPTURED",
                    BigDecimal.ONE, "portion", source.tracked())), clock.instant(), null);
            jdbc.update("update order_items set sent_to_kitchen_at=? where id=?", timestamp(), source.item());
            jdbc.update("update orders set status='CONFIRMED',confirmed_at=? where id=?", timestamp(), source.order());
        });
    }

    private UUID insertHeader(Fixture source, UUID order, UUID snapshot, UUID warehouse) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO order_item_inventory_allocations (id,restaurant_id,order_id,order_item_id,recipe_snapshot_id,
                    status,warehouse_id,order_quantity_snapshot,order_unit_snapshot,reserved_at,created_at,updated_at)
                VALUES (?,?,?,?,?,'RESERVED',?,1,'portion',?,?,?)
                """, id, source.tenant(), order, source.item(), snapshot, warehouse, timestamp(), timestamp(), timestamp());
        return id;
    }

    private void insertDetail(Fixture source, UUID allocation, Usage usage, BigDecimal quantity) {
        jdbc.update("""
                INSERT INTO inventory_reservations (restaurant_id,allocation_id,recipe_snapshot_id,recipe_usage_id,
                    warehouse_id,ingredient_id,quantity,ingredient_sku_snapshot,ingredient_name_snapshot,ingredient_unit_snapshot,created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, source.tenant(), allocation, source.snapshot(), usage.recipeUsageId(), source.warehouse(), usage.ingredientId(),
                quantity, usage.sku(), usage.name(), usage.unit(), timestamp());
    }

    private void appendRecipeUse(Fixture source, UUID order, UUID item, UUID reservation, Usage usage,
            BigDecimal change, String unit, BigDecimal cost, String sourceType) {
        jdbc.update("""
                INSERT INTO inventory_transactions (restaurant_id,warehouse_id,item_id,transaction_type,source_type,
                    order_id,order_item_id,reservation_id,recipe_usage_id,quantity_change,before_quantity,after_quantity,
                    unit,unit_cost,idempotency_key,created_by,created_at)
                VALUES (?,?,?,'RECIPE_USE',?,?,?,?,?,?,1000,?,?,?, ?,?,?)
                """, source.tenant(), source.warehouse(), usage.ingredientId(), sourceType, order, item, reservation,
                usage.recipeUsageId(), change, new BigDecimal("1000").add(change), unit, cost, UUID.randomUUID().toString(),
                source.actor().userId(), timestamp());
    }

    private void locked(Fixture source, Runnable action) {
        tx.executeWithoutResult(ignored -> { restaurants.findByIdForUpdate(source.tenant()).orElseThrow(); action.run(); });
    }
    private void reject(Fixture source, Runnable action, String constraint) {
        assertThatThrownBy(() -> locked(source, action)).isInstanceOf(RuntimeException.class).hasStackTraceContaining(constraint);
    }
    private static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable action, String code) {
        assertThatThrownBy(action).isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }
    private UUID allocationId(Fixture source) { return jdbc.queryForObject("select id from order_item_inventory_allocations where order_item_id=?", UUID.class, source.item()); }
    private UUID reservationId(Fixture source) { return jdbc.queryForObject("select id from inventory_reservations where recipe_usage_id=?", UUID.class, source.tracked().getFirst().recipeUsageId()); }
    private String status(Fixture source) { return jdbc.queryForObject("select status from order_item_inventory_allocations where order_item_id=?", String.class, source.item()); }
    private long allocationCount(Fixture source) { return jdbc.queryForObject("select count(*) from order_item_inventory_allocations where order_item_id=?", Long.class, source.item()); }
    private long reservationCount(Fixture source) { return jdbc.queryForObject("select count(*) from inventory_reservations where recipe_snapshot_id=?", Long.class, source.snapshot()); }
    private long recipeUseCount(Fixture source) { return jdbc.queryForObject("select count(*) from inventory_transactions where order_item_id=?", Long.class, source.item()); }
    private void assertCache(Fixture source, String quantity) {
        assertThat(jdbc.queryForObject("select reserved_quantity from inventory_balances where restaurant_id=? and item_id=?",
                BigDecimal.class, source.tenant(), source.tracked().getFirst().ingredientId())).isEqualByComparingTo(quantity);
    }
    private Timestamp timestamp() { return Timestamp.from(clock.instant()); }
    private record Fixture(CurrentUser actor, UUID order, UUID item, UUID snapshot, UUID warehouse, UUID menu, UUID recipe,
            List<Usage> tracked, Usage untracked) { UUID tenant() { return actor.restaurantId(); } }
}
