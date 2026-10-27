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
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(OrderRecipeSnapshotIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OrderRecipeSnapshotIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-20T08:00:00Z");
    static final String SCHEMA="it_order_recipe_"+UUID.randomUUID().toString().replace("-","");
    static final Set<String> ALL=Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE","ORDER_CANCEL","RECIPE_VIEW",
        "ORDER_MARK_SERVED","KITCHEN_MARK_READY","KITCHEN_START_COOKING","TABLE_OPEN");
    static final List<String> FEATURES=List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT","RECIPE_MANAGEMENT","KITCHEN_DISPLAY");
    @TestConfiguration static class FixedTime { @Bean @Primary Clock snapshotClock() {return Clock.fixed(NOW,ZoneOffset.UTC);} }
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA)
            :base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url);p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants; @Autowired UserRepository users; @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages; @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository items; @MockitoSpyBean RecipeRepository recipes; @MockitoSpyBean RecipeItemRepository recipeItems;
    @MockitoSpyBean OrderItemRecipeSnapshotRepository snapshots; @Autowired OrderItemRecipeUsageRepository usages;
    @Autowired PlatformTransactionManager transactions; @MockitoSpyBean AuditService audit;
    Restaurant restaurant; User user; RestaurantSubscription subscription; Item menu, ingredient; Recipe recipe;
    @BeforeEach void setup() {
        restaurant=new Restaurant();restaurant.setCode("SNAP"+UUID.randomUUID().toString().replace("-",""));restaurant.setName("Snapshot test");
        restaurant=restaurants.saveAndFlush(restaurant);
        user=new User();user.setRestaurantId(restaurant.getId());user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Staff");user.setEmail(UUID.randomUUID()+"@test.example");user.setPasswordHash("fixture");user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription();subscription.setRestaurantId(restaurant.getId());subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);subscription.setStartAt(NOW.minusSeconds(60));subscription.setEndAt(NOW.plusSeconds(3600));
        subscription.setPriceAmount(BigDecimal.ZERO);subscription.setCurrencyCode("VND");subscription.setFeatureSnapshot(features(FEATURES));
        subscription=subscriptions.saveAndFlush(subscription);
        ingredient=item("Ingredient",ItemType.INGREDIENT,"g",true);
        menu=item("Food",ItemType.MENU_ITEM,"portion",false);
        recipe=recipe(menu,ingredient,1,"1000","5");
    }
    @AfterEach void clearMocks() {reset(audit);}
    @AfterAll void cleanup() {
        if(!SCHEMA.matches("it_order_recipe_[0-9a-f]{32}"))throw new IllegalStateException();
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }

    @Test void draftAndDraftQuantityEditsDoNotCaptureAndGetNeverWrites() throws Exception {
        var draft=create(false,"3");draft.expect(201);
        var edited=call(patch(orderPath(draft)+"/items/"+draft.line()),Map.of("expectedVersion",draft.version(),"quantity",4),ALL);edited.expect(200);
        long audits=count("audit_logs");
        var view=read(edited);view.expect(200);assertThat(view.body.path("snapshotStatus").asText()).isEqualTo("NOT_SUBMITTED");
        assertThat(view.body.path("orderQuantity").decimalValue()).isEqualByComparingTo("4");
        assertThat(count("order_item_recipe_snapshots")).isZero();assertThat(count("order_item_recipe_usages")).isZero();
        assertThat(count("audit_logs")).isEqualTo(audits);
    }
    @Test void confirmUsesRecipeActiveAtSubmissionRatherThanDraftCreation() throws Exception {
        var draft=create(false,"3");draft.expect(201);replaceRecipe("2000");
        var confirmed=confirm(draft);confirmed.expect(200);
        var view=read(confirmed);view.expect(200);
        assertThat(view.body.path("recipeVersion").asInt()).isEqualTo(2);
        assertThat(view.body.at("/ingredients/0/requiredQuantity").decimalValue()).isEqualByComparingTo("630.000");
    }
    @Test void submitCapturesBusinessVersionYieldWasteAndQuantityWithoutWarehouseOrRecipePermission() throws Exception {
        var request=body(true,"3");
        var order=call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,Set.of("ORDER_CREATE","ORDER_UPDATE"));order.expect(201);
        var view=read(order);view.expect(200);assertThat(view.cache).contains("no-store");
        assertThat(view.body.path("snapshotStatus").asText()).isEqualTo("CAPTURED");
        assertThat(view.body.path("recipeVersion").asInt()).isEqualTo(1);
        assertThat(view.body.path("capturedAt").asText()).isEqualTo(NOW.toString());
        assertThat(view.body.path("yieldQuantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(view.body.at("/ingredients/0/requiredQuantity").decimalValue()).isEqualByComparingTo("315.000");
        assertThat(view.body.toString()).doesNotContain("costPrice","unitCost","creationTransactionId");
        assertThat(count("warehouses")).isZero();assertThat(count("inventory_balances")).isZero();assertThat(count("inventory_transactions")).isZero();
    }
    @Test void appendToOpenCapturesAllLiveLinesButNotPreviouslyCancelledDraftLine() throws Exception {
        var draft=create(false,"3");draft.expect(201);
        var extra=call(post(orderPath(draft)+"/items"),Map.of("expectedVersion",draft.version(),"items",List.of(line(menu.getId(),"1"))),ALL);extra.expect(200);
        UUID cancelled=UUID.fromString(extra.body.at("/items/1/id").asText());
        var after=call(post(orderPath(draft)+"/items/"+cancelled+"/cancel"),Map.of("expectedVersion",extra.version(),"reason","not needed"),ALL);after.expect(200);
        var submitted=append(after,"2",UUID.randomUUID().toString());submitted.expect(200);
        assertThat(submitted.body.path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(2);
        assertThat(snapshots.findByRestaurantIdAndOrderItemId(restaurant.getId(),cancelled)).isEmpty();
        assertThat(count("order_item_recipe_usages")).isEqualTo(2);
    }
    @Test void appendKeepsOldRecipeAndCreatesSeparateLineWithCurrentRecipe() throws Exception {
        var first=create(true,"3");first.expect(201);var old=read(first).body;
        replaceRecipe("2000");var added=append(first,"2",UUID.randomUUID().toString());added.expect(200);
        assertThat(added.body.path("items").size()).isEqualTo(2);
        assertThat(read(first).body).isEqualTo(old);
        UUID newLine=UUID.fromString(added.body.at("/items/1/id").asText());
        var current=read(first.id(),newLine,ALL);current.expect(200);
        assertThat(current.body.path("recipeVersion").asInt()).isEqualTo(2);
        assertThat(current.body.at("/ingredients/0/requiredQuantity").decimalValue()).isEqualByComparingTo("420");
    }
    @Test void noRecipeIsDurableEvenIfRecipeAppearsLater() throws Exception {
        recipe.deactivate(NOW);recipes.saveAndFlush(recipe);menu=items.findById(menu.getId()).orElseThrow();menu.setRecipeEnabled(false);menu=items.saveAndFlush(menu);
        var order=create(true,"3");order.expect(201);
        assertThat(read(order).body.path("snapshotStatus").asText()).isEqualTo("RECIPE_DISABLED");
        recipe(menu,ingredient,2,"2000","0");
        assertThat(read(order).body.path("snapshotStatus").asText()).isEqualTo("RECIPE_DISABLED");
        assertThat(count("order_item_recipe_usages")).isZero();
    }
    @Test void featureDisabledDoesNotBlockSaleAndCannotRetroactivelyAcquireRecipe() throws Exception {
        setFeatures(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER"));
        var order=create(true,"3");order.expect(201);
        assertThat(jdbc.queryForObject("select snapshot_status from order_item_recipe_snapshots where restaurant_id=?",String.class,restaurant.getId())).isEqualTo("FEATURE_DISABLED");
        setFeatures(FEATURES);
        var view=read(order);view.expect(200);assertThat(view.body.path("snapshotStatus").asText()).isEqualTo("FEATURE_DISABLED");
        assertThat(view.body.path("recipeId").isNull()).isTrue();assertThat(view.body.path("ingredients")).isEmpty();
        var added=append(order,"1",UUID.randomUUID().toString());added.expect(200);
        assertThat(read(order.id(),UUID.fromString(added.body.at("/items/1/id").asText()),ALL).body.path("snapshotStatus").asText()).isEqualTo("CAPTURED");
    }
    @Test void currentCostNamesTrackingStatusAndSoftDeletionDoNotChangeHistoricalSnapshot() throws Exception {
        ingredient.setTrackInventory(false);items.saveAndFlush(ingredient);
        var order=create(true,"3");order.expect(201);var before=read(order).body;
        assertThat(before.at("/ingredients/0/trackInventory").asBoolean()).isFalse();
        jdbc.update("update items set name='New name',sku='NEW',cost_price=999,track_inventory=true,is_active=false,deleted_at=? where id=?",
            java.sql.Timestamp.from(NOW),ingredient.getId());
        recipe.deactivate(NOW);recipes.saveAndFlush(recipe);
        assertThat(read(order).body).isEqualTo(before);
    }
    @Test void cancellationsAndKitchenTransitionsKeepSnapshotAndAuditCountStable() throws Exception {
        var order=create(true,"3");order.expect(201);var before=read(order).body;
        var cooking=call(post(orderPath(order)+"/items/"+order.line()+"/start-cooking"),Map.of("expectedOrderVersion",order.version()),ALL);cooking.expect(200);
        var ready=call(post(orderPath(order)+"/mark-ready"),Map.of("expectedOrderVersion",cooking.version()),ALL);ready.expect(200);
        var served=call(post(orderPath(order)+"/mark-served"),Map.of("expectedOrderVersion",ready.version()),ALL);served.expect(200);
        assertThat(read(order).body).isEqualTo(before);
        assertThat(events()).isEqualTo(1);
        var other=create(true,"1");other.expect(201);var otherView=read(other).body;
        call(post(orderPath(other)+"/cancel"),Map.of("expectedVersion",other.version(),"reason","cancel"),ALL).expect(200);
        assertThat(read(other).body).isEqualTo(otherView);assertThat(events()).isEqualTo(2);
    }
    @Test void createAndAppendReplayDoNotCaptureAgainAfterRecipesChange() throws Exception {
        String key=UUID.randomUUID().toString();var request=body(true,"3");
        var order=call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL);order.expect(201);
        replaceRecipe("2000");
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL).expect(200);
        String appendKey=UUID.randomUUID().toString();var appendBody=addition(order,"1");
        var appended=call(post(orderPath(order)+"/items").header("Idempotency-Key",appendKey),appendBody,ALL);appended.expect(200);
        replaceRecipe("3000");
        call(post(orderPath(order)+"/items").header("Idempotency-Key",appendKey),appendBody,ALL).expect(200);
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(2);assertThat(count("order_item_recipe_usages")).isEqualTo(2);assertThat(events()).isEqualTo(2);
    }
    @Test void concurrentCreateReplayProducesOneSnapshotSet() throws Exception {
        String key=UUID.randomUUID().toString();var request=body(true,"3");
        var responses=race(()->call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL),
            ()->call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL));
        assertThat(responses.stream().map(Reply::status)).containsExactlyInAnyOrder(201,200);
        assertThat(count("orders")).isEqualTo(1);assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);assertThat(events()).isEqualTo(1);
    }
    @Test void concurrentAppendReplayProducesOneNewSnapshot() throws Exception {
        var order=create(true,"3");order.expect(201);String key=UUID.randomUUID().toString();var request=addition(order,"2");
        var responses=race(()->call(post(orderPath(order)+"/items").header("Idempotency-Key",key),request,ALL),
            ()->call(post(orderPath(order)+"/items").header("Idempotency-Key",key),request,ALL));
        assertThat(responses).allMatch(r->r.status()==200);assertThat(count("order_item_recipe_snapshots")).isEqualTo(2);assertThat(events()).isEqualTo(2);
    }
    @Test void recipeVersionCreationAndOrderSubmitUseTheSharedTenantLock() throws Exception {
        var permissions=new HashSet<>(ALL);permissions.add("RECIPE_UPDATE");
        UUID originalRecipe=recipe.getId();
        var versionBody=Map.of("expectedVersion",recipe.getVersion(),"yieldQuantity",10,
            "ingredients",List.of(Map.of("ingredientId",ingredient.getId(),"quantity",2000,"wastePercent",5)));
        var responses=race(()->create(true,"3"),()->call(post("/api/v1/menu/items/"+menu.getId()+"/recipes/"+originalRecipe+"/versions"),versionBody,permissions));
        var order=responses.getFirst();order.expect(201);assertThat(responses.get(1).status()).as(responses.get(1).body().toString()).isIn(200,201);
        var view=read(order);view.expect(200);int version=view.body.path("recipeVersion").asInt();
        assertThat(version).isIn(1,2);
        assertThat(view.body.at("/ingredients/0/requiredQuantity").decimalValue()).isEqualByComparingTo(version==1?"315":"630");
        assertThat(view.body.path("recipeId").asText()).isEqualTo(version==1?originalRecipe.toString():responses.get(1).body.path("id").asText());
    }
    @Test void legacyMarkerIsReadWithoutLookingUpTodaysActiveRecipe() throws Exception {
        var order=create(false,"3");order.expect(201);
        jdbc.update("update orders set status='CONFIRMED',confirmed_at=? where id=?",java.sql.Timestamp.from(NOW),order.id());
        jdbc.update("update order_items set sent_to_kitchen_at=? where id=?",java.sql.Timestamp.from(NOW),order.line());
        jdbc.update("insert into order_item_recipe_snapshots (restaurant_id,order_item_id,menu_item_id_snapshot,snapshot_status,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at) values (?,?,?,'LEGACY_UNAVAILABLE',3,'portion',null,now())",restaurant.getId(),order.line(),menu.getId());
        clearInvocations(recipes,recipeItems);var view=read(order);view.expect(200);
        assertThat(view.body.path("snapshotStatus").asText()).isEqualTo("LEGACY_UNAVAILABLE");
        assertThat(view.body.path("recipeId").isNull()).isTrue();assertThat(view.body.path("capturedAt").isNull()).isTrue();assertThat(view.body.path("ingredients")).isEmpty();
        verifyNoInteractions(recipes,recipeItems);
    }
    @ParameterizedTest @ValueSource(strings={"yield","ingredient","inactive","deleted","empty","overflow"})
    void invalidActiveRecipeFailsSpecificallyAndRollsBackWholeOrder(String problem) throws Exception {
        switch(problem) {
            case "yield" -> jdbc.update("update recipes set yield_unit='kg' where id=?",recipe.getId());
            case "ingredient" -> jdbc.update("update items set base_unit='kg' where id=?",ingredient.getId());
            case "inactive" -> jdbc.update("update items set is_active=false where id=?",ingredient.getId());
            case "deleted" -> jdbc.update("update items set deleted_at=? where id=?",java.sql.Timestamp.from(NOW),ingredient.getId());
            case "empty" -> jdbc.update("delete from recipe_items where recipe_id=?",recipe.getId());
            case "overflow" -> {jdbc.update("update recipes set yield_quantity=0.001 where id=?",recipe.getId());jdbc.update("update recipe_items set quantity=999999999.999 where recipe_id=?",recipe.getId());}
        }
        String error=switch(problem) {case "yield"->"RECIPE_YIELD_UNIT_MISMATCH";case "ingredient"->"RECIPE_INGREDIENT_UNIT_MISMATCH";case "empty"->"INVALID_RECIPE_SNAPSHOT_DATA";case "overflow"->"RECIPE_USAGE_QUANTITY_OUT_OF_RANGE";default->"RECIPE_INGREDIENT_UNAVAILABLE";};
        create(true,"3").error(409,error);
        assertThat(count("orders")).isZero();assertThat(count("order_items")).isZero();assertThat(count("order_item_recipe_snapshots")).isZero();assertThat(events()).isZero();
    }
    @Test void failedSnapshotAuditRollsBackRootLinesSnapshotHistoryAndCreateKey() throws Exception {
        doThrow(new IllegalStateException("forced snapshot audit failure")).when(audit).recordAll(anyList());
        String key=UUID.randomUUID().toString();var request=body(true,"3");
        call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL).expect(500);
        for(String table:List.of("orders","order_items","order_item_recipe_snapshots","order_item_recipe_usages","order_item_status_history","audit_logs"))assertThat(count(table)).as(table).isZero();
        reset(audit);call(post("/api/v1/orders").header("Idempotency-Key",key),request,ALL).expect(201);
    }
    @Test void failedAppendSnapshotRollsBackAddedLineAndAppendLedger() throws Exception {
        var order=create(true,"3");order.expect(201);var old=read(order).body;
        doThrow(new IllegalStateException("forced snapshot audit failure")).when(audit).recordAll(anyList());
        String key=UUID.randomUUID().toString();var request=addition(order,"2");
        call(post(orderPath(order)+"/items").header("Idempotency-Key",key),request,ALL).expect(500);
        assertThat(count("order_items")).isEqualTo(1);assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);assertThat(count("order_item_submissions")).isZero();
        reset(audit);assertThat(read(order).body).isEqualTo(old);
        call(post(orderPath(order)+"/items").header("Idempotency-Key",key),request,ALL).expect(200);
    }
    @Test void snapshotEndpointRequiresBothPermissionsFeatureAndOwnedOrderAndLine() throws Exception {
        var order=create(true,"3");order.expect(201);
        read(order.id(),order.line(),Set.of("ORDER_VIEW")).error(403,"FORBIDDEN");
        read(order.id(),order.line(),Set.of("RECIPE_VIEW")).error(403,"FORBIDDEN");
        call(get(orderPath(order)+"/items/"+order.line()+"/recipe-snapshot"),null,Set.of()).expect(403);
        read(UUID.randomUUID(),order.line(),ALL).error(404,"ORDER_NOT_FOUND");
        read(order.id(),UUID.randomUUID(),ALL).error(404,"ORDER_ITEM_NOT_FOUND");
        var other=create(true,"1");other.expect(201);read(order.id(),other.line(),ALL).error(404,"ORDER_ITEM_NOT_FOUND");
        setFeatures(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER"));read(order).error(403,"FEATURE_NOT_ENTITLED");
    }
    @Test void snapshotEndpointHidesForeignTenantAndUnauthenticatedCall() throws Exception {
        var order=create(true,"3");order.expect(201);
        var response=mvc.perform(get(orderPath(order)+"/items/"+order.line()+"/recipe-snapshot")).andReturn().getResponse();assertThat(response.getStatus()).isEqualTo(401);
        // A valid actor/subscription of another tenant must receive 404, not the first tenant's data.
        setup();read(order).error(404,"ORDER_NOT_FOUND");
    }
    @Test void readsAreImmutableAndRegularOrderResponsesDoNotExposeRecipeData() throws Exception {
        var order=create(true,"3");order.expect(201);long audits=count("audit_logs");
        var before=read(order);before.expect(200);var again=read(order);again.expect(200);assertThat(again.body).isEqualTo(before.body);
        assertThat(count("audit_logs")).isEqualTo(audits);
        var regular=call(get(orderPath(order)),null,Set.of("ORDER_VIEW"));regular.expect(200);
        assertThat(regular.body.toString()).doesNotContain("recipeId","recipeVersion","ingredients","requiredQuantity","costPrice");
        assertThat(regular.version()).isEqualTo(order.version());
    }
    @Test void quantityDriftIsConflictRatherThanRewritingHistory() throws Exception {
        var order=create(true,"3");order.expect(201);
        jdbc.update("update order_items set quantity=4 where id=?",order.line());
        read(order).error(409,"ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT");
        assertThat(jdbc.queryForObject("select order_quantity_snapshot from order_item_recipe_snapshots where restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("3");
    }
    @Test void cancellingOneSubmittedLineRetainsItsSnapshot() throws Exception {
        var order=create(true,"3");order.expect(201);var before=read(order).body;
        var added=append(order,"1",UUID.randomUUID().toString());added.expect(200);
        call(post(orderPath(order)+"/items/"+order.line()+"/cancel"),Map.of("expectedVersion",added.version(),"reason","cancel line"),ALL).expect(200);
        assertThat(read(order).body).isEqualTo(before);assertThat(events()).isEqualTo(2);
    }
    @Test void receiptRepricesIngredientWithoutMutatingExistingUsageSnapshot() throws Exception {
        var order=create(true,"3");order.expect(201);var before=read(order).body;
        var enabled=new ArrayList<>(FEATURES);enabled.add("INVENTORY_MANAGEMENT");setFeatures(enabled);
        var permissions=new HashSet<>(ALL);permissions.add("WAREHOUSE_CREATE");permissions.add("INVENTORY_RECEIVE");
        var warehouse=call(post("/api/v1/inventory/warehouses"),Map.of("code","MAIN","name","Main"),permissions);warehouse.expect(201);
        var receipt=call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),
            Map.of("documentType","RECEIPT","warehouseId",warehouse.id(),"items",List.of(Map.of("ingredientId",ingredient.getId(),"quantity",10,"unitCost",20))),permissions);receipt.expect(201);
        call(post("/api/v1/inventory/stock-documents/"+receipt.id()+"/confirm"),Map.of("expectedVersion",receipt.version()),permissions).expect(200);
        assertThat(items.findById(ingredient.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("20");
        assertThat(read(order).body).isEqualTo(before);assertThat(events()).isEqualTo(1);
    }
    @Test void batchRecipeAndUsageQueriesRemainConstantForManySubmittedMenuItems() throws Exception {
        var requested=new ArrayList<Map<String,Object>>();requested.add(line(menu.getId(),"3"));
        for(int i=0;i<8;i++){var next=item("Menu"+i,ItemType.MENU_ITEM,"portion",false);recipe(next,ingredient,1,"1000","5");requested.add(line(next.getId(),"1"));}
        clearInvocations(recipes,recipeItems,snapshots);
        var request=body(true,"3");request.put("items",requested);
        call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),request,ALL).expect(201);
        verify(recipes,times(1)).findAllByRestaurantIdAndMenuItemIdInAndActiveTrue(eq(restaurant.getId()),anyCollection());
        verify(recipeItems,times(1)).findAllByRestaurantIdAndRecipeIdInOrderByRecipeIdAscIngredientIdAsc(eq(restaurant.getId()),anyCollection());
        // Capture checks existing decisions once; Phase 5 reads the persisted snapshot batch once for allocation DTOs.
        verify(snapshots,times(2)).findAllByRestaurantIdAndOrderItemIdIn(eq(restaurant.getId()),anyCollection());
        verify(recipes,never()).findByRestaurantIdAndMenuItemIdAndActiveTrue(any(),any());
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(9);
    }
    @Test void databasePreventsChangesAndAppendingIngredientsToCommittedSnapshot() throws Exception {
        var order=create(true,"3");order.expect(201);UUID snapshot=snapshots.findByRestaurantIdAndOrderItemId(restaurant.getId(),order.line()).orElseThrow().getId();
        assertThatThrownBy(()->jdbc.update("update order_item_recipe_snapshots set recipe_version=99 where id=?",snapshot)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("delete from order_item_recipe_snapshots where id=?",snapshot)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("update order_item_recipe_usages set required_quantity=1 where recipe_snapshot_id=?",snapshot)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("delete from order_item_recipe_usages where recipe_snapshot_id=?",snapshot)).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("insert into order_item_recipe_usages (restaurant_id,recipe_snapshot_id,ingredient_id,ingredient_name_snapshot,ingredient_unit_snapshot,recipe_quantity_snapshot,waste_percent_snapshot,track_inventory_snapshot,required_quantity,created_at) values (?,?,?,'extra','g',1,0,true,1,now())",restaurant.getId(),snapshot,ingredient.getId())).hasMessageContaining("immutable");
        assertThatThrownBy(()->jdbc.update("insert into order_item_recipe_snapshots (restaurant_id,order_item_id,snapshot_status,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot) values (?,?,'NO_RECIPE',3,'portion',now(),now(),true,'RECIPE')",restaurant.getId(),order.line())).hasMessageContaining("uq_order_recipe_snapshot_line");
    }
    @Test void databaseRequiresCapturedUsagesAndEnforcesUsageUniquenessInCreatingTransaction() throws Exception {
        var draft=create(false,"3");draft.expect(201);var tx=new TransactionTemplate(transactions);
        assertThatThrownBy(()->tx.executeWithoutResult(status->insertHeader(draft.line()))).hasMessageContaining("requires ingredient usages");
        assertThat(count("order_item_recipe_snapshots")).isZero();
        assertThatThrownBy(()->tx.executeWithoutResult(status->{UUID header=insertHeader(draft.line());insertUsage(header,ingredient.getId());insertUsage(header,ingredient.getId());})).hasMessageContaining("uq_order_recipe_usage_ingredient");
        assertThat(count("order_item_recipe_snapshots")).isZero();
    }
    @Test void databaseTenantForeignKeysRejectForeignLineAndIngredient() throws Exception {
        var draft=create(false,"3");draft.expect(201);Restaurant other=new Restaurant();other.setCode("FK"+UUID.randomUUID());other.setName("FK");other=restaurants.saveAndFlush(other);
        UUID otherId=other.getId();
        assertThatThrownBy(()->jdbc.update("insert into order_item_recipe_snapshots (restaurant_id,order_item_id,snapshot_status,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot) values (?,?,'NO_RECIPE',3,'portion',now(),now(),true,'RECIPE')",otherId,draft.line())).hasMessageContaining("fk_order_recipe_snapshot_line");
        Item foreign=new Item();foreign.initialize(otherId,NOW);foreign.setName("Foreign ingredient");foreign.setItemType(ItemType.INGREDIENT);foreign.setBaseUnit("g");foreign.setSalePrice(BigDecimal.ZERO);foreign=items.saveAndFlush(foreign);UUID foreignId=foreign.getId();
        assertThatThrownBy(()->new TransactionTemplate(transactions).executeWithoutResult(status->{UUID header=insertHeader(draft.line());insertUsage(header,foreignId);})).hasMessageContaining("fk_order_recipe_usage_ingredient");
    }

    UUID insertHeader(UUID line) {return jdbc.queryForObject("insert into order_item_recipe_snapshots (restaurant_id,order_item_id,menu_item_id_snapshot,snapshot_status,recipe_id,recipe_version,yield_quantity,yield_unit,order_quantity_snapshot,order_unit_snapshot,captured_at,created_at,recipe_enabled_snapshot,cost_source_snapshot) values (?,?,?,'CAPTURED',?,1,10,'portion',3,'portion',now(),now(),true,'RECIPE') returning id",UUID.class,restaurant.getId(),line,menu.getId(),recipe.getId());}
    void insertUsage(UUID header,UUID ingredientId) {jdbc.update("insert into order_item_recipe_usages (restaurant_id,recipe_snapshot_id,ingredient_id,ingredient_name_snapshot,ingredient_unit_snapshot,recipe_quantity_snapshot,waste_percent_snapshot,track_inventory_snapshot,required_quantity,created_at) values (?,?,?,'Ingredient','g',1000,5,true,315,now())",restaurant.getId(),header,ingredientId);}
    Item item(String name,ItemType type,String unit,boolean tracked) {var value=new Item();value.initialize(restaurant.getId(),NOW);value.setName(name);value.setItemType(type);value.setBaseUnit(unit);value.setTrackInventory(tracked);value.setSalePrice(type==ItemType.MENU_ITEM?new BigDecimal("50000"):BigDecimal.ZERO);value.setCostPrice(new BigDecimal("10"));value.setCostConfigured(true);return items.saveAndFlush(value);}
    Recipe recipe(Item menuItem,Item source,int version,String quantity,String waste) {menuItem=items.findById(menuItem.getId()).orElseThrow();menuItem.setRecipeEnabled(true);menuItem=items.saveAndFlush(menuItem);if(menu.getId().equals(menuItem.getId()))menu=menuItem;var value=new Recipe();value.initialize(restaurant.getId(),menuItem.getId(),version,new BigDecimal("10"),menuItem.getBaseUnit(),NOW);value=recipes.saveAndFlush(value);var part=new RecipeItem();part.initialize(restaurant.getId(),value.getId(),source,new BigDecimal(quantity),new BigDecimal(waste),NOW);recipeItems.saveAndFlush(part);return value;}
    void replaceRecipe(String quantity) {recipe=recipes.findById(recipe.getId()).orElseThrow();recipe.deactivate(NOW);recipes.saveAndFlush(recipe);recipe=recipe(menu,ingredient,recipe.getRecipeVersion()+1,quantity,"5");}
    Map<String,Object> features(List<String> codes) {return new SubscriptionFeatureSnapshot(1,"PRO",codes.stream().map(code->new SubscriptionFeatureSnapshot.FeatureGrant(code,Map.of())).toList(),NOW).toMap();}
    void setFeatures(List<String> codes) {subscription.setFeatureSnapshot(features(codes));subscription=subscriptions.saveAndFlush(subscription);}
    Map<String,Object> line(UUID id,String quantity) {return Map.of("itemId",id,"quantity",new BigDecimal(quantity));}
    Map<String,Object> body(boolean submit,String quantity) {var result=new LinkedHashMap<String,Object>();result.put("serviceType","TAKEAWAY");result.put("sourceChannel","CASHIER");result.put("submissionMode",submit?"SUBMIT":"DRAFT");result.put("items",List.of(line(menu.getId(),quantity)));return result;}
    Reply create(boolean submit,String quantity)throws Exception{return call(post("/api/v1/orders").header("Idempotency-Key",UUID.randomUUID().toString()),body(submit,quantity),ALL);}
    Reply confirm(Reply order)throws Exception{return call(post(orderPath(order)+"/confirm"),Map.of("expectedVersion",order.version()),ALL);}
    Map<String,Object> addition(Reply order,String quantity){return Map.of("expectedVersion",order.version(),"submissionMode","SUBMIT","items",List.of(line(menu.getId(),quantity)));}
    Reply append(Reply order,String quantity,String key)throws Exception{return call(post(orderPath(order)+"/items").header("Idempotency-Key",key),addition(order,quantity),ALL);}
    String orderPath(Reply order){return "/api/v1/orders/"+order.id();}
    Reply read(Reply order)throws Exception{return read(order.id(),order.line(),ALL);}
    Reply read(UUID order,UUID line,Set<String> permissions)throws Exception{return call(get("/api/v1/orders/"+order+"/items/"+line+"/recipe-snapshot"),null,permissions);}
    Reply call(MockHttpServletRequestBuilder request,Object body,Set<String> permissions)throws Exception {
        if(body!=null)request.content(json.writeValueAsString(body));var principal=new CurrentUser(user.getId(),restaurant.getId(),user.getEmail(),"OWNER",permissions);
        var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).with(authentication(new UsernamePasswordAuthenticationToken(principal,null,permissions.stream().map(SimpleGrantedAuthority::new).toList())))).andReturn().getResponse();
        return new Reply(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()),response.getHeader("Cache-Control"));
    }
    long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where restaurant_id=?",Long.class,restaurant.getId());}
    long events(){return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='ORDER_ITEM_RECIPE_SNAPSHOTTED'",Long.class,restaurant.getId());}
    record Reply(int status,JsonNode body,String cache){UUID id(){return UUID.fromString(body.path("id").asText());}UUID line(){return UUID.fromString(body.at("/items/0/id").asText());}long version(){return body.path("version").asLong();}void expect(int code){assertThat(status).as(body.toString()).isEqualTo(code);}void error(int status,String code){expect(status);assertThat(body.path("code").asText()).isEqualTo(code);}}
    List<Reply> race(Callable<Reply> one,Callable<Reply> two)throws Exception{var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return one.call();});var b=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return two.call();});assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}}
}
