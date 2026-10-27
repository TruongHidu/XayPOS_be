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
import com.possaas.modules.inventory.service.StockPostingService;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(StockAuditIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StockAuditIntegrationTest {
    static final String SCHEMA="it_stock_audit_"+UUID.randomUUID().toString().replace("-","");
    static final String URL="/api/v1/inventory/audits",DOCS="/api/v1/inventory/stock-documents",WH="/api/v1/inventory/warehouses";
    static final Instant NOW=Instant.parse("2026-10-21T08:00:00Z");
    static final Set<String> ALL=Set.of("INVENTORY_VIEW","STOCK_AUDIT_MANAGE","STOCK_AUDIT_CONFIRM","WAREHOUSE_CREATE",
        "WAREHOUSE_UPDATE","WAREHOUSE_DELETE","INVENTORY_THRESHOLD_UPDATE","INVENTORY_RECEIVE","INVENTORY_ISSUE",
        "INVENTORY_ADJUST","INGREDIENT_UPDATE","INGREDIENT_DELETE");
    @TestConfiguration static class FixedTime {@Bean @Primary Clock auditClock(){return Clock.fixed(NOW,ZoneOffset.UTC);}}
    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p){
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA):base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url);p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;@Autowired UserRepository users;@Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages;@Autowired RestaurantSubscriptionRepository subscriptions;@Autowired ItemRepository items;
    @MockitoSpyBean AuditService logs;@MockitoSpyBean StockPostingService posting;
    @MockitoSpyBean InventoryTransactionWriteRepository ledger;@MockitoSpyBean LowStockAlertRepository alerts;
    @MockitoSpyBean StockAuditMovementQuery movements;
    User user;Item ingredient;UUID warehouse;RestaurantSubscription subscription;

    @BeforeEach void setup()throws Exception {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
        var restaurant=new Restaurant();restaurant.setCode("AUDIT"+UUID.randomUUID().toString().replace("-",""));restaurant.setName("Stocktaking");restaurant=restaurants.saveAndFlush(restaurant);
        user=new User();user.setRestaurantId(restaurant.getId());user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());user.setName("Owner");user.setEmail(UUID.randomUUID()+"@audit.test");user.setPasswordHash("fixture");user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription();subscription.setRestaurantId(user.getRestaurantId());subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());subscription.setStatus(SubscriptionStatus.ACTIVE);subscription.setStartAt(NOW.minusSeconds(60));subscription.setEndAt(NOW.plusSeconds(7200));subscription.setPriceAmount(BigDecimal.ZERO);subscription.setCurrencyCode("VND");features(Set.of("INVENTORY_MANAGEMENT","STOCK_AUDIT","LOW_STOCK_ALERT"));
        ingredient=ingredient("Thịt bò","BEEF_%");warehouse=call(post(WH),Map.of("code","MAIN","name","Kho chính"),ALL).expect(201).id();
    }
    @AfterEach void resetFailures(){reset(logs,org.springframework.test.util.AopTestUtils.getUltimateTargetObject(posting),ledger,alerts,movements);}
    @AfterAll void cleanup(){if(!SCHEMA.matches("it_stock_audit_[0-9a-f]{32}"))throw new IllegalStateException();jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}

    @Test void createIsHeaderOnlyIdempotentAndWarehouseIsImmutable()throws Exception {
        String key=UUID.randomUUID().toString();var body=Map.of("warehouseId",warehouse,"note","note");
        var first=call(post(URL).header("Idempotency-Key",key),body,ALL).expect(201);
        assertThat(first.body.path("status").asText()).isEqualTo("DRAFT");assertThat(first.version()).isZero();
        assertThat(call(post(URL).header("Idempotency-Key",key),body,ALL).expect(201).id()).isEqualTo(first.id());
        call(post(URL).header("Idempotency-Key",key),Map.of("warehouseId",warehouse,"note","different"),ALL).error(409,"IDEMPOTENCY_KEY_REUSED");
        for(String table:List.of("stock_audit_items","inventory_balances","stock_documents","inventory_transactions"))assertThat(count(table)).as(table).isZero();
        assertThat(events("STOCK_AUDIT_CREATED")).isEqualTo(1);
        call(patch(URL+"/"+first.id()),Map.of("expectedVersion",0,"warehouseId",UUID.randomUUID()),ALL).expect(400);
        call(post(URL),body,ALL).error(400,"IDEMPOTENCY_KEY_REQUIRED");
    }
    @Test void snapshotUsesStartTimeIncludesInactiveLogicalZeroAndFreezesScope()throws Exception {
        var draft=create();movement("RECEIPT","10",null);ingredient.setActive(false);ingredient=items.saveAndFlush(ingredient);
        var absent=ingredient("Zero","ZERO");var audit=start(draft).expect(200);var sheet=sheet(audit);
        assertThat(sheet.body.path("totalElements").asInt()).isEqualTo(2);assertThat(count("inventory_balances")).isEqualTo(1);
        var beef=row(audit,ingredient.getId());assertThat(beef.path("systemQuantity").decimalValue()).isEqualByComparingTo("10");
        assertThat(row(audit,absent.getId()).path("systemQuantity").decimalValue()).isZero();assertThat(beef.path("countedQuantity").isNull()).isTrue();
        ingredient("New after start","NEW");var counted=countLine(audit,beef,new BigDecimal("8")).expect(200);
        var replay=start(draft).expect(200);assertThat(replay.version()).isEqualTo(counted.version());assertThat(replay.body.path("itemCount").asInt()).isEqualTo(2);
        assertThat(row(audit,ingredient.getId()).path("countedQuantity").decimalValue()).isEqualByComparingTo("8");assertThat(events("STOCK_AUDIT_STARTED")).isEqualTo(1);
    }
    @Test void emptyScopeRollsBackStart()throws Exception {
        ingredient.setTrackInventory(false);items.saveAndFlush(ingredient);var draft=create();start(draft).error(409,"STOCK_AUDIT_EMPTY");
        assertThat(detail(draft).body.path("status").asText()).isEqualTo("DRAFT");assertThat(detail(draft).version()).isZero();assertThat(events("STOCK_AUDIT_STARTED")).isZero();
    }
    @Test void concurrentStartsAndDatabasePartialUniqueEnforceOneCountingPerWarehouse()throws Exception {
        var first=create();var second=create();var results=race(()->start(first),()->start(second));
        assertThat(results.stream().filter(r->r.status==200).count()).isEqualTo(1);assertThat(results.stream().filter(r->r.status==409).count()).isEqualTo(1);
        var loser=detail(first).body.path("status").asText().equals("DRAFT")?first:second;
        assertThatThrownBy(()->jdbc.update("update stock_audits set status='COUNTING',started_by=?,started_at=? where id=?",user.getId(),java.sql.Timestamp.from(NOW),loser.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("ux_stock_audit_counting");
        UUID main=warehouse;warehouse=call(post(WH),Map.of("code","SECOND","name","Second"),ALL).expect(201).id();start(create()).expect(200);warehouse=main;
    }
    @Test void batchIsAtomicAndVersionedOnceAndNoopDoesNotWrite()throws Exception {
        var zero=ingredient("Zero","ZERO");var audit=start(create()).expect(200);var a=row(audit,ingredient.getId());var b=row(audit,zero.getId());
        var batch=Map.of("expectedVersion",audit.version(),"items",List.of(line(a,"0"),line(b,"3.125")));
        var updated=call(patch(URL+"/"+audit.id()+"/items"),batch,ALL).expect(200);assertThat(updated.version()).isEqualTo(audit.version()+1);
        assertThat(row(audit,ingredient.getId()).path("differenceQuantity").decimalValue()).isZero();assertThat(events("STOCK_AUDIT_COUNTS_UPDATED")).isEqualTo(1);
        call(patch(URL+"/"+audit.id()+"/items"),batch,ALL).error(409,"CONCURRENT_STOCK_AUDIT_UPDATE");
        var noop=call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",updated.version(),"items",List.of(line(a,"0.000"),line(b,"3.125"))),ALL).expect(200);
        assertThat(noop.version()).isEqualTo(updated.version());assertThat(events("STOCK_AUDIT_COUNTS_UPDATED")).isEqualTo(1);
        call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",updated.version(),"items",List.of(line(a,"4"),line(a,"5"))),ALL).expect(400);
        call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",updated.version(),"items",List.of(line(a,"4"),Map.of("auditItemId",UUID.randomUUID(),"countedQuantity",5))),ALL).error(404,"STOCK_AUDIT_ITEM_NOT_FOUND");
        assertThat(row(audit,ingredient.getId()).path("countedQuantity").decimalValue()).isZero();
        var race=race(()->countLine(updated,a,new BigDecimal("2")),()->countLine(updated,b,new BigDecimal("2")));
        assertThat(race.stream().filter(r->r.status==200).count()).isEqualTo(1);assertThat(race.stream().filter(r->r.status==409).count()).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"null","-1","0.0001","100000000000"})
    void invalidCountsNeverModifySheet(String count)throws Exception {
        var audit=start(create()).expect(200);var row=row(audit,ingredient.getId());
        call(patch(URL+"/"+audit.id()+"/items"),json.readTree("{\"expectedVersion\":"+audit.version()+",\"items\":[{\"auditItemId\":\""+row.path("id").asText()+"\",\"countedQuantity\":"+count+"}]}"),ALL).expect(400);
        assertThat(detail(audit).version()).isEqualTo(audit.version());
    }
    @Test void incompleteThenAllZeroConfirmsWithoutCreatingStockArtifacts()throws Exception {
        var audit=start(create()).expect(200);confirm(audit).error(409,"STOCK_AUDIT_INCOMPLETE");audit=countAll(audit,"0");
        var confirmed=confirm(audit).expect(200);assertThat(confirmed.body.path("adjustmentDocumentId").isNull()).isTrue();
        assertThat(confirm(audit).expect(200).body).isEqualTo(confirmed.body);assertThat(events("STOCK_AUDIT_CONFIRMED")).isEqualTo(1);
        for(String table:List.of("stock_documents","inventory_balances","inventory_transactions"))assertThat(count(table)).isZero();
    }
    @Test void mixedDeltaGeneratesOneImmutableAdjustmentWithSnapshotCostAndLowStockAlerts()throws Exception {
        movement("RECEIPT","10",null);var added=ingredient("Extra","EXTRA");var same=ingredient("Same","SAME");threshold("9");
        var audit=start(create()).expect(200);var beef=row(audit,ingredient.getId());
        audit=call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",audit.version(),"items",List.of(line(beef,"8"),line(row(audit,added.getId()),"2.125"),line(row(audit,same.getId()),"0"))),ALL).expect(200);
        ingredient=items.findById(ingredient.getId()).orElseThrow();ingredient.setCostPrice(new BigDecimal("99"));ingredient.setCostConfigured(true);ingredient.setName("Changed");ingredient.setSku("CHANGED");items.saveAndFlush(ingredient);
        var confirmed=confirm(audit).expect(200);UUID document=UUID.fromString(confirmed.body.path("adjustmentDocumentId").asText());
        assertThat(count("stock_documents")).isEqualTo(2);assertThat(count("inventory_transactions")).isEqualTo(3);assertThat(count("low_stock_alerts")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select quantity from inventory_balances where warehouse_id=? and item_id=?",BigDecimal.class,warehouse,ingredient.getId())).isEqualByComparingTo("8");
        var doc=call(get(DOCS+"/"+document),null,ALL).expect(200);assertThat(doc.body.path("stockAuditId").asText()).isEqualTo(audit.id().toString());assertThat(doc.body.path("lineCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select unit_cost from stock_document_items where stock_document_id=? and ingredient_id=?",BigDecimal.class,document,ingredient.getId())).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select ingredient_name from stock_document_items where stock_document_id=? and ingredient_id=?",String.class,document,ingredient.getId())).isEqualTo("Thịt bò");
        assertThat(items.findById(ingredient.getId()).orElseThrow().getCostPrice()).isEqualByComparingTo("99");
        assertThat(confirm(audit).expect(200).body).isEqualTo(confirmed.body);assertThat(events("STOCK_AUDIT_CONFIRMED")).isEqualTo(1);
        assertThat(jdbc.queryForList("select transaction_type from inventory_transactions where stock_document_id=?",String.class,document)).containsExactlyInAnyOrder("ADJUSTMENT_INCREASE","ADJUSTMENT_DECREASE");
        assertThat(jdbc.queryForList("select distinct source_type from inventory_transactions where stock_document_id=?",String.class,document)).containsExactly("STOCK_DOCUMENT");
        call(post(DOCS+"/"+document+"/confirm"),Map.of("expectedVersion",0),ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        call(post(DOCS+"/"+document+"/cancel"),Map.of("expectedVersion",0),ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        call(patch(DOCS+"/"+document),Map.of("expectedVersion",0,"note","bad"),ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        var docLine=doc.body.at("/items/0/id").asText();
        call(post(DOCS+"/"+document+"/items"),Map.of("expectedVersion",0,"ingredientId",same.getId(),"quantity",1),ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        call(patch(DOCS+"/"+document+"/items/"+docLine),Map.of("expectedVersion",0,"quantity",1),ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        call(delete(DOCS+"/"+document+"/items/"+docLine).param("expectedVersion","0"),null,ALL).error(409,"STOCK_DOCUMENT_MANAGED_BY_AUDIT");
        assertThatThrownBy(()->jdbc.update("update stock_documents set stock_audit_id=null where id=?",document)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update stock_document_items set quantity=3 where stock_document_id=?",document)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        UUID sourceAudit=audit.id();assertThatThrownBy(()->jdbc.update("update stock_audit_items set system_quantity=9 where stock_audit_id=?",sourceAudit)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void increasedAuditResolvesExistingLowStockInSamePosting()throws Exception {
        threshold("5");assertThat(count("low_stock_alerts")).isEqualTo(1);var audit=countAll(start(create()).expect(200),"6");confirm(audit).expect(200);
        assertThat(jdbc.queryForObject("select status from low_stock_alerts where restaurant_id=?",String.class,user.getRestaurantId())).isEqualTo("RESOLVED");
    }
    @ParameterizedTest @ValueSource(strings={"RECEIPT","ISSUE","ADJUSTMENT","NET_ZERO"})
    void everyPhysicalMovementMakesSnapshotStaleIncludingNetZero(String type)throws Exception {
        movement("RECEIPT","10",null);var audit=countAll(start(create()).expect(200),"8");
        if(type.equals("NET_ZERO")){movement("RECEIPT","5",null);movement("ISSUE","5",null);}else movement(type,"1",type.equals("ADJUSTMENT")?"INCREASE":null);
        long before=count("stock_documents"),auditBefore=count("audit_logs");confirm(audit).error(409,"STOCK_AUDIT_SNAPSHOT_STALE");
        assertThat(count("stock_documents")).isEqualTo(before);assertThat(count("audit_logs")).isEqualTo(auditBefore);assertThat(detail(audit).body.path("status").asText()).isEqualTo("COUNTING");
        assertThat(row(audit,ingredient.getId()).path("countedQuantity").decimalValue()).isEqualByComparingTo("8");
    }
    @ParameterizedTest @ValueSource(strings={"tracking","unit","delete"})
    void sourceEligibilityOrUnitChangesAreStaleButSnapshotRemainsReadable(String action)throws Exception {
        var audit=countAll(start(create()).expect(200),"0");long version=items.findById(ingredient.getId()).orElseThrow().getVersion();String item="/api/v1/menu/ingredients/"+ingredient.getId();
        if(action.equals("delete"))call(delete(item).param("expectedVersion",String.valueOf(version)),null,ALL).expect(204);
        else call(patch(item),Map.of("expectedVersion",version,action.equals("unit")?"baseUnit":"trackInventory",action.equals("unit")?"kg":false),ALL).expect(200);
        confirm(audit).error(409,"STOCK_AUDIT_SNAPSHOT_STALE");assertThat(row(audit,ingredient.getId()).path("unit").asText()).isEqualTo("g");
    }
    @Test void thresholdAndCostChangesAndOutsideScopeMovementsDoNotMakeStale()throws Exception {
        movement("RECEIPT","10",null);var audit=countAll(start(create()).expect(200),"10");threshold("5");
        var later=ingredient("Later","LATER");var original=ingredient;ingredient=later;movement("RECEIPT","4",null);ingredient=original;
        ingredient=items.findById(ingredient.getId()).orElseThrow();ingredient.setCostPrice(BigDecimal.ZERO);ingredient.setCostConfigured(true);ingredient.setName("Renamed");items.saveAndFlush(ingredient);
        confirm(audit).expect(200);assertThat(row(audit,ingredient.getId()).path("referenceUnitCostSnapshot").decimalValue()).isEqualByComparingTo("10");
    }
    @Test void concurrentConfirmsProduceOneDocumentLedgerAndAudit()throws Exception {
        var audit=countAll(start(create()).expect(200),"2");var result=race(()->confirm(audit),()->confirm(audit));result.forEach(r->r.expect(200));
        assertThat(result.get(0).body).isEqualTo(result.get(1).body);assertThat(count("stock_documents")).isEqualTo(1);assertThat(count("inventory_transactions")).isEqualTo(1);assertThat(events("STOCK_AUDIT_CONFIRMED")).isEqualTo(1);
    }
    @ParameterizedTest @ValueSource(strings={"DRAFT","COUNTING"})
    void cancellationPreservesHistoryAndIsIdempotent(String state)throws Exception {
        var audit=create();if(state.equals("COUNTING"))audit=countAll(start(audit).expect(200),"2");var before=sheet(audit).body;long stock=count("inventory_balances");
        var cancelled=cancel(audit).expect(200);assertThat(cancel(audit).expect(200).body).isEqualTo(cancelled.body);assertThat(sheet(audit).body).isEqualTo(before);assertThat(count("inventory_balances")).isEqualTo(stock);assertThat(events("STOCK_AUDIT_CANCELLED")).isEqualTo(1);
        start(cancelled).error(409,"INVALID_STOCK_AUDIT_TRANSITION");confirm(cancelled).error(409,"INVALID_STOCK_AUDIT_TRANSITION");
    }
    @ParameterizedTest @ValueSource(strings={"inactive","deleted"})
    void warehouseChangesRejectConfirmButAllowHistoricalReadAndCancel(String mode)throws Exception {
        UUID main=warehouse;warehouse=call(post(WH),Map.of("code","OTHER","name","Other"),ALL).expect(201).id();var audit=countAll(start(create()).expect(200),"0");
        if(mode.equals("deleted"))call(delete(WH+"/"+warehouse).param("expectedVersion","0"),null,ALL).expect(204);
        else call(patch(WH+"/"+warehouse+"/status"),Map.of("expectedVersion",0,"active",false),ALL).expect(200);
        detail(audit).expect(200);sheet(audit).expect(200);confirm(audit).error(409,"WAREHOUSE_INACTIVE");cancel(audit).expect(200);warehouse=main;
    }
    @Test void confirmedIsTerminalAndScopeCannotBeAddedOrDeleted()throws Exception {
        var audit=countAll(start(create()).expect(200),"0");var row=row(audit,ingredient.getId());
        assertThatThrownBy(()->jdbc.update("delete from stock_audit_items where id=?",UUID.fromString(row.path("id").asText()))).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("insert into stock_audit_items(id,restaurant_id,stock_audit_id,ingredient_id,ingredient_name_snapshot,unit_snapshot,system_quantity,reference_unit_cost_snapshot,movement_count_snapshot,created_at,updated_at) select gen_random_uuid(),restaurant_id,stock_audit_id,ingredient_id,ingredient_name_snapshot,unit_snapshot,system_quantity,reference_unit_cost_snapshot,movement_count_snapshot,created_at,updated_at from stock_audit_items where stock_audit_id=?",audit.id())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class).hasMessageContaining("scope is frozen");
        var confirmed=confirm(audit).expect(200);cancel(confirmed).error(409,"INVALID_STOCK_AUDIT_TRANSITION");
        call(patch(URL+"/"+audit.id()),Map.of("expectedVersion",confirmed.version(),"note","no"),ALL).error(409,"INVALID_STOCK_AUDIT_TRANSITION");
        countLine(confirmed,row,BigDecimal.ONE).error(409,"INVALID_STOCK_AUDIT_TRANSITION");
    }
    @ParameterizedTest @ValueSource(strings={"posting","ledger","alert","audit"})
    void downstreamFailureRollsBackAllGeneratedArtifactsAndCanRetry(String failure)throws Exception {
        movement("RECEIPT","10",null);threshold("9");var audit=countAll(start(create()).expect(200),"8");
        long docBefore=count("stock_documents"),ledgerBefore=count("inventory_transactions"),logsBefore=count("audit_logs");
        switch(failure){
            case "posting" -> doAnswer(i->{i.callRealMethod();throw new IllegalStateException("forced posting failure");}).when(org.springframework.test.util.AopTestUtils.<StockPostingService>getUltimateTargetObject(posting)).post(any(),any(),anyList(),anyString());
            case "ledger" -> doAnswer(i->{i.callRealMethod();throw new IllegalStateException("forced ledger failure");}).when(ledger).append(any());
            case "alert" -> doAnswer(i->{i.callRealMethod();throw new IllegalStateException("forced alert failure");}).when(alerts).saveAllAndFlush(anyList());
            default -> doAnswer(i->{i.callRealMethod();if("STOCK_AUDIT_CONFIRMED".equals(i.getArgument(2)))throw new IllegalStateException("forced audit failure");return null;}).when(logs).record(any(),any(),anyString(),anyString(),any(),any(),any(),anyString());
        }
        confirm(audit).expect(500);resetFailures();
        assertThat(count("stock_documents")).isEqualTo(docBefore);assertThat(count("inventory_transactions")).isEqualTo(ledgerBefore);assertThat(count("audit_logs")).isEqualTo(logsBefore);assertThat(count("low_stock_alerts")).isZero();
        assertThat(detail(audit).body.path("status").asText()).isEqualTo("COUNTING");assertThat(jdbc.queryForObject("select quantity from inventory_balances where warehouse_id=? and item_id=?",BigDecimal.class,warehouse,ingredient.getId())).isEqualByComparingTo("10");confirm(audit).expect(200);
    }
    @Test void moreThanOneHundredDifferencesUseOneDocumentAndBatchQueries()throws Exception {
        List<Item> added=new ArrayList<>();for(int n=0;n<104;n++)added.add(ingredient("Ingredient "+n,"SKU"+n));
        clearInvocations(movements);var audit=start(create()).expect(200);assertThat(audit.body.path("itemCount").asInt()).isEqualTo(105);verify(movements,times(1)).counts(eq(user.getRestaurantId()),eq(warehouse),anyCollection());
        List<JsonNode> sheet=new ArrayList<>();for(int page=0;page<2;page++)call(get(URL+"/"+audit.id()+"/items").param("size","100").param("page",String.valueOf(page)),null,ALL).expect(200).body.path("content").forEach(sheet::add);
        for(int from=0;from<sheet.size();from+=100)audit=call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",audit.version(),"items",sheet.subList(from,Math.min(from+100,sheet.size())).stream().map(r->line(r,"1")).toList()),ALL).expect(200);
        clearInvocations(movements);confirm(audit).expect(200);verify(movements,times(1)).counts(eq(user.getRestaurantId()),eq(warehouse),anyCollection());
        assertThat(count("stock_documents")).isEqualTo(1);assertThat(count("stock_document_items")).isEqualTo(105);assertThat(count("inventory_transactions")).isEqualTo(105);
    }
    @Test void overflowFailsBeforeWritingDocumentAndCostZeroIsValid()throws Exception {
        ingredient.setCostPrice(new BigDecimal("999999999999.99"));ingredient.setCostConfigured(true);items.saveAndFlush(ingredient);var audit=countAll(start(create()).expect(200),"99999999999.999");confirm(audit).error(409,"STOCK_AUDIT_ADJUSTMENT_OUT_OF_RANGE");assertThat(count("stock_documents")).isZero();
        cancel(audit).expect(200);ingredient=items.findById(ingredient.getId()).orElseThrow();ingredient.setCostPrice(BigDecimal.ZERO);ingredient.setCostConfigured(true);items.saveAndFlush(ingredient);confirm(countAll(start(create()).expect(200),"0.001")).expect(200);
        assertThat(jdbc.queryForObject("select total_cost from stock_documents where restaurant_id=?",BigDecimal.class,user.getRestaurantId())).isZero();
    }
    @Test void reservedNamespaceAndServerOwnedSourceAreRejectedOnPublicCreate()throws Exception {
        for(String key:List.of("SYS_STOCK_AUDIT:anything"," sys_stock_audit:anything "))call(post(DOCS).header("Idempotency-Key",key),Map.of("documentType","ADJUSTMENT","warehouseId",warehouse),ALL).error(400,"RESERVED_IDEMPOTENCY_NAMESPACE");
        call(post(DOCS).header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType","ADJUSTMENT","warehouseId",warehouse,"stockAuditId",UUID.randomUUID()),ALL).expect(400);
        call(post(URL).header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("warehouseId",warehouse,"origin","manual"),ALL).expect(400);
    }
    @Test void securityFeaturesAccountsAndCrossTenantAreEnforcedWithoutAdjustmentPermission()throws Exception {
        var audit=countAll(start(create()).expect(200),"1");
        call(post(URL+"/"+audit.id()+"/confirm"),Map.of("expectedVersion",audit.version()),Set.of("INVENTORY_VIEW")).expect(403);
        call(post(URL+"/"+audit.id()+"/confirm"),Map.of("expectedVersion",audit.version()),Set.of("STOCK_AUDIT_CONFIRM")).expect(403);
        call(post(URL),Map.of("warehouseId",warehouse),Set.of("INVENTORY_VIEW")).expect(403);
        call(post(URL),Map.of("warehouseId",warehouse),Set.of("STOCK_AUDIT_MANAGE")).expect(403);
        call(get(URL),null,Set.of()).expect(403);assertThat(mvc.perform(get(URL)).andReturn().getResponse().getStatus()).isEqualTo(401);
        features(Set.of("INVENTORY_MANAGEMENT"));detail(audit).error(403,"FEATURE_NOT_ENTITLED");features(Set.of("STOCK_AUDIT"));detail(audit).error(403,"FEATURE_NOT_ENTITLED");features(Set.of("INVENTORY_MANAGEMENT","STOCK_AUDIT"));
        call(post(URL+"/"+audit.id()+"/confirm"),Map.of("expectedVersion",audit.version()),Set.of("INVENTORY_VIEW","STOCK_AUDIT_CONFIRM")).expect(200);
        setup();detail(audit).error(404,"STOCK_AUDIT_NOT_FOUND");call(get(URL+"/"+audit.id()+"/items"),null,ALL).error(404,"STOCK_AUDIT_NOT_FOUND");confirm(audit).error(404,"STOCK_AUDIT_NOT_FOUND");cancel(audit).error(404,"STOCK_AUDIT_NOT_FOUND");
        user.setActive(false);users.saveAndFlush(user);call(get(URL),null,ALL).expect(401);
    }
    @Test void subscriptionAndRestaurantStatusStillBlockQueries()throws Exception {
        var audit=create();subscription.setEndAt(NOW.minusSeconds(1));subscription=subscriptions.saveAndFlush(subscription);detail(audit).error(403,"SUBSCRIPTION_NOT_ACTIVE");
        subscription=subscriptions.findById(subscription.getId()).orElseThrow();subscription.setStatus(SubscriptionStatus.ACTIVE);subscription.setEndAt(NOW.plusSeconds(100));subscription=subscriptions.saveAndFlush(subscription);
        var restaurant=restaurants.findById(user.getRestaurantId()).orElseThrow();restaurant.setStatus(com.possaas.modules.restaurant.entity.RestaurantStatus.SUSPENDED);restaurants.saveAndFlush(restaurant);detail(audit).error(403,"RESTAURANT_INACTIVE");
    }
    @Test void pagingLiteralSearchFilteringAndReadsDoNotWrite()throws Exception {
        var draft=create();var audit=start(draft).expect(200);audit=countAll(audit,"0");long before=count("audit_logs");
        assertThat(call(get(URL).param("warehouseId",warehouse.toString()).param("status","COUNTING").param("createdFrom",NOW.toString()).param("createdTo",NOW.plusSeconds(1).toString()).param("size","1"),null,ALL).expect(200).body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get(URL).param("createdTo",NOW.toString()),null,ALL).expect(200).body.path("totalElements").asInt()).isZero();
        assertThat(call(get(URL+"/"+audit.id()+"/items").param("q","_%").param("counted","true"),null,ALL).expect(200).body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get(URL+"/"+audit.id()+"/items").param("differenceOnly","true"),null,ALL).expect(200).body.path("totalElements").asInt()).isZero();
        for(String[] query:List.of(new String[]{"page","-1"},new String[]{"size","101"},new String[]{"sortBy","drop table"},new String[]{"direction","BAD"},new String[]{"status","BAD"}))call(get(URL).param(query[0],query[1]),null,ALL).expect(400);
        call(get(URL).param("createdFrom",NOW.toString()).param("createdTo",NOW.toString()),null,ALL).expect(400);
        assertThat(detail(audit).body.has("items")).isFalse();assertThat(detail(audit).version()).isEqualTo(audit.version());assertThat(count("audit_logs")).isEqualTo(before);
    }

    void features(Set<String> codes){subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(1,"TEST",codes.stream().map(c->new SubscriptionFeatureSnapshot.FeatureGrant(c,Map.of())).toList(),NOW).toMap());subscription=subscriptions.saveAndFlush(subscription);}
    Item ingredient(String name,String sku){var value=new Item();value.initialize(user.getRestaurantId(),NOW);value.setItemType(ItemType.INGREDIENT);value.setName(name);value.setSku(sku);value.setBaseUnit("g");value.setTrackInventory(true);value.setSalePrice(BigDecimal.ZERO);value.setCostPrice(BigDecimal.TEN);value.setCostConfigured(true);return items.saveAndFlush(value);}
    Reply create()throws Exception{return call(post(URL).header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("warehouseId",warehouse),ALL).expect(201);}
    Reply start(Reply audit)throws Exception{return call(post(URL+"/"+audit.id()+"/start"),Map.of("expectedVersion",audit.version()),ALL);}
    Reply confirm(Reply audit)throws Exception{return call(post(URL+"/"+audit.id()+"/confirm"),Map.of("expectedVersion",audit.version()),ALL);}
    Reply cancel(Reply audit)throws Exception{return call(post(URL+"/"+audit.id()+"/cancel"),Map.of("expectedVersion",audit.version()),ALL);}
    Reply detail(Reply audit)throws Exception{return call(get(URL+"/"+audit.id()),null,ALL);}
    Reply sheet(Reply audit)throws Exception{return call(get(URL+"/"+audit.id()+"/items").param("size","100"),null,ALL).expect(200);}
    JsonNode row(Reply audit,UUID ingredient)throws Exception{return java.util.stream.StreamSupport.stream(sheet(audit).body.path("content").spliterator(),false).filter(n->n.path("ingredientId").asText().equals(ingredient.toString())).findFirst().orElseThrow();}
    Map<String,Object> line(JsonNode row,String value){return Map.of("auditItemId",row.path("id").asText(),"countedQuantity",new BigDecimal(value));}
    Reply countLine(Reply audit,JsonNode row,BigDecimal value)throws Exception{return call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",audit.version(),"items",List.of(line(row,value.toPlainString()))),ALL);}
    Reply countAll(Reply audit,String value)throws Exception{List<Map<String,Object>> lines=new ArrayList<>();sheet(audit).body.path("content").forEach(row->lines.add(line(row,value)));return call(patch(URL+"/"+audit.id()+"/items"),Map.of("expectedVersion",audit.version(),"items",lines),ALL).expect(200);}
    Reply movement(String type,String quantity,String direction)throws Exception{var line=new HashMap<String,Object>();line.put("ingredientId",ingredient.getId());line.put("quantity",new BigDecimal(quantity));line.put("unitCost",BigDecimal.TEN);if(direction!=null)line.put("adjustmentDirection",direction);var draft=call(post(DOCS).header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType",type,"warehouseId",warehouse,"items",List.of(line)),ALL).expect(201);return call(post(DOCS+"/"+draft.id()+"/confirm"),Map.of("expectedVersion",draft.version()),ALL).expect(200);}
    void threshold(String min)throws Exception{Long version=jdbc.query("select version from inventory_balances where restaurant_id=? and warehouse_id=? and item_id=?",rs->rs.next()?rs.getLong(1):null,user.getRestaurantId(),warehouse,ingredient.getId());call(patch(WH+"/"+warehouse+"/balances/"+ingredient.getId()+"/threshold"),json.readTree("{\"expectedVersion\":"+version+",\"minQuantity\":"+min+"}"),ALL).expect(200);}
    long count(String table){return jdbc.queryForObject("select count(*) from "+table+" where restaurant_id=?",Long.class,user.getRestaurantId());}
    long events(String action){return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",Long.class,user.getRestaurantId(),action);}
    Reply call(MockHttpServletRequestBuilder request,Object body,Set<String> permissions)throws Exception{if(body!=null)request.content(json.writeValueAsString(body));var actor=new CurrentUser(user.getId(),user.getRestaurantId(),user.getEmail(),"OWNER",permissions);var auth=new UsernamePasswordAuthenticationToken(actor,null,permissions.stream().map(SimpleGrantedAuthority::new).toList());var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).requestAttr(com.possaas.infrastructure.security.JwtAuthenticationFilter.JWT_AUTHENTICATED,true).with(authentication(auth))).andReturn().getResponse();return new Reply(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()));}
    List<Reply> race(Callable<Reply> one,Callable<Reply> two)throws Exception{var ready=new CountDownLatch(2);var go=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return one.call();});var b=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return two.call();});assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();go.countDown();return List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));}}
    record Reply(int status,JsonNode body){UUID id(){return UUID.fromString(body.path("id").asText());}long version(){return body.path("version").asLong();}Reply expect(int expected){assertThat(status).as(body.toString()).isEqualTo(expected);return this;}void error(int status,String code){expect(status);assertThat(body.path("code").asText()).isEqualTo(code);}}
}
