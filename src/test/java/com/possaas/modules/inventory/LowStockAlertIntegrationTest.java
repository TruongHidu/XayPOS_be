package com.possaas.modules.inventory;

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
import com.possaas.modules.menu.repository.ItemRepository;
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
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(LowStockAlertIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LowStockAlertIntegrationTest {
    static final String SCHEMA="it_stock_alert_"+UUID.randomUUID().toString().replace("-","");
    static final String ALERTS="/api/v1/inventory/alerts";
    static final String WAREHOUSES="/api/v1/inventory/warehouses";
    static final Instant NOW=Instant.parse("2026-10-21T08:00:00Z");
    static final Set<String> ALL=Set.of("INVENTORY_VIEW","INVENTORY_ALERT_ACKNOWLEDGE","WAREHOUSE_CREATE",
        "WAREHOUSE_UPDATE","WAREHOUSE_DELETE","INVENTORY_THRESHOLD_UPDATE","INVENTORY_RECEIVE",
        "INVENTORY_ISSUE","INVENTORY_ADJUST","INGREDIENT_UPDATE","INGREDIENT_DELETE");
    @TestConfiguration static class FixedTime {@Bean @Primary Clock alertClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA)
            :base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url);p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;@Autowired UserRepository users;@Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages;@Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository items;
    @MockitoSpyBean AuditService audits;
    @MockitoSpyBean LowStockAlertBalanceQuery projection;
    @MockitoSpyBean LowStockAlertRepository alertRepository;
    User user;Item ingredient;UUID warehouse;RestaurantSubscription subscription;

    @BeforeEach void setup() throws Exception {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
        var restaurant=new Restaurant();restaurant.setCode("ALERT"+UUID.randomUUID().toString().replace("-",""));restaurant.setName("Alert test");restaurant=restaurants.saveAndFlush(restaurant);
        user=new User();user.setRestaurantId(restaurant.getId());user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Owner");user.setEmail(UUID.randomUUID()+"@alert.test");user.setPasswordHash("fixture");user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription();subscription.setRestaurantId(user.getRestaurantId());subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(NOW.minusSeconds(60));subscription.setEndAt(NOW.plusSeconds(3600));subscription.setPriceAmount(BigDecimal.ZERO);subscription.setCurrencyCode("VND");
        features(Set.of("INVENTORY_MANAGEMENT","LOW_STOCK_ALERT","RECIPE_MANAGEMENT"));
        ingredient=new Item();ingredient.initialize(user.getRestaurantId(),NOW);ingredient.setItemType(ItemType.INGREDIENT);ingredient.setName("Thịt bò");ingredient.setSku("BEEF_%");ingredient.setBaseUnit("g");ingredient.setTrackInventory(true);ingredient.setActive(true);ingredient.setSalePrice(BigDecimal.ZERO);ingredient.setCostPrice(BigDecimal.TEN);ingredient.setAvailabilityStatus(AvailabilityStatus.AVAILABLE);ingredient=items.saveAndFlush(ingredient);
        warehouse=call(post(WAREHOUSES),Map.of("code","MAIN","name","Kho chính"),ALL).expect(201).id();
    }
    @AfterEach void resetSpies(){reset(audits,projection,alertRepository);}
    @AfterAll void cleanup(){if(!SCHEMA.matches("it_stock_alert_[0-9a-f]{32}"))throw new IllegalStateException();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}

    @Test void logicalZeroReadsAndDisabledThresholdNeverCreateRowsOrAlerts() throws Exception {
        call(get(WAREHOUSES+"/"+warehouse+"/balances/"+ingredient.getId()),null,ALL).expect(200);
        threshold("0").expect(200);assertThat(count("inventory_balances")).isZero();assertThat(count("low_stock_alerts")).isZero();
        long before=count("audit_logs");list().expect(200);assertThat(count("audit_logs")).isEqualTo(before);
    }
    @Test void zeroPhysicalTriggersEvenForInactiveTrackedIngredientAndKeepsSnapshotThroughThresholdChanges() throws Exception {
        ingredient.setActive(false);items.saveAndFlush(ingredient);
        threshold("20").expect(200);var alert=only();assertThat(alert.body.path("status").asText()).isEqualTo("OPEN");
        assertThat(alert.body.path("triggeredAt").asText()).isEqualTo(NOW.toString());
        assertThat(alert.body.path("quantityAtTrigger").decimalValue()).isEqualByComparingTo("0");
        assertThat(call(get("/api/v1/inventory/low-stock"),null,ALL).expect(200).body.path("totalElements").asInt()).isEqualTo(1);
        threshold("30").expect(200);var updated=detail(alert).expect(200);
        assertThat(updated.body.path("thresholdQuantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(updated.body.path("currentMinQuantity").decimalValue()).isEqualByComparingTo("30");assertThat(updated.version()).isZero();
        assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(1);
    }
    @Test void recoveryAndSecondShortageCreateNewEpisodesWithAcknowledgementPreserved() throws Exception {
        movement("RECEIPT","100",null).expect(200);threshold("20");assertThat(count("low_stock_alerts")).isZero();
        movement("ISSUE","80",null).expect(200);var alert=only();
        assertThat(alert.body.path("quantityAtTrigger").decimalValue()).isEqualByComparingTo("20");ack(alert,0).expect(200);
        movement("RECEIPT","1",null).expect(200);var resolved=detail(alert).expect(200);
        assertThat(resolved.body.path("status").asText()).isEqualTo("RESOLVED");assertThat(resolved.body.path("resolutionReason").asText()).isEqualTo("STOCK_RECOVERED");
        assertThat(resolved.body.path("acknowledgedBy").asText()).isEqualTo(user.getId().toString());
        movement("ISSUE","1",null).expect(200);assertThat(count("low_stock_alerts")).isEqualTo(2);
        var open=call(get(ALERTS).param("status","OPEN"),null,ALL).expect(200);assertThat(open.body.at("/content/0/id").asText()).isNotEqualTo(alert.id().toString());
        assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(2);assertThat(events("LOW_STOCK_ALERT_RESOLVED")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"INCREASE","DECREASE"})
    void adjustmentsReconcileFinalPhysical(String direction) throws Exception {
        movement("RECEIPT","30",null).expect(200);threshold("20");
        if(direction.equals("DECREASE")) {movement("ADJUSTMENT","10",direction).expect(200);assertThat(only().body.path("status").asText()).isEqualTo("OPEN");}
        else {threshold("40");var alert=only();movement("ADJUSTMENT","11",direction).expect(200);assertThat(detail(alert).body.path("status").asText()).isEqualTo("RESOLVED");}
    }
    @Test void stillLowAndAcknowledgedNeverResetAndReceiptReplayDoesNotDuplicate() throws Exception {
        threshold("20");var alert=only();var acknowledged=ack(alert,0).expect(200);
        var draft=draft("RECEIPT","5",null);confirm(draft).expect(200);confirm(draft).expect(200);threshold("25");
        var current=detail(alert).expect(200);assertThat(current.version()).isEqualTo(acknowledged.version());
        assertThat(current.body.path("status").asText()).isEqualTo("ACKNOWLEDGED");assertThat(current.body.path("thresholdQuantity").decimalValue()).isEqualByComparingTo("20");
        assertThat(count("low_stock_alerts")).isEqualTo(1);assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(1);
    }
    @Test void acknowledgementIsIdempotentAndRejectsStaleOpenAndResolved() throws Exception {
        threshold("20");var alert=only();ack(alert,9).error(409,"CONCURRENT_LOW_STOCK_ALERT_UPDATE");
        var first=ack(alert,0).expect(200);var again=ack(alert,0).expect(200);assertThat(again.body).isEqualTo(first.body);
        assertThat(events("LOW_STOCK_ALERT_ACKNOWLEDGED")).isEqualTo(1);
        threshold("0");assertThat(detail(alert).body.path("resolutionReason").asText()).isEqualTo("THRESHOLD_DISABLED");
        ack(alert,first.version()).error(409,"INVALID_LOW_STOCK_ALERT_TRANSITION");
        threshold("20");assertThat(count("low_stock_alerts")).isEqualTo(2);
    }
    @Test void concurrentAcknowledgementsProduceOnlyOneTransitionAudit() throws Exception {
        threshold("20");var alert=only();var results=race(()->ack(alert,0),()->ack(alert,0));
        results.forEach(r->r.expect(200));assertThat(events("LOW_STOCK_ALERT_ACKNOWLEDGED")).isEqualTo(1);
        assertThat(detail(alert).version()).isEqualTo(1);
    }
    @Test void acknowledgeRacingRecoveryCannotReviveAlert() throws Exception {
        threshold("20");var alert=only();var receipt=draft("RECEIPT","21",null);
        var results=race(()->ack(alert,0),()->confirm(receipt));
        assertThat(results.get(1).status).isEqualTo(200);assertThat(results.get(0).status).isIn(200,409);
        assertThat(detail(alert).body.path("status").asText()).isEqualTo("RESOLVED");
        assertThat(events("LOW_STOCK_ALERT_RESOLVED")).isEqualTo(1);
    }
    @Test void concurrentInitialThresholdConfigurationCreatesOnlyOneOpenEpisode() throws Exception {
        String url=WAREHOUSES+"/"+warehouse+"/balances/"+ingredient.getId()+"/threshold";
        JsonNode request=json.readTree("{\"expectedVersion\":null,\"minQuantity\":20}");
        var results=race(()->call(patch(url),request,ALL),()->call(patch(url),request,ALL));
        assertThat(results.stream().filter(r->r.status==200).count()).isEqualTo(1);
        assertThat(results.stream().filter(r->r.status==409).count()).isEqualTo(1);
        assertThat(count("low_stock_alerts")).isEqualTo(1);assertThat(events("LOW_STOCK_ALERT_TRIGGERED")).isEqualTo(1);
    }
    @Test void expiredSubscriptionAndInactiveRestaurantRemainBlocked() throws Exception {
        threshold("20");subscription.setEndAt(NOW.minusSeconds(1));subscription=subscriptions.saveAndFlush(subscription);
        list().error(403,"SUBSCRIPTION_NOT_ACTIVE");subscription=subscriptions.findById(subscription.getId()).orElseThrow();
        subscription.setStatus(SubscriptionStatus.ACTIVE);subscription.setEndAt(NOW.plusSeconds(3600));subscription=subscriptions.saveAndFlush(subscription);
        var restaurant=restaurants.findById(user.getRestaurantId()).orElseThrow();restaurant.setStatus(com.possaas.modules.restaurant.entity.RestaurantStatus.SUSPENDED);restaurants.saveAndFlush(restaurant);
        list().error(403,"RESTAURANT_INACTIVE");
    }
    @ParameterizedTest @ValueSource(strings={"disable","delete"})
    void warehouseLifecycleClosesAndEnableReopensOnlyItsOwnEpisodes(String action) throws Exception {
        var second=call(post(WAREHOUSES),Map.of("code","SECOND","name","Kho phụ"),ALL).expect(201);
        UUID main=warehouse;warehouse=second.id();threshold("20");var alert=only();
        if(action.equals("disable")) {
            var disabled=call(patch(WAREHOUSES+"/"+warehouse+"/status"),Map.of("expectedVersion",0,"active",false),ALL).expect(200);
            assertThat(detail(alert).body.path("resolutionReason").asText()).isEqualTo("WAREHOUSE_INACTIVE");
            call(patch(WAREHOUSES+"/"+warehouse+"/status"),Map.of("expectedVersion",disabled.version(),"active",true),ALL).expect(200);
            assertThat(count("low_stock_alerts")).isEqualTo(2);
        } else {
            call(delete(WAREHOUSES+"/"+warehouse).param("expectedVersion","0"),null,ALL).expect(204);
            assertThat(detail(alert).expect(200).body.path("resolutionReason").asText()).isEqualTo("WAREHOUSE_DELETED");
            assertThat(detail(alert).body.path("currentQuantity").isNull()).isTrue();
        }
        assertThat(call(get(WAREHOUSES+"/"+main),null,ALL).expect(200).body.path("isDefault").asBoolean()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings={"tracking","delete","unit","inactive"})
    void ingredientLifecyclePreservesHistoryWithoutNewBlockingRules(String action) throws Exception {
        threshold("20");var alert=only();String url="/api/v1/menu/ingredients/"+ingredient.getId();
        long version=items.findById(ingredient.getId()).orElseThrow().getVersion();
        switch(action) {
            case "tracking" -> {
                var off=call(patch(url),Map.of("expectedVersion",version,"trackInventory",false),ALL).expect(200);
                assertThat(detail(alert).body.path("resolutionReason").asText()).isEqualTo("INGREDIENT_UNTRACKED");
                call(patch(url),Map.of("expectedVersion",off.version(),"trackInventory",true),ALL).expect(200);assertThat(count("low_stock_alerts")).isEqualTo(2);
            }
            case "delete" -> {
                call(delete(url).param("expectedVersion",String.valueOf(version)),null,ALL).expect(204);
                assertThat(detail(alert).expect(200).body.path("resolutionReason").asText()).isEqualTo("INGREDIENT_DELETED");
            }
            case "unit" -> {
                call(patch(url),Map.of("expectedVersion",version,"baseUnit","kg"),ALL).expect(200);
                var old=detail(alert).expect(200);assertThat(old.body.path("resolutionReason").asText()).isEqualTo("INGREDIENT_UNIT_CHANGED");
                assertThat(old.body.path("unit").asText()).isEqualTo("g");assertThat(old.body.path("currentQuantity").isNull()).isTrue();
                assertThat(call(get(ALERTS).param("status","OPEN"),null,ALL).expect(200).body.at("/content/0/unit").asText()).isEqualTo("kg");
            }
            default -> {
                call(patch(url+"/status"),Map.of("expectedVersion",version,"active",false),ALL).expect(200);
                assertThat(detail(alert).body.path("status").asText()).isEqualTo("OPEN");
            }
        }
    }
    @Test void namesAreHistoricalAndCurrentMetricsDoNotChangeAlertVersion() throws Exception {
        threshold("20");var alert=only();long version=items.findById(ingredient.getId()).orElseThrow().getVersion();
        call(patch("/api/v1/menu/ingredients/"+ingredient.getId()),Map.of("expectedVersion",version,"name","New name","sku","NEW"),ALL).expect(200);
        call(patch(WAREHOUSES+"/"+warehouse),Map.of("expectedVersion",0,"name","New warehouse"),ALL).expect(200);
        movement("RECEIPT","5",null).expect(200);var current=detail(alert).expect(200);
        assertThat(current.body.path("ingredientName").asText()).isEqualTo("Thịt bò");assertThat(current.body.path("warehouseName").asText()).isEqualTo("Kho chính");
        assertThat(current.body.path("currentQuantity").decimalValue()).isEqualByComparingTo("5");assertThat(current.version()).isEqualTo(alert.version());
    }
    @Test void packageGatesOnlyApiAndNotInternalMaintenance() throws Exception {
        features(Set.of("INVENTORY_MANAGEMENT"));threshold("20").expect(200);assertThat(count("low_stock_alerts")).isEqualTo(1);
        list().error(403,"FEATURE_NOT_ENTITLED");features(Set.of("INVENTORY_MANAGEMENT","LOW_STOCK_ALERT"));only();
        features(Set.of("LOW_STOCK_ALERT"));list().error(403,"FEATURE_NOT_ENTITLED");
    }
    @Test void securityValidatesBothPermissionsTenantAccountAndSubscription() throws Exception {
        threshold("20");var alert=only();call(get(ALERTS),null,Set.of()).expect(403);
        call(post(ALERTS+"/"+alert.id()+"/acknowledge"),Map.of("expectedVersion",0),Set.of("INVENTORY_VIEW")).expect(403);
        call(post(ALERTS+"/"+alert.id()+"/acknowledge"),Map.of("expectedVersion",0),Set.of("INVENTORY_ALERT_ACKNOWLEDGE")).expect(403);
        assertThat(mvc.perform(get(ALERTS)).andReturn().getResponse().getStatus()).isEqualTo(401);
        User original=user;setup();call(get(ALERTS+"/"+alert.id()),null,ALL).error(404,"LOW_STOCK_ALERT_NOT_FOUND");
        ack(alert,0).error(404,"LOW_STOCK_ALERT_NOT_FOUND");assertThat(list().body.path("totalElements").asInt()).isZero();
        user.setActive(false);users.saveAndFlush(user);list().expect(401);
        user=original;
    }
    @Test void filtersValidationReadOnlyAndDatabaseUniqueness() throws Exception {
        threshold("20");var alert=only();long auditsBefore=count("audit_logs");
        assertThat(call(get(ALERTS).param("warehouseId",warehouse.toString()).param("ingredientId",ingredient.getId().toString())
            .param("status","OPEN").param("q","_%").param("triggeredFrom",NOW.toString()).param("triggeredTo",NOW.plusSeconds(1).toString()),null,ALL).expect(200).body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get(ALERTS).param("triggeredTo",NOW.toString()),null,ALL).body.path("totalElements").asInt()).isZero();
        for(String[] query:List.of(new String[]{"page","-1"},new String[]{"size","101"},new String[]{"sortBy","id;drop table"},new String[]{"status","BAD"},new String[]{"direction","BAD"},new String[]{"warehouseId","bad"}))
            call(get(ALERTS).param(query[0],query[1]),null,ALL).expect(400);
        call(get(ALERTS).param("triggeredFrom",NOW.toString()).param("triggeredTo",NOW.toString()),null,ALL).expect(400);
        call(post(ALERTS+"/"+alert.id()+"/acknowledge"),Map.of(),ALL).expect(400);
        call(post(ALERTS+"/"+alert.id()+"/acknowledge"),Map.of("expectedVersion",-1),ALL).expect(400);
        detail(alert).expect(200);assertThat(count("audit_logs")).isEqualTo(auditsBefore);assertThat(detail(alert).version()).isZero();
        assertThatThrownBy(()->jdbc.update("INSERT INTO low_stock_alerts SELECT gen_random_uuid(),restaurant_id,warehouse_id,item_id,warehouse_code,warehouse_name,ingredient_sku,ingredient_name,unit,quantity_at_trigger,threshold_quantity,status,triggered_at,acknowledged_by,acknowledged_at,resolved_at,resolution_reason,version,created_at,updated_at FROM low_stock_alerts WHERE id=?",alert.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE low_stock_alerts SET quantity_at_trigger=1 WHERE id=?",alert.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("DELETE FROM low_stock_alerts WHERE id=?",alert.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void stockAndThresholdRollbackTogetherWithAlertAndAudit() throws Exception {
        movement("RECEIPT","100",null).expect(200);threshold("20");var draft=draft("ISSUE","80",null);
        doAnswer(invocation->{invocation.callRealMethod();throw new IllegalStateException("forced alert audit failure");}).when(audits).recordAll(anyList());
        confirm(draft).expect(500);reset(audits);assertThat(count("low_stock_alerts")).isZero();
        assertThat(jdbc.queryForObject("SELECT quantity FROM inventory_balances WHERE warehouse_id=? AND item_id=?",BigDecimal.class,warehouse,ingredient.getId())).isEqualByComparingTo("100");
        confirm(draft).expect(200);assertThat(count("low_stock_alerts")).isEqualTo(1);
        var alert=only();long version=alert.version();
        doThrow(new IllegalStateException("forced alert audit failure")).when(audits).recordAll(anyList());threshold("0").expect(500);reset(audits);
        assertThat(detail(alert).version()).isEqualTo(version);assertThat(detail(alert).body.path("status").asText()).isEqualTo("OPEN");
    }
    @Test void batchWarehouseReconciliationIsBoundedAndDefaultSwitchDoesNotMoveAlerts() throws Exception {
        threshold("20");var alert=only();var other=call(post(WAREHOUSES),Map.of("code","OTHER","name","Other"),ALL).expect(201);
        call(post(WAREHOUSES+"/"+other.id()+"/make-default"),Map.of("expectedVersion",0),ALL).expect(200);
        assertThat(detail(alert).body.path("warehouseId").asText()).isEqualTo(warehouse.toString());
        clearInvocations(projection,alertRepository);
        long version=jdbc.queryForObject("SELECT version FROM warehouses WHERE id=?",Long.class,warehouse);
        call(patch(WAREHOUSES+"/"+warehouse+"/status"),Map.of("expectedVersion",version,"active",false),ALL).expect(200);
        verify(projection,times(1)).find(eq(user.getRestaurantId()),anyCollection(),anyCollection());
        verify(alertRepository,times(1)).findUnresolvedForUpdate(eq(user.getRestaurantId()),anyCollection(),anyCollection());
    }

    void features(Set<String> codes) {subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1,"TEST",codes.stream().map(c->new SubscriptionFeatureSnapshot.FeatureGrant(c,Map.of())).toList(),NOW).toMap());subscription=subscriptions.saveAndFlush(subscription);}
    Reply threshold(String min)throws Exception {
        Long version=jdbc.query("SELECT version FROM inventory_balances WHERE restaurant_id=? AND warehouse_id=? AND item_id=?",rs->rs.next()?rs.getLong(1):null,user.getRestaurantId(),warehouse,ingredient.getId());
        return call(patch(WAREHOUSES+"/"+warehouse+"/balances/"+ingredient.getId()+"/threshold"),json.readTree("{\"expectedVersion\":"+version+",\"minQuantity\":"+min+"}"),ALL);
    }
    Reply draft(String type,String quantity,String direction)throws Exception {
        var line=new HashMap<String,Object>();line.put("ingredientId",ingredient.getId());line.put("quantity",new BigDecimal(quantity));line.put("unitCost",BigDecimal.TEN);if(direction!=null)line.put("adjustmentDirection",direction);
        return call(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType",type,"warehouseId",warehouse,"items",List.of(line)),ALL).expect(201);
    }
    Reply confirm(Reply draft)throws Exception {return call(post("/api/v1/inventory/stock-documents/"+draft.id()+"/confirm"),Map.of("expectedVersion",draft.version()),ALL);}
    Reply movement(String type,String quantity,String direction)throws Exception {return confirm(draft(type,quantity,direction));}
    Reply list()throws Exception{return call(get(ALERTS),null,ALL);}
    Reply only()throws Exception{var page=list().expect(200);assertThat(page.body.path("totalElements").asInt()).isEqualTo(1);return new Reply(200,page.body.at("/content/0"));}
    Reply detail(Reply alert)throws Exception{return call(get(ALERTS+"/"+alert.id()),null,ALL);}
    Reply ack(Reply alert,long version)throws Exception{return call(post(ALERTS+"/"+alert.id()+"/acknowledge"),Map.of("expectedVersion",version),ALL);}
    long count(String table){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE restaurant_id=?",Long.class,user.getRestaurantId());}
    long events(String code){return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE restaurant_id=? AND action_code=?",Long.class,user.getRestaurantId(),code);}
    Reply call(MockHttpServletRequestBuilder request,Object body,Set<String> permissions)throws Exception {
        if(body!=null)request.content(json.writeValueAsString(body));
        var actor=new CurrentUser(user.getId(),user.getRestaurantId(),user.getEmail(),"OWNER",permissions);
        var auth=new UsernamePasswordAuthenticationToken(actor,null,permissions.stream().map(SimpleGrantedAuthority::new).toList());
        var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON)
            .requestAttr(com.possaas.infrastructure.security.JwtAuthenticationFilter.JWT_AUTHENTICATED,true)
            .with(authentication(auth))).andReturn().getResponse();
        return new Reply(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()));
    }
    List<Reply> race(Callable<Reply> one,Callable<Reply> two)throws Exception {
        var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {var a=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return one.call();});var b=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return two.call();});assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}
    }
    record Reply(int status,JsonNode body) {UUID id(){return UUID.fromString(body.path("id").asText());}long version(){return body.path("version").asLong();}Reply expect(int expected){assertThat(status).as(body.toString()).isEqualTo(expected);return this;}void error(int status,String code){expect(status);assertThat(body.path("code").asText()).isEqualTo(code);}}
}
