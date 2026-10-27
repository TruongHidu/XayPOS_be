package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.inventory.repository.*;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.order.repository.OrderItemSubmissionRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.entity.RestaurantTable;
import com.possaas.modules.table.repository.RestaurantTableRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.*;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({OrderInventoryAllocationIntegrationTest.FixedTime.class, com.possaas.support.CommittedRealtimeEvents.Config.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderInventoryAllocationIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-20T08:00:00Z");
    static final String SCHEMA="it_order_allocation_"+UUID.randomUUID().toString().replace("-","");
    static final Set<String> ORDER=Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE","ORDER_CANCEL",
        "ORDER_MARK_SERVED","ORDER_COMPLETE","KITCHEN_VIEW","KITCHEN_MARK_READY","KITCHEN_START_COOKING",
        "TABLE_OPEN","TABLE_CLOSE","TABLE_TRANSFER","PAYMENT_CREATE","PAYMENT_CONFIRM");
    static final Set<String> ALL;
    static {var p=new HashSet<>(ORDER);p.addAll(Set.of("MENU_VIEW","MENU_UPDATE","MENU_CREATE","INGREDIENT_CREATE","INGREDIENT_VIEW","RECIPE_VIEW","INVENTORY_VIEW","WAREHOUSE_CREATE",
        "WAREHOUSE_UPDATE","WAREHOUSE_DELETE","INVENTORY_RECEIVE","INVENTORY_ISSUE","INVENTORY_ADJUST",
        "INVENTORY_THRESHOLD_UPDATE","INGREDIENT_UPDATE","INGREDIENT_DELETE"));ALL=Set.copyOf(p);}
    static final List<String> FEATURES=List.of("MENU_MANAGEMENT","ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT",
        "RECIPE_MANAGEMENT","INVENTORY_MANAGEMENT","KITCHEN_DISPLAY","PAYMENT_MANAGEMENT","LOW_STOCK_ALERT");
    @TestConfiguration static class FixedTime {@Bean @Primary Clock allocationClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA)
            :base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url);p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;@Autowired UserRepository users;@Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages;@Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository items;@Autowired RecipeRepository recipes;@Autowired RecipeItemRepository recipeItems;
    @Autowired RestaurantTableRepository tables;
    @MockitoSpyBean AuditService audit;@MockitoSpyBean InventoryTransactionWriteRepository ledger;
    @MockitoSpyBean OrderItemInventoryAllocationRepository allocations;
    @MockitoSpyBean InventoryReservationRepository reservations;
    @MockitoSpyBean OrderItemSubmissionRepository submissions;
    @Autowired com.possaas.support.CommittedRealtimeEvents committed;
    Restaurant restaurant;User user;RestaurantSubscription subscription;Item menu,ingredient;Recipe recipe;UUID warehouse;

    @BeforeEach void setup() {
        committed.clear();
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
        restaurant=new Restaurant();restaurant.setCode("ALLOC"+UUID.randomUUID().toString().replace("-",""));restaurant.setName("Allocation test");restaurant=restaurants.saveAndFlush(restaurant);
        user=new User();user.setRestaurantId(restaurant.getId());user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Staff");user.setEmail(UUID.randomUUID()+"@test.example");user.setPasswordHash("fixture");user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription();subscription.setRestaurantId(restaurant.getId());subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);subscription.setStartAt(NOW.minusSeconds(60));subscription.setEndAt(NOW.plusSeconds(7200));
        subscription.setPriceAmount(BigDecimal.ZERO);subscription.setCurrencyCode("VND");subscription.setFeatureSnapshot(features(FEATURES));subscription=subscriptions.saveAndFlush(subscription);
        ingredient=item("Ingredient",ItemType.INGREDIENT,"g",true);menu=item("Food",ItemType.MENU_ITEM,"portion",false);
        recipe=recipe(menu,ingredient,"1000","5");warehouse=null;
    }
    @AfterEach void resetFailures(){reset(audit,ledger,submissions);}
    @AfterAll void cleanup(){if(!SCHEMA.matches("it_order_allocation_[0-9a-f]{32}"))throw new IllegalStateException();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}

    @Test void disabledDecisionRollbackHasNoCommittedEventAndToggleDoesNotDirtyOrderVersion() throws Exception {
        toggle(false).expect(200);
        String key=UUID.randomUUID().toString();var request=body(true,"1");
        doThrow(new IllegalStateException("forced decision audit failure")).when(audit).recordAll(anyList());
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(500);
        reset(audit);assertNoOrderArtifacts();assertThat(committed.orders).isEmpty();
        var accepted=call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(201);
        assertThat(committed.orders).hasSize(1);
        assertThat(committed.orders.getFirst().version()).isEqualTo(accepted.version());
        assertThat(Arrays.stream(committed.orders.getFirst().getClass().getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName)).doesNotContain("costPrice","recipeEnabled","costSource","ingredientId");
        toggle(true).expect(200);
        assertThat(detail(accepted).version()).isEqualTo(accepted.version());assertThat(committed.orders).hasSize(1);
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(200);
        assertThat(committed.orders).hasSize(1);assertThat(decision(accepted,0).body.path("costSourceSnapshot").asText()).isEqualTo("MANUAL");
    }
    @Test void draftCreationQuantityEditAndAppendNeverSnapshotOrReserveAndGetDoesNotWrite() throws Exception {
        var order=create(false,"3").expect(201);
        order=call(patch(path(order)+"/items/"+order.line(0)),Map.of("expectedVersion",order.version(),"quantity",4),ORDER).expect(200);
        order=call(post(path(order)+"/items"),Map.of("expectedVersion",order.version(),"items",List.of(line(menu.getId(),"1","draft"))),ORDER).expect(200);
        long before=count("audit_logs");var view=allocation(order,0).expect(200);
        assertThat(view.body.path("status").asText()).isEqualTo("NOT_SUBMITTED");assertThat(view.body.path("allocationId").isNull()).isTrue();
        assertThat(view.body.path("ingredients")).isEmpty();assertThat(view.cache).contains("no-store");
        for(String table:List.of("order_item_recipe_snapshots","order_item_inventory_allocations","inventory_reservations","inventory_balances","inventory_transactions"))assertThat(count(table)).as(table).isZero();
        assertThat(count("audit_logs")).isEqualTo(before);
        cancel(order).expect(200);assertThat(count("order_item_inventory_allocations")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"create","confirm","append-open"})
    void acceptanceRoutesReserveEveryNewlySubmittedLine(String route) throws Exception {
        stock("1000");Reply order;
        if(route.equals("create"))order=create(true,"3").expect(201);
        else {order=create(false,"3").expect(201);order=route.equals("confirm")?confirm(order).expect(200):append(order,"1").expect(200);}
        int size=route.equals("append-open")?2:1;
        assertThat(count("order_item_inventory_allocations")).isEqualTo(size);assertThat(count("inventory_reservations")).isEqualTo(size);
        assertThat(order.body.path("status").asText()).isEqualTo("CONFIRMED");
        var view=allocation(order,0).expect(200);assertThat(view.body.path("status").asText()).isEqualTo("RESERVED");
        assertThat(view.body.at("/ingredients/0/quantity").decimalValue()).isEqualByComparingTo("315");
        assertThat(view.body.path("reservedAt").asText()).isEqualTo(NOW.toString());
        balance(warehouse,"1000",size==2?"420":"315",size==2?"580":"685");assertInvariant();
        assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isEqualTo(size);
    }
    @Test void appendServingOrderReservesOnlyAddedLinesAndReplayKeepsOriginalQuantities() throws Exception {
        stock("2000");String key=UUID.randomUUID().toString();var body=body(true,"3");
        var first=call(post("/api/v1/orders").header("Idempotency-Key",key),body,ORDER).expect(201);
        replaceRecipe("2000");call(post("/api/v1/orders").header("Idempotency-Key",key),body,ORDER).expect(200);
        String appendKey=UUID.randomUUID().toString();var request=addition(first,"2");
        var added=call(post(path(first)+"/items").header("Idempotency-Key",appendKey),request,ORDER).expect(200);
        call(post(path(first)+"/items").header("Idempotency-Key",appendKey),request,ORDER).expect(200);
        assertThat(allocation(first,0).body.at("/ingredients/0/quantity").decimalValue()).isEqualByComparingTo("315");
        assertThat(allocation(added,1).body.at("/ingredients/0/quantity").decimalValue()).isEqualByComparingTo("420");
        balance(warehouse,"2000","735","1265");assertThat(count("inventory_reservations")).isEqualTo(2);assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isEqualTo(2);assertInvariant();
    }
    @Test void sharedIngredientDemandIsAggregatedBeforeCheckingAndFailureLeavesNoOrderOrKey() throws Exception {
        stock("500");var request=body(true,"3");request.put("items",List.of(line(menu.getId(),"3","first"),line(menu.getId(),"3","second")));
        String key=UUID.randomUUID().toString();long auditBefore=count("audit_logs");
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertNoOrderArtifacts();assertThat(count("audit_logs")).isEqualTo(auditBefore);balance(warehouse,"500","0","500");
        receive(warehouse,ingredient,"500","10");call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(201);
        balance(warehouse,"1000","630","370");assertInvariant();
    }
    @Test void missingSecondIngredientRollsBackNewTableSessionAndAllAcceptanceArtifacts() throws Exception {
        stock("1000");var missing=item("Missing",ItemType.INGREDIENT,"g",true);
        RecipeItem extra=new RecipeItem();extra.initialize(restaurant.getId(),recipe.getId(),missing,BigDecimal.ONE,BigDecimal.ZERO,NOW);recipeItems.saveAndFlush(extra);
        var table=table();var request=body(true,"3");request.put("serviceType","DINE_IN");request.put("sourceChannel","WAITER");request.put("tableId",table.getId());
        long audits=count("audit_logs");call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertNoOrderArtifacts();assertThat(count("table_sessions")).isZero();assertThat(count("audit_logs")).isEqualTo(audits);balance(warehouse,"1000","0","1000");
    }
    @Test void insufficientAppendPreservesExistingRootLinesReservationAndIdempotency() throws Exception {
        stock("400");var first=create(true,"3").expect(201);var before=allocation(first,0).body;long audits=count("audit_logs");
        String key=UUID.randomUUID().toString();var request=addition(first,"1");
        call(post(path(first)+"/items").header("Idempotency-Key",key),request,ORDER).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(detail(first).body).isEqualTo(first.body);assertThat(allocation(first,0).body).isEqualTo(before);
        assertThat(count("order_item_submissions")).isZero();assertThat(count("audit_logs")).isEqualTo(audits);balance(warehouse,"400","315","85");
        receive(warehouse,ingredient,"100","10");call(post(path(first)+"/items").header("Idempotency-Key",key),request,ORDER).expect(200);assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"inventory","recipe","no-recipe","untracked"})
    void durableSkippedDecisionsDoNotRequireWarehouseAndNeverAcquireStockLater(String reason) throws Exception {
        String expected=switch(reason){case "inventory"->"INVENTORY_FEATURE_DISABLED";case "recipe"->"SNAPSHOT_FEATURE_DISABLED";case "no-recipe"->"SNAPSHOT_RECIPE_DISABLED";default->"NO_TRACKED_INGREDIENTS";};
        if(reason.equals("inventory"))withoutFeature("INVENTORY_MANAGEMENT");
        if(reason.equals("recipe"))withoutFeature("RECIPE_MANAGEMENT");
        if(reason.equals("no-recipe")){recipe.deactivate(NOW);recipes.saveAndFlush(recipe);menu=items.findById(menu.getId()).orElseThrow();menu.setRecipeEnabled(false);menu=items.saveAndFlush(menu);}
        if(reason.equals("untracked")){ingredient.setTrackInventory(false);ingredient=items.saveAndFlush(ingredient);}
        var order=create(true,"3").expect(201);assertThat(count("warehouses")).isZero();assertThat(count("inventory_reservations")).isZero();
        setFeatures(FEATURES);ingredient=items.findById(ingredient.getId()).orElseThrow();ingredient.setTrackInventory(true);ingredient=items.saveAndFlush(ingredient);stock("1000");
        if(reason.equals("no-recipe")){replaceRecipe("1000");toggle(true).expect(200);}
        var view=allocation(order,0).expect(200);assertThat(view.body.path("status").asText()).isEqualTo("SKIPPED");assertThat(view.body.path("skipReason").asText()).isEqualTo(expected);assertThat(view.body.path("ingredients")).isEmpty();
        var cooking=single(order,0,"start-cooking").expect(200);balance(warehouse,"1000","0","1000");assertThat(recipeUses()).isZero();assertThat(events("ORDER_ITEM_INVENTORY_SKIPPED")).isEqualTo(1);
        var added=append(cooking,"1").expect(200);assertThat(allocation(added,1).expect(200).body.path("status").asText()).isEqualTo("RESERVED");balance(warehouse,"1000","105","895");assertInvariant();
    }
    @Test void trackedSnapshotRequiresValidDefaultAndSparseZeroBalanceCannotProvideStock() throws Exception {
        create(true,"3").error(409,"INVENTORY_DEFAULT_WAREHOUSE_REQUIRED");assertNoOrderArtifacts();
        warehouse=createWarehouse("MAIN").id();long rows=count("inventory_balances"),audits=count("audit_logs");
        var zero=call(get("/api/v1/inventory/warehouses/"+warehouse+"/balances/"+ingredient.getId()),null,ALL).expect(200);
        assertThat(zero.body.path("configured").asBoolean()).isFalse();for(String field:List.of("quantity","reservedQuantity","availableQuantity"))assertThat(zero.body.path(field).decimalValue()).isEqualByComparingTo("0");
        assertThat(count("inventory_balances")).isEqualTo(rows);assertThat(count("audit_logs")).isEqualTo(audits);
        create(true,"3").error(409,"INSUFFICIENT_AVAILABLE_STOCK");assertThat(count("inventory_balances")).isZero();assertNoOrderArtifacts();
    }
    @Test void consumeUsesOwnReservationEvenWhenAvailableIsZeroAndNeverConsumesAgain() throws Exception {
        stock("315");var order=create(true,"3").expect(201);balance(warehouse,"315","315","0");
        var cooking=single(order,0,"start-cooking").expect(200);balance(warehouse,"0","0","0");
        single(order,0,"start-cooking").expect(200);var ready=single(cooking,0,"mark-ready").expect(200);var served=single(ready,0,"mark-served").expect(200);
        bulk(served,"mark-served").expect(200);assertThat(recipeUses()).isEqualTo(1);assertThat(events("ORDER_ITEM_INVENTORY_CONSUMED")).isEqualTo(1);
        var consumed=allocation(served,0).expect(200);assertThat(consumed.body.path("status").asText()).isEqualTo("CONSUMED");assertThat(consumed.body.path("consumedAt").asText()).isEqualTo(NOW.toString());assertInvariant();
    }
    @Test void mixedBulkServedConsumesOnlyPendingAndReplayDoesNotAddLedger() throws Exception {
        stock("2000");var request=body(true,"1");request.put("items",java.util.stream.IntStream.range(0,4).mapToObj(i->line(menu.getId(),"1","line"+i)).toList());
        var order=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).expect(201);
        order=single(order,1,"start-cooking").expect(200);order=single(order,2,"start-cooking").expect(200);order=single(order,2,"mark-ready").expect(200);
        order=single(order,3,"start-cooking").expect(200);order=single(order,3,"mark-ready").expect(200);order=single(order,3,"mark-served").expect(200);
        var served=bulk(order,"mark-served").expect(200);bulk(order,"mark-served").expect(200);
        assertThat(recipeUses()).isEqualTo(4);balance(warehouse,"1580","0","1580");
        for(int i=0;i<4;i++){assertThat(served.body.at("/items/"+i+"/status").asText()).isEqualTo("SERVED");assertThat(allocation(served,i).body.path("status").asText()).isEqualTo("CONSUMED");}assertInvariant();
    }
    @Test void paidOrderWithoutKdsConsumesThroughBulkServingAndCompleteDoesNotDeductAgain() throws Exception {
        stock("1000");withoutFeature("KITCHEN_DISPLAY");var order=create(true,"3").expect(201);
        var paid=call(post(path(order)+"/payments").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("expectedOrderVersion",order.version(),"paymentMethod","CASH","amount",order.body.path("totalAmount").decimalValue()),ORDER).expect(201);
        order=detail(order).expect(200);assertThat(order.body.path("paymentStatus").asText()).isEqualTo("PAID");assertThat(recipeUses()).isZero();balance(warehouse,"1000","315","685");
        order=bulk(order,"mark-served").expect(200);var completed=call(post(path(order)+"/complete"),Map.of("expectedVersion",order.version()),ORDER).expect(200);
        assertThat(completed.body.path("status").asText()).isEqualTo("COMPLETED");balance(warehouse,"685","0","685");assertThat(recipeUses()).isEqualTo(1);assertInvariant();
    }
    @Test void downgradeAndRecipeRenameNeverChangeReservedUsageOrBlockConsume() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);var before=allocation(order,0).body;
        replaceRecipe("2000");ingredient=items.findById(ingredient.getId()).orElseThrow();ingredient.setName("Renamed");ingredient.setSku("NEW");ingredient.setCostPrice(new BigDecimal("99"));ingredient.setCostConfigured(true);ingredient=items.saveAndFlush(ingredient);
        setFeatures(FEATURES.stream().filter(f->!Set.of("INVENTORY_MANAGEMENT","RECIPE_MANAGEMENT").contains(f)).toList());
        single(order,0,"start-cooking").expect(200);balanceDb(warehouse,"685","0");assertThat(recipeUses()).isEqualTo(1);
        setFeatures(FEATURES);var after=allocation(order,0).expect(200);assertThat(after.body.path("ingredients")).isEqualTo(before.path("ingredients"));assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"line","root","last"})
    void cancelPendingReleasesExactlyOnceWithoutChangingPhysical(String route) throws Exception {
        stock("1000");var order=create(true,"3").expect(201);
        if(route.equals("line"))order=append(order,"1").expect(200);
        var result=route.equals("root")?cancel(order):call(post(path(order)+"/items/"+order.line(0)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","cancel"),ORDER);
        result.expect(200);int remaining=route.equals("line")?105:0;balance(warehouse,"1000",String.valueOf(remaining),String.valueOf(1000-remaining));
        assertThat(allocation(order,0).body.path("status").asText()).isEqualTo("RELEASED");assertThat(events("ORDER_ITEM_INVENTORY_RELEASED")).isEqualTo(1);assertThat(recipeUses()).isZero();
        if(!route.equals("line")){cancel(result).expect(200);assertThat(events("ORDER_ITEM_INVENTORY_RELEASED")).isEqualTo(1);}
        assertInvariant();
    }
    @Test void rootCancelReleasesEveryReservationAndCookingCannotBeCancelled() throws Exception {
        stock("2000");var order=create(true,"3").expect(201);order=append(order,"2").expect(200);
        cancel(order).expect(200);balance(warehouse,"2000","0","2000");assertThat(events("ORDER_ITEM_INVENTORY_RELEASED")).isEqualTo(2);
        var other=create(true,"1").expect(201);var cooking=single(other,0,"start-cooking").expect(200);
        call(post(path(other)+"/items/"+other.line(0)+"/cancel"),Map.of("expectedVersion",cooking.version(),"reason","cannot cancel cooking"),ORDER).expect(409);
        assertThat(recipeUses()).isEqualTo(1);assertThat(allocation(other,0).body.path("status").asText()).isEqualTo("CONSUMED");assertInvariant();
    }
    @Test void defaultWarehouseChangesPinOldAllocationAndNewAppendUsesNewWarehouse() throws Exception {
        stock("1000");UUID firstWarehouse=warehouse;var first=create(true,"3").expect(201);
        var second=createWarehouse("SECOND").expect(201);receive(second.id(),ingredient,"1000","10");
        call(post("/api/v1/inventory/warehouses/"+second.id()+"/make-default"),Map.of("expectedVersion",second.version()),ALL).expect(200);
        var added=append(first,"2").expect(200);assertThat(allocation(added,0).body.path("warehouseId").asText()).isEqualTo(firstWarehouse.toString());assertThat(allocation(added,1).body.path("warehouseId").asText()).isEqualTo(second.id().toString());
        bulk(added,"mark-served").expect(200);balance(firstWarehouse,"685","0","685");balance(second.id(),"790","0","790");assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"ISSUE","ADJUSTMENT"})
    void stockDecreaseCannotSpendReservedAndFailedConfirmDoesNotPost(String type) throws Exception {
        stock("1000");create(true,"3").expect(201);var movement=movement(warehouse,ingredient,type,"700","10",type.equals("ADJUSTMENT")?"DECREASE":null).expect(201);
        call(post("/api/v1/inventory/stock-documents/"+movement.id()+"/confirm"),Map.of("expectedVersion",movement.version()),ALL).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(jdbc.queryForObject("select status from stock_documents where id=?",String.class,movement.id())).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("select count(*) from inventory_transactions where stock_document_id=?",Long.class,movement.id())).isZero();balance(warehouse,"1000","315","685");assertInvariant();
    }
    @Test void receiptLeavesReservedUnchangedAndWeightedAverageUsesPhysicalNotAvailable() throws Exception {
        stock("1000");create(true,"3").expect(201);var receipt=receive(warehouse,ingredient,"1000","30");
        balance(warehouse,"2000","315","1685");assertThat(items.findById(ingredient.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("20.00");
        call(post("/api/v1/inventory/stock-documents/"+receipt.id()+"/confirm"),Map.of("expectedVersion",receipt.version()),ALL).expect(200);
        balance(warehouse,"2000","315","1685");assertThat(items.findById(ingredient.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("20");assertInvariant();
    }
    @Test void lowStockRetainsPhysicalBasisWhenAvailableIsLowerThanThreshold() throws Exception {
        stock("1000");create(true,"3").expect(201);var old=balance(warehouse,"1000","315","685");
        call(patch("/api/v1/inventory/warehouses/"+warehouse+"/balances/"+ingredient.getId()+"/threshold"),Map.of("expectedVersion",old.version(),"minQuantity",800),ALL).expect(200);
        var view=balance(warehouse,"1000","315","685");assertThat(view.body.path("lowStock").asBoolean()).isFalse();
        var low=call(get("/api/v1/inventory/low-stock"),null,ALL).expect(200);assertThat(low.body.path("totalElements").asLong()).isZero();
    }
    @Test void twoOrdersCompetingForStockCannotOversell() throws Exception {
        stock("500");var results=race(()->create(true,"3"),()->create(true,"3"));
        assertThat(results.stream().map(Reply::status)).containsExactlyInAnyOrder(201,409);assertThat(results.stream().filter(r->r.status==409).findFirst().orElseThrow().body.path("code").asText()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(count("orders")).isEqualTo(1);balance(warehouse,"500","315","185");assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"ISSUE","ADJUSTMENT"})
    void reserveRacingStockDecreaseKeepsPhysicalReservedInvariant(String type) throws Exception {
        stock("500");var movement=movement(warehouse,ingredient,type,"300","10",type.equals("ADJUSTMENT")?"DECREASE":null).expect(201);
        var results=race(()->create(true,"3"),()->call(post("/api/v1/inventory/stock-documents/"+movement.id()+"/confirm"),Map.of("expectedVersion",movement.version()),ALL));
        assertThat(results.stream().map(Reply::status).filter(s->s==409).count()).isEqualTo(1);
        assertThat(results.stream().filter(r->r.status==409).findFirst().orElseThrow().body.path("code").asText()).isEqualTo("INSUFFICIENT_AVAILABLE_STOCK");assertInvariant();
    }
    @Test void consumeRacingCancelNeverBothConsumesAndReleases() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);var results=race(()->single(order,0,"start-cooking"),()->cancel(order));
        assertThat(results.stream().filter(r->r.status==200).count()).isEqualTo(1);assertThat(results.stream().filter(r->r.status==409).count()).isEqualTo(1);
        String state=allocation(order,0).body.path("status").asText();assertThat(state).isIn("CONSUMED","RELEASED");
        assertThat(events("ORDER_ITEM_INVENTORY_CONSUMED")+events("ORDER_ITEM_INVENTORY_RELEASED")).isEqualTo(1);
        balance(warehouse,state.equals("CONSUMED")?"685":"1000","0",state.equals("CONSUMED")?"685":"1000");assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"audit","ledger"})
    void failedBulkConsumeRollsBackItemsHistoryBalanceLedgerAllocationAndAudit(String failure) throws Exception {
        stock("2000");var order=create(true,"3").expect(201);order=append(order,"2").expect(200);var before=detail(order).body;
        long history=count("order_item_status_history"),audits=count("audit_logs"),transactions=count("inventory_transactions");
        if(failure.equals("ledger"))doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("forced ledger failure");}).when(ledger).append(any());
        else doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("forced inventory audit failure");}).when(audit).recordAll(anyList());
        bulk(order,"mark-served").expect(500);reset(audit,ledger);
        assertThat(detail(order).body).isEqualTo(before);assertThat(count("order_item_status_history")).isEqualTo(history);assertThat(count("audit_logs")).isEqualTo(audits);assertThat(count("inventory_transactions")).isEqualTo(transactions);
        balance(warehouse,"2000","525","1475");assertThat(allocation(order,0).body.path("status").asText()).isEqualTo("RESERVED");assertInvariant();
        bulk(order,"mark-served").expect(200);assertThat(recipeUses()).isEqualTo(2);assertInvariant();
    }
    @Test void ledgerQueryIncludesRecipeUseWithNullableDocumentAndUnvaluedCostAndFilters() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);single(order,0,"start-cooking").expect(200);
        var view=call(get("/api/v1/inventory/transactions").param("orderId",order.id().toString()).param("orderItemId",order.line(0).toString()).param("sourceType","ORDER_ITEM").param("transactionType","RECIPE_USE"),null,ALL).expect(200);
        assertThat(view.body.path("totalElements").asLong()).isEqualTo(1);var row=view.body.at("/content/0");
        assertThat(row.path("sourceType").asText()).isEqualTo("ORDER_ITEM");assertThat(row.path("orderId").asText()).isEqualTo(order.id().toString());assertThat(row.path("orderItemId").asText()).isEqualTo(order.line(0).toString());
        for(String field:List.of("stockDocumentId","documentCode","documentType","unitCost"))assertThat(row.path(field).isNull()).as(field).isTrue();
        assertThat(row.path("reservationId").asText()).isNotBlank();assertThat(row.path("quantityChange").decimalValue()).isEqualByComparingTo("-315");assertThat(row.path("beforeQuantity").decimalValue()).isEqualByComparingTo("1000");assertThat(row.path("afterQuantity").decimalValue()).isEqualByComparingTo("685");
        var document=call(get("/api/v1/inventory/transactions").param("sourceType","STOCK_DOCUMENT"),null,ALL).expect(200);assertThat(document.body.path("totalElements").asLong()).isEqualTo(1);assertThat(document.body.at("/content/0/unitCost").decimalValue()).isEqualByComparingTo("10");
    }
    @Test void ownerReadRequiresEveryPermissionFeatureAndCorrectTenantOrderItemAndDoesNotLeakIntoOrder() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);long audits=count("audit_logs");
        var before=allocation(order,0).expect(200);allocation(order,0).expect(200);assertThat(count("audit_logs")).isEqualTo(audits);assertThat(before.cache).contains("no-store");
        for(String permission:List.of("ORDER_VIEW","RECIPE_VIEW","INVENTORY_VIEW")){var allowed=new HashSet<>(ALL);allowed.remove(permission);call(get(allocationPath(order,0)),null,allowed).expect(403);}
        for(String feature:List.of("ORDER_MANAGEMENT","RECIPE_MANAGEMENT","INVENTORY_MANAGEMENT")){withoutFeature(feature);call(get(allocationPath(order,0)),null,ALL).expect(403);setFeatures(FEATURES);}
        var other=create(true,"1").expect(201);call(get("/api/v1/inventory/orders/"+order.id()+"/items/"+other.line(0)+"/allocation"),null,ALL).expect(404);
        assertThat(detail(order).body.toString()).doesNotContain("allocationId","reservationId","requiredQuantity","ingredients","unitCost");
        var kitchen=call(get("/api/v1/kitchen/items"),null,ORDER).expect(200);assertThat(kitchen.body.toString()).doesNotContain("allocationId","reservationId","requiredQuantity","ingredientId","ingredients","unitCost");
        var unauth=mvc.perform(get(allocationPath(order,0))).andReturn().getResponse();assertThat(unauth.getStatus()).isEqualTo(401);
        setup();call(get(allocationPath(order,0)),null,ALL).expect(404);
    }
    @Test void databaseRejectsChangesAndDeletesForCommittedAllocationAndLedger() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);var allocation=allocation(order,0).body;UUID id=UUID.fromString(allocation.path("allocationId").asText());UUID detail=UUID.fromString(allocation.at("/ingredients/0/reservationId").asText());
        assertThatThrownBy(()->jdbc.update("update inventory_reservations set quantity=1 where id=?",detail)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from inventory_reservations where id=?",detail)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("update order_item_inventory_allocations set warehouse_id=? where id=?",UUID.randomUUID(),id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from order_item_inventory_allocations where id=?",id)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        single(order,0,"start-cooking").expect(200);assertThatThrownBy(()->jdbc.update("update inventory_transactions set quantity_change=-1 where order_item_id=?",order.line(0))).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("delete from inventory_transactions where order_item_id=?",order.line(0))).isInstanceOf(org.springframework.dao.DataAccessException.class);assertInvariant();
    }
    @Test void batchAllocationQueriesStayBoundedWhenManyLinesShareIngredient() throws Exception {
        stock("10000");var request=body(true,"1");request.put("items",java.util.stream.IntStream.range(0,9).mapToObj(i->line(menu.getId(),"1","batch"+i)).toList());
        clearInvocations(allocations,reservations);var order=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).expect(201);
        verify(allocations,atMost(2)).findAllForUpdate(eq(restaurant.getId()),anyCollection());
        verify(reservations,atMost(3)).findAllForAllocations(eq(restaurant.getId()),anyCollection());
        verify(allocations,never()).findByRestaurantIdAndOrderIdAndOrderItemId(any(),any(),any());
        assertThat(count("inventory_reservations")).isEqualTo(9);bulk(order,"mark-served").expect(200);assertThat(recipeUses()).isEqualTo(9);assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"disable","delete"})
    void warehouseWithReservedOrdersCannotBeDisabledOrDeleted(String action) throws Exception {
        stock("1000");create(true,"3").expect(201);
        long version=jdbc.queryForObject("select version from warehouses where id=?",Long.class,warehouse),before=count("audit_logs");
        Reply result=action.equals("disable")?call(patch("/api/v1/inventory/warehouses/"+warehouse+"/status"),Map.of("expectedVersion",version,"active",false),ALL)
            :call(delete("/api/v1/inventory/warehouses/"+warehouse).param("expectedVersion",String.valueOf(version)),null,ALL);
        result.error(409,"WAREHOUSE_HAS_RESERVATIONS");assertThat(count("audit_logs")).isEqualTo(before);balance(warehouse,"1000","315","685");assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"unit","tracking","deactivate","delete"})
    void ingredientWithReservedOrdersCannotChangeUnitTrackingActivityOrDeletion(String action) throws Exception {
        stock("1000");create(true,"3").expect(201);long version=items.findById(ingredient.getId()).orElseThrow().getVersion(),before=count("audit_logs");
        String endpoint="/api/v1/menu/ingredients/"+ingredient.getId();Reply result=switch(action){
            case "unit"->call(patch(endpoint),Map.of("expectedVersion",version,"baseUnit","kg"),ALL);
            case "tracking"->call(patch(endpoint),Map.of("expectedVersion",version,"trackInventory",false),ALL);
            case "deactivate"->call(patch(endpoint+"/status"),Map.of("expectedVersion",version,"active",false),ALL);
            default->call(delete(endpoint).param("expectedVersion",String.valueOf(version)),null,ALL);};
        result.error(409,"INGREDIENT_HAS_RESERVATIONS");assertThat(count("audit_logs")).isEqualTo(before);assertInvariant();
    }
    @Test void transferringTablePreservesAllocationRecipeWarehouseAndOrderVersion() throws Exception {
        stock("1000");var source=table();var request=body(true,"3");request.put("serviceType","DINE_IN");request.put("sourceChannel","WAITER");request.put("tableId",source.getId());
        var order=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).expect(201);var before=allocation(order,0).expect(200).body;
        UUID session=UUID.fromString(order.body.path("tableSessionId").asText());long version=jdbc.queryForObject("select version from table_sessions where id=?",Long.class,session);var target=table();
        call(post("/api/v1/table-sessions/"+session+"/transfer").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("expectedVersion",version,"targetTableId",target.getId(),"reason","Move table"),ORDER).expect(200);
        assertThat(allocation(order,0).expect(200).body).isEqualTo(before);assertThat(detail(order).expect(200).version()).isEqualTo(order.version());
        bulk(order,"mark-served").expect(200);balance(warehouse,"685","0","685");assertInvariant();
    }
    @Test void submittedPostUpgradeLineMissingAllocationIsConflictWithoutRepair() throws Exception {
        var draft=create(false,"3").expect(201);
        jdbc.update("update order_items set sent_to_kitchen_at=? where id=?",java.sql.Timestamp.from(NOW),draft.line(0));
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=? where id=?",java.sql.Timestamp.from(NOW),draft.id());
        long audits=count("audit_logs");allocation(draft,0).error(409,"INVENTORY_ALLOCATION_NOT_FOUND");
        single(draft,0,"start-cooking").error(409,"INVENTORY_ALLOCATION_NOT_FOUND");
        assertThat(count("order_item_inventory_allocations")).isZero();assertThat(count("order_item_recipe_snapshots")).isZero();assertThat(recipeUses()).isZero();assertThat(count("audit_logs")).isEqualTo(audits);
    }
    @Test void completionRejectsServedLineWhoseAllocationStillReservedAndNeverConsumesToRepairIt() throws Exception {
        stock("1000");var order=create(true,"3").expect(201);
        call(post(path(order)+"/payments").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("expectedOrderVersion",order.version(),"paymentMethod","CASH","amount",order.body.path("totalAmount").decimalValue()),ORDER).expect(201);
        jdbc.update("update order_items set status='SERVED' where id=?",order.line(0));jdbc.update("update orders set status='SERVED' where id=?",order.id());order=detail(order).expect(200);
        long audits=count("audit_logs");call(post(path(order)+"/complete"),Map.of("expectedVersion",order.version()),ORDER).error(409,"INVALID_INVENTORY_ALLOCATION_TRANSITION");
        balance(warehouse,"1000","315","685");assertThat(recipeUses()).isZero();assertThat(count("audit_logs")).isEqualTo(audits);assertInvariant();
    }
    @Test void appendIdempotencyPersistenceFailureRollsBackNewReservationAndCanRetrySameIntent() throws Exception {
        stock("1000");var first=create(true,"3").expect(201);long auditBefore=count("audit_logs");String key=UUID.randomUUID().toString();var request=addition(first,"2");
        doThrow(new IllegalStateException("forced append idempotency persistence failure")).when(submissions).saveAndFlush(any());
        call(post(path(first)+"/items").header("Idempotency-Key",key),request,ORDER).expect(500);reset(submissions);
        assertThat(detail(first).body).isEqualTo(first.body);assertThat(count("order_items")).isEqualTo(1);assertThat(count("order_item_inventory_allocations")).isEqualTo(1);assertThat(count("inventory_reservations")).isEqualTo(1);
        assertThat(count("order_item_submissions")).isZero();assertThat(count("audit_logs")).isEqualTo(auditBefore);balance(warehouse,"1000","315","685");assertInvariant();
        call(post(path(first)+"/items").header("Idempotency-Key",key),request,ORDER).expect(200);balance(warehouse,"1000","525","475");assertInvariant();
    }
    @Test void aggregateOverflowIsRejectedBeforePersistenceEvenIfIndividualUsagesFit() throws Exception {
        warehouse=createWarehouse("MAIN").expect(201).id();
        jdbc.update("update recipes set yield_quantity=0.001 where id=?",recipe.getId());jdbc.update("update recipe_items set quantity=20000000,waste_percent=0 where recipe_id=?",recipe.getId());
        var request=body(true,"3");request.put("items",List.of(line(menu.getId(),"3","overflow1"),line(menu.getId(),"3","overflow2")));
        long before=count("audit_logs");call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).error(409,"INVENTORY_QUANTITY_OUT_OF_RANGE");
        assertNoOrderArtifacts();assertThat(count("inventory_balances")).isZero();assertThat(count("audit_logs")).isEqualTo(before);
    }

    @Test void reserveAndReleaseDoNotAlertWhenOnlyAvailableIsLow() throws Exception {
        stock("1000");alertThreshold("200");var order=create(true,"9").expect(201);
        balance(warehouse,"1000","945","55");assertThat(count("low_stock_alerts")).isZero();
        cancel(order).expect(200);assertThat(count("low_stock_alerts")).isZero();
        assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"single","bulk","downgrade"})
    void physicalConsumptionAlertsWithOnlyExistingKitchenOrderPermissions(String mode) throws Exception {
        stock("1000");alertThreshold("700");var order=create(true,"3").expect(201);
        assertThat(count("low_stock_alerts")).isZero();
        if(mode.equals("downgrade"))withoutFeature("INVENTORY_MANAGEMENT");
        var done=mode.equals("single")?single(order,0,"start-cooking").expect(200):bulk(order,"mark-served").expect(200);
        assertThat(count("low_stock_alerts")).isEqualTo(1);assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity_at_trigger FROM low_stock_alerts WHERE restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("685");
        if(!mode.equals("single"))bulk(done,"mark-served").expect(200);
        assertThat(count("low_stock_alerts")).isEqualTo(1);assertThat(recipeUses()).isEqualTo(1);
    }
    @Test void sharedIngredientBulkConsumptionTriggersOneAlertAfterFinalBalance() throws Exception {
        stock("1000");alertThreshold("500");var order=create(true,"3").expect(201);order=append(order,"2").expect(200);
        bulk(order,"mark-served").expect(200);assertThat(count("low_stock_alerts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quantity_at_trigger FROM low_stock_alerts WHERE restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("475");
        assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(1);
    }
    @Test void alertAuditFailureRollsBackConsumptionAndCanRetry() throws Exception {
        stock("1000");alertThreshold("700");var order=create(true,"3").expect(201);long before=count("audit_logs");
        doAnswer(invocation->{
            invocation.callRealMethod();
            java.util.List<com.possaas.modules.audit.service.AuditRecordCommand> commands=invocation.getArgument(0);
            if(commands.stream().anyMatch(c->c.actionCode().startsWith("LOW_STOCK_ALERT_")))throw new IllegalStateException("forced alert failure");
            return null;
        }).when(audit).recordAll(anyList());
        bulk(order,"mark-served").expect(500);reset(audit);assertThat(count("low_stock_alerts")).isZero();
        assertThat(count("audit_logs")).isEqualTo(before);assertThat(recipeUses()).isZero();balance(warehouse,"1000","315","685");
        assertThat(detail(order).body).isEqualTo(order.body);bulk(order,"mark-served").expect(200);
        assertThat(count("low_stock_alerts")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"reserve","release","insufficient","corrupt-zero"})
    void stockAuditUsesCurrentReservationWithoutTreatingReservationAsPhysicalMovement(String scenario)throws Exception {
        setFeatures(java.util.stream.Stream.concat(FEATURES.stream(),java.util.stream.Stream.of("STOCK_AUDIT")).toList());
        stock("1000");var sheet=stockAudit(scenario.equals("corrupt-zero")?"1000":"800");
        var order=create(true,scenario.equals("insufficient")?"9":"6").expect(201);
        if(scenario.equals("release"))cancel(order).expect(200);
        if(scenario.equals("corrupt-zero"))jdbc.update("update inventory_balances set reserved_quantity=629 where warehouse_id=? and item_id=?",warehouse,ingredient.getId());
        long recipeBefore=count("order_item_recipe_usages"),ledgerBefore=count("inventory_transactions");
        var result=call(post("/api/v1/inventory/audits/"+sheet.id()+"/confirm"),Map.of("expectedVersion",sheet.version()),Set.of("INVENTORY_VIEW","STOCK_AUDIT_CONFIRM"));
        if(scenario.equals("insufficient")){result.error(409,"INSUFFICIENT_AVAILABLE_STOCK");balance(warehouse,"1000","945","55");}
        else if(scenario.equals("corrupt-zero")){result.error(409,"INVENTORY_RESERVATION_INVARIANT_VIOLATION");assertThat(count("inventory_transactions")).isEqualTo(ledgerBefore);}
        else {result.expect(200);balance(warehouse,"800",scenario.equals("release")?"0":"630",scenario.equals("release")?"800":"170");assertInvariant();}
        assertThat(count("order_item_recipe_usages")).isEqualTo(recipeBefore);
        assertThat(items.findById(menu.getId()).orElseThrow().getSalePrice()).isEqualByComparingTo("50000");
    }
    @ParameterizedTest @ValueSource(strings={"start-cooking","mark-served"})
    void consumptionAfterStockAuditSnapshotAlwaysMakesItStale(String action)throws Exception {
        setFeatures(java.util.stream.Stream.concat(FEATURES.stream(),java.util.stream.Stream.of("STOCK_AUDIT")).toList());
        stock("1000");var order=create(true,"3").expect(201);var sheet=stockAudit("800");
        if(action.equals("start-cooking"))single(order,0,action).expect(200);else bulk(order,action).expect(200);
        call(post("/api/v1/inventory/audits/"+sheet.id()+"/confirm"),Map.of("expectedVersion",sheet.version()),Set.of("INVENTORY_VIEW","STOCK_AUDIT_CONFIRM")).error(409,"STOCK_AUDIT_SNAPSHOT_STALE");
        balance(warehouse,"685","0","685");assertThat(count("stock_documents")).isEqualTo(1);
    }
    Reply stockAudit(String counted)throws Exception {
        var permissions=Set.of("INVENTORY_VIEW","STOCK_AUDIT_MANAGE");String url="/api/v1/inventory/audits";
        var audit=call(post(url).header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("warehouseId",warehouse),permissions).expect(201);
        audit=call(post(url+"/"+audit.id()+"/start"),Map.of("expectedVersion",audit.version()),permissions).expect(200);
        var rows=call(get(url+"/"+audit.id()+"/items"),null,Set.of("INVENTORY_VIEW")).expect(200).body.path("content");
        List<Map<String,Object>> counts=new ArrayList<>();rows.forEach(row->counts.add(Map.of("auditItemId",row.path("id").asText(),"countedQuantity",new BigDecimal(counted))));
        return call(patch(url+"/"+audit.id()+"/items"),Map.of("expectedVersion",audit.version(),"items",counts),permissions).expect(200);
    }

    void alertThreshold(String value)throws Exception {
        long version=jdbc.queryForObject("SELECT version FROM inventory_balances WHERE warehouse_id=? AND item_id=?",Long.class,warehouse,ingredient.getId());
        call(patch("/api/v1/inventory/warehouses/"+warehouse+"/balances/"+ingredient.getId()+"/threshold"),Map.of("expectedVersion",version,"minQuantity",new BigDecimal(value)),ALL).expect(200);
    }


    @ParameterizedTest @ValueSource(strings={"menu/items","menu/ingredients"})
    void referenceCostApiDistinguishesUnknownExplicitZeroAndClearing(String resource)throws Exception {
        var request=new LinkedHashMap<String,Object>();request.put("name","New cost");request.put("baseUnit","g");
        if(resource.endsWith("items"))request.put("salePrice",50);
        var created=call(post("/api/v1/"+resource),request,ALL).expect(201);
        assertThat(created.body.path("costConfigured").asBoolean()).isFalse();
        assertThat(created.body.path("costPrice").decimalValue()).isEqualByComparingTo("0");
        if(resource.endsWith("items"))assertThat(created.body.path("recipeEnabled").asBoolean()).isFalse();
        String url="/api/v1/"+resource+"/"+created.id();
        var zero=call(patch(url),Map.of("costPrice",0,"expectedVersion",created.version()),ALL).expect(200);
        assertThat(zero.body.path("costConfigured").asBoolean()).isTrue();assertThat(zero.version()).isGreaterThan(created.version());
        long auditCount=count("audit_logs");
        var same=call(patch(url),Map.of("costPrice",0,"expectedVersion",zero.version()),ALL).expect(200);
        assertThat(same.version()).isEqualTo(zero.version());assertThat(count("audit_logs")).isEqualTo(auditCount);
        var positive=call(patch(url),Map.of("costPrice",25,"expectedVersion",same.version()),ALL).expect(200);
        assertThat(positive.body.path("costConfigured").asBoolean()).isTrue();
        var kept=call(patch(url),Map.of("name","Other name","expectedVersion",positive.version()),ALL).expect(200);
        assertThat(kept.body.path("costPrice").decimalValue()).isEqualByComparingTo("25");
        var cleared=call(patch(url),Map.of("costConfigured",false,"expectedVersion",kept.version()),ALL).expect(200);
        assertThat(cleared.body.path("costConfigured").asBoolean()).isFalse();
        assertThat(cleared.body.path("costPrice").decimalValue()).isEqualByComparingTo("0");
        var confirmed=call(patch(url),Map.of("costConfigured",true,"expectedVersion",cleared.version()),ALL).expect(200);
        assertThat(confirmed.body.path("costConfigured").asBoolean()).isTrue();
        call(patch(url),Map.of("costConfigured",false,"costPrice",7,"expectedVersion",confirmed.version()),ALL)
            .error(400,"INVALID_COST_CONFIGURATION");
        request.put("costConfigured",true);
        call(post("/api/v1/"+resource),request,ALL).error(400,"COST_PRICE_REQUIRED");
        request.remove("costConfigured");request.put("costPrice",0);
        assertThat(call(post("/api/v1/"+resource),request,ALL).expect(201).body.path("costConfigured").asBoolean()).isTrue();
    }

    @Test void onlyManualCostUpdateUsesTheMenuAuditTimestampAndVersionPath()throws Exception {
        long before=events("MENU_ITEM_UPDATED");long version=items.findById(menu.getId()).orElseThrow().getVersion();
        var changed=call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("costPrice",25,"expectedVersion",version),ALL).expect(200);
        assertThat(changed.version()).isEqualTo(version+1);assertThat(events("MENU_ITEM_UPDATED")).isEqualTo(before+1);
        String after=jdbc.queryForObject("select after_data::text from audit_logs where restaurant_id=? and action_code='MENU_ITEM_UPDATED' order by created_at desc limit 1",String.class,restaurant.getId());
        assertThat(after).contains("costPrice","costConfigured","recipeEnabled");
        assertThat(changed.body.path("salePrice").decimalValue()).isEqualByComparingTo("50000");
    }

    @Test void disabledRecipeIgnoresRetainedInvalidDefinitionAndNeedsNoWarehouse()throws Exception {
        toggle(false).expect(200);
        jdbc.update("update items set is_active=false where id=?",ingredient.getId());
        var order=create(true,"3").expect(201);
        var decision=decision(order,0).expect(200);
        assertThat(decision.body.path("snapshotStatus").asText()).isEqualTo("RECIPE_DISABLED");
        assertThat(decision.body.path("recipeEnabledSnapshot").asBoolean()).isFalse();
        assertThat(decision.body.path("costSourceSnapshot").asText()).isEqualTo("MANUAL");
        assertThat(decision.body.path("ingredients")).isEmpty();
        var allocation=allocation(order,0).expect(200);
        assertThat(allocation.body.path("skipReason").asText()).isEqualTo("SNAPSHOT_RECIPE_DISABLED");
        assertThat(count("inventory_reservations")).isZero();assertThat(count("warehouses")).isZero();
        toggle(true).error(409,"INGREDIENT_INACTIVE");
    }

    @Test void disabledAcceptedItemNeverAcquiresStockWhenRecipeIsEnabledBeforeCancel()throws Exception {
        toggle(false).expect(200);var order=create(true,"3").expect(201);toggle(true).expect(200);
        cancel(order).expect(200);
        assertThat(count("inventory_reservations")).isZero();assertThat(recipeUses()).isZero();
        assertThat(allocation(order,0).expect(200).body.path("status").asText()).isEqualTo("SKIPPED");
        assertThat(decision(order,0).body.path("costSourceSnapshot").asText()).isEqualTo("MANUAL");
    }

    @Test void disabledAcceptedItemNeverAcquiresStockWhenRecipeIsEnabledBeforeCooking()throws Exception {
        toggle(false).expect(200);var order=create(true,"3").expect(201);toggle(true).expect(200);
        var cooked=single(order,0,"start-cooking").expect(200);
        assertThat(cooked.body.path("status").asText()).isEqualTo("PREPARING");
        assertThat(count("inventory_reservations")).isZero();assertThat(recipeUses()).isZero();
        assertThat(allocation(order,0).expect(200).body.path("status").asText()).isEqualTo("SKIPPED");
    }

    @Test void acceptedRecipeStillReleasesItsOriginalReservationAfterToggleOff()throws Exception {
        stock("1000");var order=create(true,"3").expect(201);toggle(false).expect(200);
        cancel(order).expect(200);balance(warehouse,"1000","0","1000");assertThat(recipeUses()).isZero();
        assertThat(allocation(order,0).expect(200).body.path("status").asText()).isEqualTo("RELEASED");
        assertThat(decision(order,0).body.path("costSourceSnapshot").asText()).isEqualTo("RECIPE");
    }

    @Test void acceptedRecipeStillConsumesAfterToggleOffAndRecipeUsePriceRemainsNull()throws Exception {
        stock("1000");var order=create(true,"3").expect(201);toggle(false).expect(200);
        var served=bulk(order,"mark-served").expect(200);balance(warehouse,"685","0","685");
        assertThat(recipeUses()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select bool_and(unit_cost is null) from inventory_transactions where restaurant_id=? and transaction_type='RECIPE_USE'",Boolean.class,restaurant.getId())).isTrue();
        bulk(served,"mark-served").expect(200);assertThat(recipeUses()).isEqualTo(1);
        assertThat(served.body.toString()).doesNotContain("costConfigured","recipeEnabled","costSourceSnapshot");
    }

    @Test void appendAndDraftFreezeConfigurationOnlyAtAcceptanceAndReplayKeepsIt()throws Exception {
        toggle(false).expect(200);String key=UUID.randomUUID().toString();var request=body(true,"3");
        var order=call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(201);
        stock("1000");toggle(true).expect(200);
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ORDER).expect(200);
        var added=append(order,"1").expect(200);
        assertThat(decision(added,0).body.path("costSourceSnapshot").asText()).isEqualTo("MANUAL");
        assertThat(decision(added,1).body.path("costSourceSnapshot").asText()).isEqualTo("RECIPE");
        balance(warehouse,"1000","105","895");
        toggle(false).expect(200);var draft=create(false,"1").expect(201);toggle(true).expect(200);
        var confirmed=confirm(draft).expect(200);
        assertThat(decision(confirmed,0).body.path("snapshotStatus").asText()).isEqualTo("CAPTURED");
    }

    @Test void missingRecipeFeatureDoesNotBecomeManualAndSwitchOffStillWorks()throws Exception {
        withoutFeature("RECIPE_MANAGEMENT");var order=create(true,"1").expect(201);
        assertThat(jdbc.queryForObject("select snapshot_status from order_item_recipe_snapshots where order_item_id=?",String.class,order.line(0))).isEqualTo("FEATURE_DISABLED");
        assertThat(jdbc.queryForObject("select cost_source_snapshot from order_item_recipe_snapshots where order_item_id=?",String.class,order.line(0))).isEqualTo("RECIPE");
        toggle(false).expect(200);toggle(true).error(403,"FEATURE_NOT_ENTITLED");
    }

    @Test void enabledRecipeRequiresDefinitionAndCannotBeArchivedUntilApplicationIsDisabled()throws Exception {
        long version=recipes.findById(recipe.getId()).orElseThrow().getVersion();
        call(delete("/api/v1/menu/items/"+menu.getId()+"/recipes/"+recipe.getId()).param("expectedVersion",Long.toString(version)),null,Set.of("RECIPE_DELETE"))
            .error(409,"RECIPE_APPLICATION_ENABLED");
        recipe=recipes.findById(recipe.getId()).orElseThrow();recipe.deactivate(NOW);recipes.saveAndFlush(recipe);
        long before=count("audit_logs");create(true,"1").error(409,"ACTIVE_RECIPE_REQUIRED");
        assertNoOrderArtifacts();assertThat(count("audit_logs")).isEqualTo(before);
        toggle(false).expect(200);toggle(true).error(409,"ACTIVE_RECIPE_REQUIRED");
    }

    @Test void invalidEnableRollsBackSimultaneousCostEditAndRequiresMenuPermission()throws Exception {
        toggle(false).expect(200);long version=items.findById(menu.getId()).orElseThrow().getVersion();
        long before=count("audit_logs");jdbc.update("update items set is_active=false where id=?",ingredient.getId());
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("recipeEnabled",true,"costPrice",99,"expectedVersion",version),ALL)
            .error(409,"INGREDIENT_INACTIVE");
        var unchanged=items.findById(menu.getId()).orElseThrow();assertThat(unchanged.getCostPrice()).isEqualByComparingTo("10");
        assertThat(unchanged.getVersion()).isEqualTo(version);assertThat(count("audit_logs")).isEqualTo(before);
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("recipeEnabled",true,"expectedVersion",version),Set.of("MENU_VIEW")).expect(403);
    }

    @Test void unconfiguredOpeningStockDoesNotBecomeKnownByAWeightedReceipt()throws Exception {
        stock("100");ingredient=items.findById(ingredient.getId()).orElseThrow();
        call(patch("/api/v1/menu/ingredients/"+ingredient.getId()),Map.of("costConfigured",false,"expectedVersion",ingredient.getVersion()),ALL).expect(200);
        receive(warehouse,ingredient,"100","20");
        var unknown=items.findById(ingredient.getId()).orElseThrow();assertThat(unknown.isCostConfigured()).isFalse();
        assertThat(unknown.getCostPrice()).isEqualByComparingTo("0");
        assertThat(events("INGREDIENT_COST_RECALCULATION_SKIPPED")).isEqualTo(1);
        call(get("/api/v1/menu/items/"+menu.getId()+"/recipes/"+recipe.getId()+"/cost-estimate"),null,ALL)
            .error(409,"INGREDIENT_COST_NOT_CONFIGURED");
        balance(warehouse,"200","0","200");
    }

    @Test void firstExplicitZeroReceiptConfiguresZeroEvenWithoutNumericPriceChange()throws Exception {
        ingredient=items.findById(ingredient.getId()).orElseThrow();
        call(patch("/api/v1/menu/ingredients/"+ingredient.getId()),Map.of("costConfigured",false,"expectedVersion",ingredient.getVersion()),ALL).expect(200);
        warehouse=createWarehouse("ZERO").expect(201).id();long before=events("INGREDIENT_COST_RECALCULATED");
        receive(warehouse,ingredient,"10","0");var zero=items.findById(ingredient.getId()).orElseThrow();
        assertThat(zero.isCostConfigured()).isTrue();assertThat(zero.getCostPrice()).isEqualByComparingTo("0");
        assertThat(events("INGREDIENT_COST_RECALCULATED")).isEqualTo(before+1);
        var estimate=call(get("/api/v1/menu/items/"+menu.getId()+"/recipes/"+recipe.getId()+"/cost-estimate"),null,ALL).expect(200);
        assertThat(estimate.body.path("totalBatchCost").decimalValue()).isEqualByComparingTo("0");
        assertThat(estimate.body.path("warnings").get(0).path("code").asText()).isEqualTo("ZERO_INGREDIENT_REFERENCE_COST");
    }

    @Test void concurrentToggleAndSubmitUseOneCompleteDecision()throws Exception {
        stock("1000");var result=race(()->toggle(false),()->create(true,"3"));
        result.get(0).expect(200);var order=result.get(1).expect(201);
        String status=decision(order,0).expect(200).body.path("snapshotStatus").asText();
        assertThat(status).isIn("CAPTURED","RECIPE_DISABLED");
        boolean captured=status.equals("CAPTURED");
        assertThat(count("inventory_reservations")).isEqualTo(captured?1:0);
        balance(warehouse,"1000",captured?"315":"0",captured?"685":"1000");assertInvariant();
    }

    Reply toggle(boolean enabled)throws Exception {
        long version=items.findById(menu.getId()).orElseThrow().getVersion();
        return call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("recipeEnabled",enabled,"expectedVersion",version),ALL);
    }
    Reply decision(Reply order,int index)throws Exception {
        return call(get("/api/v1/orders/"+order.id()+"/items/"+order.line(index)+"/recipe-snapshot"),null,ALL);
    }

    Item item(String name,ItemType type,String unit,boolean tracked){var value=new Item();value.initialize(restaurant.getId(),NOW);value.setName(name);value.setItemType(type);value.setBaseUnit(unit);value.setTrackInventory(tracked);value.setSalePrice(type==ItemType.MENU_ITEM?new BigDecimal("50000"):BigDecimal.ZERO);value.setCostPrice(new BigDecimal("10"));value.setCostConfigured(true);return items.saveAndFlush(value);}
    Recipe recipe(Item food,Item source,String quantity,String waste){food=items.findById(food.getId()).orElseThrow();food.setRecipeEnabled(true);food=items.saveAndFlush(food);if(menu.getId().equals(food.getId()))menu=food;var value=new Recipe();value.initialize(restaurant.getId(),food.getId(),1,new BigDecimal("10"),food.getBaseUnit(),NOW);value=recipes.saveAndFlush(value);var part=new RecipeItem();part.initialize(restaurant.getId(),value.getId(),source,new BigDecimal(quantity),new BigDecimal(waste),NOW);recipeItems.saveAndFlush(part);return value;}
    void replaceRecipe(String quantity){recipe=recipes.findById(recipe.getId()).orElseThrow();int version=recipe.getRecipeVersion()+1;recipe.deactivate(NOW);recipes.saveAndFlush(recipe);var next=new Recipe();next.initialize(restaurant.getId(),menu.getId(),version,new BigDecimal("10"),menu.getBaseUnit(),NOW);recipe=recipes.saveAndFlush(next);var part=new RecipeItem();part.initialize(restaurant.getId(),recipe.getId(),ingredient,new BigDecimal(quantity),new BigDecimal("5"),NOW);recipeItems.saveAndFlush(part);}
    RestaurantTable table(){var value=new RestaurantTable();value.initialize(restaurant.getId(),NOW);value.setCode(UUID.randomUUID().toString());value.setName("Table");value.setQrToken(UUID.randomUUID().toString());return tables.saveAndFlush(value);}
    Map<String,Object> features(List<String> codes){return new SubscriptionFeatureSnapshot(1,"PRO",codes.stream().map(code->new SubscriptionFeatureSnapshot.FeatureGrant(code,Map.of())).toList(),NOW).toMap();}
    void setFeatures(List<String> codes){subscription.setFeatureSnapshot(features(codes));subscription=subscriptions.saveAndFlush(subscription);}
    void withoutFeature(String code){setFeatures(FEATURES.stream().filter(f->!f.equals(code)).toList());}
    Reply createWarehouse(String code)throws Exception{return call(post("/api/v1/inventory/warehouses"),Map.of("code",code,"name",code),ALL);}
    void stock(String quantity)throws Exception{warehouse=createWarehouse("MAIN").expect(201).id();receive(warehouse,ingredient,quantity,"10");}
    Reply movement(UUID target,Item source,String type,String quantity,String cost,String direction)throws Exception{var part=new LinkedHashMap<String,Object>();part.put("ingredientId",source.getId());part.put("quantity",new BigDecimal(quantity));part.put("unitCost",new BigDecimal(cost));if(direction!=null)part.put("adjustmentDirection",direction);return call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType",type,"warehouseId",target,"items",List.of(part)),ALL);}
    Reply receive(UUID target,Item source,String quantity,String cost)throws Exception{var draft=movement(target,source,"RECEIPT",quantity,cost,null).expect(201);return call(post("/api/v1/inventory/stock-documents/"+draft.id()+"/confirm"),Map.of("expectedVersion",draft.version()),ALL).expect(200);}
    Map<String,Object> line(UUID id,String quantity,String note){return Map.of("itemId",id,"quantity",new BigDecimal(quantity),"note",note);}
    Map<String,Object> body(boolean submit,String quantity){var result=new LinkedHashMap<String,Object>();result.put("serviceType","TAKEAWAY");result.put("sourceChannel","CASHIER");result.put("submissionMode",submit?"SUBMIT":"DRAFT");result.put("items",List.of(line(menu.getId(),quantity,"initial")));return result;}
    Reply create(boolean submit,String quantity)throws Exception{return call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),body(submit,quantity),ORDER);}
    Reply confirm(Reply order)throws Exception{return call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version()),ORDER);}
    Map<String,Object> addition(Reply order,String quantity){return Map.of("expectedVersion",order.version(),"submissionMode","SUBMIT","items",List.of(line(menu.getId(),quantity,"added")));}
    Reply append(Reply order,String quantity)throws Exception{return call(post(path(order)+"/items").header("Idempotency-Key",UUID.randomUUID().toString()),addition(order,quantity),ORDER);}
    Reply single(Reply order,int index,String action)throws Exception{return call(post(path(order)+"/items/"+order.line(index)+"/"+action),Map.of("expectedOrderVersion",order.version()),ORDER);}
    Reply bulk(Reply order,String action)throws Exception{return call(post(path(order)+"/"+action),Map.of("expectedOrderVersion",order.version()),ORDER);}
    Reply cancel(Reply order)throws Exception{return call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","cancel"),ORDER);}
    String path(Reply order){return "/api/v1/orders/"+order.id();}
    String allocationPath(Reply order,int index){return "/api/v1/inventory/orders/"+order.id()+"/items/"+order.line(index)+"/allocation";}
    Reply allocation(Reply order,int index)throws Exception{return call(get(allocationPath(order,index)),null,ALL);}
    Reply detail(Reply order)throws Exception{return call(get(path(order)),null,ORDER);}
    Reply balance(UUID target,String physical,String reserved,String available)throws Exception{var reply=call(get("/api/v1/inventory/warehouses/"+target+"/balances/"+ingredient.getId()),null,ALL).expect(200);assertThat(reply.body.path("quantity").decimalValue()).isEqualByComparingTo(physical);assertThat(reply.body.path("reservedQuantity").decimalValue()).isEqualByComparingTo(reserved);assertThat(reply.body.path("availableQuantity").decimalValue()).isEqualByComparingTo(available);return reply;}
    void balanceDb(UUID target,String physical,String reserved){var value=jdbc.queryForMap("select quantity,reserved_quantity from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",restaurant.getId(),target,ingredient.getId());assertThat((BigDecimal)value.get("quantity")).isEqualByComparingTo(physical);assertThat((BigDecimal)value.get("reserved_quantity")).isEqualByComparingTo(reserved);}
    void assertInvariant(){long violations=jdbc.queryForObject("select count(*) from inventory_balances b where b.restaurant_id=? and (b.quantity<0 or b.reserved_quantity<0 or b.quantity<b.reserved_quantity or b.reserved_quantity<>(select coalesce(sum(r.quantity),0) from inventory_reservations r join order_item_inventory_allocations a on a.id=r.allocation_id and a.restaurant_id=r.restaurant_id where r.restaurant_id=b.restaurant_id and r.warehouse_id=b.warehouse_id and r.ingredient_id=b.item_id and a.status='RESERVED'))",Long.class,restaurant.getId());assertThat(violations).isZero();}
    void assertNoOrderArtifacts(){for(String table:List.of("orders","order_items","order_item_recipe_snapshots","order_item_recipe_usages","order_item_inventory_allocations","inventory_reservations","order_item_status_history","order_item_submissions"))assertThat(count(table)).as(table).isZero();}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where restaurant_id=?",Long.class,restaurant.getId());}
    long events(String event){return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",Long.class,restaurant.getId(),event);}
    long recipeUses(){return jdbc.queryForObject("select count(*) from inventory_transactions where restaurant_id=? and transaction_type='RECIPE_USE'",Long.class,restaurant.getId());}
    Reply call(MockHttpServletRequestBuilder request,Object body,Set<String> permissions)throws Exception{if(body!=null)request.content(json.writeValueAsString(body));var actor=new CurrentUser(user.getId(),restaurant.getId(),user.getEmail(),"OWNER",permissions);var auth=new UsernamePasswordAuthenticationToken(actor,null,permissions.stream().map(SimpleGrantedAuthority::new).toList());var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(auth))).andReturn().getResponse();return new Reply(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()),response.getHeader("Cache-Control"));}
    record Reply(int status,JsonNode body,String cache){UUID id(){return UUID.fromString(body.path("id").asText());}UUID line(int index){return UUID.fromString(body.at("/items/"+index+"/id").asText());}long version(){return body.path("version").asLong();}Reply expect(int code){assertThat(status).as(body.toString()).isEqualTo(code);return this;}void error(int status,String code){expect(status);assertThat(body.path("code").asText()).isEqualTo(code);}}
    List<Reply> race(Callable<Reply> one,Callable<Reply> two)throws Exception{var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return one.call();});var b=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return two.call();});assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}}
}
