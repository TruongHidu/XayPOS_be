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
@Import({DirectStockIntegrationTest.FixedTime.class, com.possaas.support.CommittedRealtimeEvents.Config.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DirectStockIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-20T08:00:00Z");
    static final String SCHEMA="it_direct_stock_"+UUID.randomUUID().toString().replace("-","");
    static final Set<String> ORDER=Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE","ORDER_CANCEL",
        "ORDER_MARK_SERVED","ORDER_COMPLETE","KITCHEN_VIEW","KITCHEN_MARK_READY","KITCHEN_START_COOKING",
        "TABLE_OPEN","TABLE_CLOSE","TABLE_TRANSFER","PAYMENT_CREATE","PAYMENT_CONFIRM");
    static final Set<String> ALL;
    static {var p=new HashSet<>(ORDER);p.addAll(Set.of("MENU_VIEW","MENU_UPDATE","MENU_CREATE","INGREDIENT_CREATE","INGREDIENT_VIEW","RECIPE_VIEW","INVENTORY_VIEW","WAREHOUSE_CREATE",
        "WAREHOUSE_UPDATE","WAREHOUSE_DELETE","INVENTORY_RECEIVE","INVENTORY_ISSUE","INVENTORY_ADJUST",
        "INVENTORY_THRESHOLD_UPDATE","INGREDIENT_UPDATE","INGREDIENT_DELETE"));ALL=Set.copyOf(p);}
    static final List<String> FEATURES=List.of("MENU_MANAGEMENT","ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT",
        "RECIPE_MANAGEMENT","INVENTORY_MANAGEMENT","KITCHEN_DISPLAY","PAYMENT_MANAGEMENT","LOW_STOCK_ALERT");
    static final List<String> COST_FEATURES=java.util.stream.Stream.concat(FEATURES.stream(),java.util.stream.Stream.of("DETAIL_REPORT")).toList();
    static final Set<String> COST_READ=Set.of("ORDER_VIEW","REPORT_DETAIL_VIEW");
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
    @MockitoSpyBean com.possaas.modules.order.application.port.OrderReferenceCostQuery referenceCosts;
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
    @AfterAll void cleanup(){if(!SCHEMA.matches("it_direct_stock_[0-9a-f]{32}"))throw new IllegalStateException();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}


    void direct() throws Exception {
        var created=call(post("/api/v1/menu/items"),Map.of("name","Coca lon","sku","COCA","baseUnit","lon","salePrice",15000,"trackInventory",true,"recipeEnabled",false),ALL).expect(201);
        menu=items.findById(created.id()).orElseThrow(); ingredient=menu;
        assertThat(created.body.path("inventoryMode").asText()).isEqualTo("DIRECT");
    }
    long directUses(){return jdbc.queryForObject("select count(*) from inventory_transactions where restaurant_id=? and transaction_type='DIRECT_USE'",Long.class,restaurant.getId());}
    @Test void directWithoutRecipeFeatureReservesConsumesFrozenCostAndReplays() throws Exception {
        direct(); withoutFeature("RECIPE_MANAGEMENT");stock("100");
        var order=create(true,"3").expect(201);balance(warehouse,"100","3","97");
        assertThat(count("order_item_recipe_usages")).isZero();
        var view=call(get(allocationPath(order,0)),null,Set.of("ORDER_VIEW","INVENTORY_VIEW")).expect(200);
        assertThat(view.body.path("status").asText()).isEqualTo("RESERVED");
        assertThat(view.body.at("/ingredients/0/recipeUsageId").isNull()).isTrue();
        receive(warehouse,menu,"100","30");
        assertThat(items.findById(menu.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("20");
        var served=bulk(order,"mark-served").expect(200);bulk(order,"mark-served").expect(200);
        balance(warehouse,"197","0","197");assertThat(directUses()).isEqualTo(1);assertThat(recipeUses()).isZero();
        assertThat(jdbc.queryForObject("select unit_cost from inventory_transactions where order_item_id=?",BigDecimal.class,order.line(0))).isEqualByComparingTo("10");assertInvariant();
    }
    @Test void pendingCancelOnlyReleasesAndConfigurationCannotBeDisabledWithStock() throws Exception {
        direct();stock("100");var order=create(true,"3").expect(201);cancel(order).expect(200);balance(warehouse,"100","0","100");
        assertThat(directUses()).isZero();
        var current=items.findById(menu.getId()).orElseThrow();
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",current.getVersion(),"trackInventory",false),ALL).error(409,"STOCK_TRACKING_CHANGE_NOT_ALLOWED");
    }
    @ParameterizedTest @ValueSource(strings={"create","confirm","append"})
    void acceptanceRoutesFreezeDirectDecision(String route) throws Exception {
        direct();stock("100");Reply order;
        if(route.equals("create"))order=create(true,"3").expect(201);
        else {order=create(false,"3").expect(201);assertThat(count("inventory_reservations")).isZero();order=route.equals("confirm")?confirm(order).expect(200):append(order,"1").expect(200);}
        assertThat(count("inventory_reservations")).isEqualTo(route.equals("append")?2:1);assertInvariant();
    }
    @ParameterizedTest @ValueSource(strings={"start-cooking","mark-served"})
    void firstPendingTransitionConsumesExactlyOnce(String action) throws Exception {
        direct();stock("100");var order=create(true,"3").expect(201);
        if(action.equals("start-cooking"))single(order,0,action).expect(200);else bulk(order,action).expect(200);
        balance(warehouse,"97","0","97");assertThat(directUses()).isEqualTo(1);assertInvariant();
    }
    @Test void missingStockRollsBackSessionAndAcceptanceArtifacts() throws Exception {
        direct();stock("2");var request=body(true,"3");request.put("serviceType","DINE_IN");request.put("sourceChannel","WAITER");request.put("tableId",table().getId());
        var failure=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(failure.body.at("/details/lines")).singleElement().satisfies(shortage->{
            assertThat(shortage.path("itemName").asText()).isEqualTo("Coca lon");assertThat(shortage.path("unit").asText()).isEqualTo("lon");
            assertThat(shortage.path("maxAvailableQuantity").decimalValue()).isEqualByComparingTo("2");
            assertThat(shortage.has("shortIngredients")).isFalse();});
        var manager=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ALL).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(manager.body.at("/details/lines/0/shortIngredients/0/ingredientId").asText()).isEqualTo(menu.getId().toString());
        assertNoOrderArtifacts();assertThat(count("table_sessions")).isZero();balance(warehouse,"2","0","2");
    }
    @Test void concurrentOrdersCannotOversellLastStock() throws Exception {
        direct();stock("3");var result=race(()->create(true,"3"),()->create(true,"3"));
        assertThat(result.stream().map(Reply::status)).containsExactlyInAnyOrder(201,409);balance(warehouse,"3","3","0");assertInvariant();
    }
    @Test void lostFeaturesNeverCancelHistoricalReservation() throws Exception {
        direct();stock("100");var order=create(true,"3").expect(201);withoutFeature("INVENTORY_MANAGEMENT");bulk(order,"mark-served").expect(200);
        balanceDb(warehouse,"97","0");assertThat(directUses()).isEqualTo(1);assertInvariant();
    }
    @Test void skippedDirectNeverAcquiresStockAfterUpgrade() throws Exception {
        direct();withoutFeature("INVENTORY_MANAGEMENT");var order=create(true,"3").expect(201);setFeatures(FEATURES);stock("100");bulk(order,"mark-served").expect(200);
        balance(warehouse,"100","0","100");assertThat(directUses()).isZero();assertInvariant();
    }
    @Test void aliasesAreValidatedAndHashCompatible() throws Exception {
        direct();warehouse=createWarehouse("MAIN").expect(201).id();String key=UUID.randomUUID().toString();
        var part=new LinkedHashMap<String,Object>();part.put("itemId",menu.getId());part.put("quantity",100);part.put("unitCost",10);
        var request=Map.of("documentType","RECEIPT","warehouseId",warehouse,"items",List.of(part));
        var draft=call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",key),request,ALL).expect(201);
        call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",key),request,ALL).expect(201);
        call(post("/api/v1/inventory/stock-documents/"+draft.id()+"/confirm"),Map.of("expectedVersion",draft.version()),ALL).expect(200);
        call(post("/api/v1/inventory/stock-documents/"+draft.id()+"/confirm"),Map.of("expectedVersion",draft.version()),ALL).expect(200);balance(warehouse,"100","0","100");
        part.put("ingredientId",UUID.randomUUID());call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),request,ALL).error(400,"INVALID_STOCK_ITEM_REFERENCE");
    }
    @Test void conflictingModesAreRejectedAndMissingInventoryOnlyBlocksEnabling() throws Exception {
        direct();var current=items.findById(menu.getId()).orElseThrow();
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",current.getVersion(),"recipeEnabled",true),ALL).error(409,"INVENTORY_TRACKING_MODE_CONFLICT");
        withoutFeature("INVENTORY_MANAGEMENT");
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",current.getVersion(),"name","New Coca"),ALL).expect(200);
        call(post("/api/v1/menu/items"),Map.of("name","Other","baseUnit","lon","salePrice",1,"trackInventory",true),ALL).expect(403);
    }

    @Test void directPickerBalancesAndLedgerExposeItemIdentityWithoutFakeIngredient() throws Exception {
        direct();stock("100");
        var picker=call(get("/api/v1/inventory/stock-items").param("itemType","MENU_ITEM").param("q","Coca"),null,ALL).expect(200);
        assertThat(picker.body.at("/content/0/itemId").asText()).isEqualTo(menu.getId().toString());
        var bal=balance(warehouse,"100","0","100");assertThat(bal.body.path("ingredientId").isNull()).isTrue();assertThat(bal.body.path("itemType").asText()).isEqualTo("MENU_ITEM");
        var order=create(true,"3").expect(201);bulk(order,"mark-served").expect(200);
        var tx=call(get("/api/v1/inventory/transactions").param("orderId",order.id().toString()),null,ALL).expect(200);
        assertThat(tx.body.at("/content/0/itemId").asText()).isEqualTo(menu.getId().toString());assertThat(tx.body.at("/content/0/ingredientId").isNull()).isTrue();
    }
    @Test void directAlertsOpenAndResolveWithTypedRealtimePayload() throws Exception {
        direct();stock("100");var bal=balance(warehouse,"100","0","100");
        call(patch("/api/v1/inventory/warehouses/"+warehouse+"/balances/"+menu.getId()+"/threshold"),Map.of("expectedVersion",bal.version(),"minQuantity",98),ALL).expect(200);
        var order=create(true,"3").expect(201);bulk(order,"mark-served").expect(200);
        assertThat(jdbc.queryForObject("select status from low_stock_alerts where restaurant_id=?",String.class,restaurant.getId())).isEqualTo("OPEN");
        var alerts=call(get("/api/v1/inventory/alerts"),null,ALL).expect(200);
        assertThat(alerts.body.at("/content/0/itemType").asText()).isEqualTo("MENU_ITEM");assertThat(alerts.body.at("/content/0/ingredientId").isNull()).isTrue();
        receive(warehouse,menu,"10","10");
        assertThat(jdbc.queryForObject("select status from low_stock_alerts where restaurant_id=?",String.class,restaurant.getId())).isEqualTo("RESOLVED");
    }
    @Test void directUnknownAndExplicitZeroCostsRemainDistinct() throws Exception {
        direct();warehouse=createWarehouse("MAIN").expect(201).id();
        // Adjustment establishes unknown opening stock without assigning a receipt price.
        var doc=movement(warehouse,menu,"ADJUSTMENT","10","0","INCREASE").expect(201);
        call(post("/api/v1/inventory/stock-documents/"+doc.id()+"/confirm"),Map.of("expectedVersion",doc.version()),ALL).expect(200);
        var first=create(true,"1").expect(201);bulk(first,"mark-served").expect(200);
        assertThat(jdbc.queryForObject("select unit_cost from inventory_transactions where order_item_id=?",BigDecimal.class,first.line(0))).isNull();
        setPrice(menu,true,"0");var second=create(true,"1").expect(201);bulk(second,"mark-served").expect(200);
        assertThat(jdbc.queryForObject("select unit_cost from inventory_transactions where order_item_id=?",BigDecimal.class,second.line(0))).isEqualByComparingTo("0");
        assertInvariant();
    }
    @Test void nonTrackingAcceptanceStaysSkippedAfterTrackingIsEnabled() throws Exception {
        direct();var version=items.findById(menu.getId()).orElseThrow().getVersion();
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",version,"trackInventory",false),ALL).expect(200);
        var old=create(true,"3").expect(201);version=items.findById(menu.getId()).orElseThrow().getVersion();
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",version,"trackInventory",true),ALL).expect(200);stock("100");bulk(old,"mark-served").expect(200);
        balance(warehouse,"100","0","100");assertThat(directUses()).isZero();
    }
    @Test void doubleModeDatabaseCheckAndImmutableHistoryRemainEnforced() throws Exception {
        direct();stock("100");var order=create(true,"3").expect(201);
        assertThatThrownBy(()->jdbc.update("update items set recipe_enabled=true where id=?",menu.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update inventory_reservations set quantity=2 where restaurant_id=?",restaurant.getId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update order_item_inventory_allocations set direct_stock_snapshot=false where order_item_id=?",order.line(0))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        bulk(order,"mark-served").expect(200);
        assertThatThrownBy(()->jdbc.update("delete from inventory_transactions where order_item_id=?",order.line(0))).isInstanceOf(org.springframework.dao.DataAccessException.class).hasMessageContaining("append-only");assertInvariant();
    }
    @Test void aggregateDirectDemandAndAuditFailureRollbackWholeCommand() throws Exception {
        direct();stock("3");var request=body(true,"2");request.put("items",List.of(line(menu.getId(),"2","a"),line(menu.getId(),"2","b")));
        var failure=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).error(409,"INSUFFICIENT_AVAILABLE_STOCK");assertNoOrderArtifacts();
        assertThat(failure.body.at("/details/lines")).hasSize(2).allSatisfy(shortage->{
            assertThat(shortage.path("maxAvailableQuantity").decimalValue()).isEqualByComparingTo("1");
            assertThat(shortage.path("sharesIngredientWithOtherLines").asBoolean()).isTrue();});
        doThrow(new IllegalStateException("audit failure")).when(audit).recordAll(anyList());create(true,"1").expect(500);reset(audit);
        assertNoOrderArtifacts();balance(warehouse,"3","0","3");assertThat(committed.orders).isEmpty();
    }
    @Test void menuPatchOmissionAndAtomicModeSwitchAreValidated() throws Exception {
        direct();var v=items.findById(menu.getId()).orElseThrow().getVersion();
        var response=call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",v,"name","Coca new"),ALL).expect(200);
        assertThat(response.body.path("trackInventory").asBoolean()).isTrue();
        // No recipe exists for this new DIRECT menu item, so the atomic switch must fail on recipe policy.
        call(patch("/api/v1/menu/items/"+menu.getId()),Map.of("expectedVersion",response.version(),"trackInventory",false,"recipeEnabled",true),ALL).error(409,"ACTIVE_RECIPE_REQUIRED");
        assertThat(items.findById(menu.getId()).orElseThrow().isTrackInventory()).isTrue();
    }


    @Test void directStocktakingAdjustsQuantityAndKeepsReferencePrice() throws Exception {
        direct();stock("100");var codes=new ArrayList<>(FEATURES);codes.add("STOCK_AUDIT");setFeatures(codes);
        var permissions=new HashSet<>(ALL);permissions.addAll(Set.of("STOCK_AUDIT_MANAGE","STOCK_AUDIT_CONFIRM"));
        var audit=call(post("/api/v1/inventory/audits").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("warehouseId",warehouse),permissions).expect(201);
        var started=call(post("/api/v1/inventory/audits/"+audit.id()+"/start"),Map.of("expectedVersion",audit.version()),permissions).expect(200);
        var rows=call(get("/api/v1/inventory/audits/"+audit.id()+"/items"),null,permissions).expect(200).body.path("content");
        var counts=new ArrayList<Map<String,Object>>();
        for(var row:rows) {boolean direct="MENU_ITEM".equals(row.path("itemType").asText());counts.add(Map.of("auditItemId",row.path("id").asText(),"countedQuantity",direct?99:0));if(direct)assertThat(row.path("ingredientId").isNull()).isTrue();}
        var counted=call(patch("/api/v1/inventory/audits/"+audit.id()+"/items"),Map.of("expectedVersion",started.version(),"items",counts),permissions).expect(200);
        call(post("/api/v1/inventory/audits/"+audit.id()+"/confirm"),Map.of("expectedVersion",counted.version()),permissions).expect(200);
        balance(warehouse,"99","0","99");assertThat(items.findById(menu.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("10");assertInvariant();
    }


    @Test void mixedOrderKeepsDirectRecipeAndNoneDecisionsIndependent() throws Exception {
        var food=menu;var raw=ingredient;direct();warehouse=createWarehouse("MAIN").expect(201).id();receive(warehouse,menu,"100","10");receive(warehouse,raw,"1000","10");
        var none=item("Untracked",ItemType.MENU_ITEM,"portion",false);
        var request=body(true,"3");request.put("items",List.of(line(menu.getId(),"3","direct"),line(food.getId(),"1","recipe"),line(none.getId(),"2","none")));
        var order=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ORDER).expect(201);
        assertThat(count("inventory_reservations")).isEqualTo(2);assertThat(count("order_item_inventory_allocations")).isEqualTo(3);
        bulk(order,"mark-served").expect(200);assertThat(directUses()).isEqualTo(1);assertThat(recipeUses()).isEqualTo(1);balance(warehouse,"97","0","97");
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where warehouse_id=? and item_id=?",BigDecimal.class,warehouse,raw.getId())).isEqualByComparingTo("895");assertInvariant();
    }

    @Test void unsentDirectAllocationDoesNotRequireRecipeAccessOrCreateDecisions() throws Exception {
        direct();withoutFeature("RECIPE_MANAGEMENT");var draft=create(false,"3").expect(201);
        var response=call(get(allocationPath(draft,0)),null,Set.of("ORDER_VIEW","INVENTORY_VIEW")).expect(200);
        assertThat(response.body.path("status").asText()).isEqualTo("NOT_SUBMITTED");assertThat(count("order_item_inventory_allocations")).isZero();
    }
    Reply setPrice(Item source,boolean configured,String price)throws Exception {
        long version=items.findById(source.getId()).orElseThrow().getVersion();var request=new LinkedHashMap<String,Object>();request.put("expectedVersion",version);request.put("costConfigured",configured);
        if(price!=null)request.put("costPrice",new BigDecimal(price));
        return call(patch("/api/v1/menu/"+(source.getItemType()==ItemType.INGREDIENT?"ingredients/":"items/")+source.getId()),request,ALL).expect(200);
    }
    String costPath(Reply order,int index){return path(order)+"/items/"+order.line(index)+"/cost-snapshot";}
    Reply cost(Reply order,int index)throws Exception{return call(get(costPath(order,index)),null,COST_READ);}
    void withoutCostFeature(String code){setFeatures(COST_FEATURES.stream().filter(f->!f.equals(code)).toList());}

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
    Reply movement(UUID target,Item source,String type,String quantity,String cost,String direction)throws Exception{var part=new LinkedHashMap<String,Object>();part.put(source.getItemType()==ItemType.MENU_ITEM?"itemId":"ingredientId",source.getId());part.put("quantity",new BigDecimal(quantity));part.put("unitCost",new BigDecimal(cost));if(direction!=null)part.put("adjustmentDirection",direction);return call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType",type,"warehouseId",target,"items",List.of(part)),ALL);}
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
    record Reply(int status,JsonNode body,String cache){UUID id(){return UUID.fromString(body.path("id").asText());}UUID line(int index){return UUID.fromString(body.at("/items/"+index+"/id").asText());}long version(){return body.path("version").asLong();}Reply expect(int code){assertThat(status).as(body.toString()).isEqualTo(code);return this;}Reply error(int status,String code){expect(status);assertThat(body.path("code").asText()).isEqualTo(code);return this;}}
    List<Reply> race(Callable<Reply> one,Callable<Reply> two)throws Exception{var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return one.call();});var b=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return two.call();});assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}}
}
