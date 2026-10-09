package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.infrastructure.security.JwtTokenService;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.menu.entity.*;
import com.possaas.modules.menu.repository.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.repository.*;
import com.possaas.modules.order.service.OrderCodeGenerator;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import com.possaas.modules.table.application.port.*;
import com.possaas.modules.table.service.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.system.*;
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

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false","app.bootstrap.super-admin.enabled=false","logging.level.root=WARN","app.cors.allowed-origins=http://localhost:3000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(OrderIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class OrderIntegrationTest {
    private static final String SCHEMA="it_order_"+UUID.randomUUID().toString().replace("-","");
    private static final Instant NOW=Instant.parse("2026-10-08T00:00:00Z");
    private static final String ORDERS="/api/v1/orders";
    private static final Set<String> ALL=Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE","ORDER_CANCEL","TABLE_VIEW","TABLE_CLOSE","TABLE_OPEN");
    @TestConfiguration static class FixedTime { @Bean @Primary Clock orderClock() { return Clock.fixed(NOW,ZoneOffset.UTC); } }
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA):base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        properties.add("spring.datasource.url",()->url); properties.add("spring.flyway.schemas",()->SCHEMA);
        properties.add("spring.flyway.default-schema",()->SCHEMA); properties.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants; @Autowired UserRepository users; @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages; @Autowired RestaurantSubscriptionRepository subscriptions;
    @MockitoSpyBean ItemRepository menuItems; @Autowired ItemGroupRepository groups;
    @Autowired RestaurantTableRepository tables; @Autowired TableAreaRepository areas; @MockitoSpyBean TableSessionRepository sessions;
    @MockitoSpyBean OrderSeatingQueryAdapter seatingQuery;
    @MockitoSpyBean TableLifecyclePolicy tableLifecycle;
    @Autowired OrderSeatingCommand seatingCommand;
    @Autowired PlatformTransactionManager transactions;
    @MockitoSpyBean OrderRepository orders; @Autowired OrderItemRepository lines;
    @MockitoSpyBean com.possaas.modules.order.service.OrderSessionServingPolicy serving;
    @MockitoSpyBean OrderItemSubmissionRepository submissionLedger;
    @MockitoSpyBean OrderItemStatusHistoryRepository histories; @MockitoSpyBean AuditService audit;
    @MockitoSpyBean OrderCodeGenerator codes; @Autowired PermissionService permissions; @Autowired JwtTokenService jwt;
    @Autowired EntityManagerFactory emf;
    private Fixture f;
    private record Fixture(Restaurant restaurant,User user,RestaurantSubscription subscription,ItemGroup group,Item item,RestaurantTable table,TableSession session) {}
    private record Result(int status,JsonNode body,String replay) {
        UUID id() { return UUID.fromString(body.path("id").asText()); }
        long version() { return body.path("version").asLong(); }
        UUID lineId() { return UUID.fromString(body.path("items").get(0).path("id").asText()); }
        void expect(int expected) { assertThat(status).as(body.toString()).isEqualTo(expected); }
        void error(int expected,String code) { expect(expected); assertThat(body.path("code").asText()).isEqualTo(code); }
    }
    @BeforeEach void setup() { assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA); f=fixture(true); }
    @AfterAll void dropOwnSchema() {
        if(!SCHEMA.matches("it_order_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }

    @Test void dineInSnapshotsAuthoritativeFieldsAndHasRealVersionAndHistory() throws Exception {
        var order=create(true); order.expect(201); assertThat(order.version()).isZero();
        assertThat(order.body.path("tableSessionId").asText()).isEqualTo(f.session.getId().toString());
        assertThat(order.body.path("createdBy").asText()).isEqualTo(f.user.getId().toString());
        assertThat(order.body.path("status").asText()).isEqualTo("OPEN"); assertThat(order.body.path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(order.body.path("totalAmount").decimalValue()).isEqualByComparingTo("100000.00");
        assertThat(order.body.toString()).doesNotContain("costPrice","requestHash","idempotencyKey","metadata");
        assertThat(detail(order).body).isEqualTo(order.body); assertThat(history(order.lineId())).hasSize(1);
        assertThat(events("ORDER_CREATED")).isEqualTo(1);
    }
    @Test void takeawayUsesNoTableWhileDineInAllowsOnlyOneServingOrder() throws Exception {
        var first=create(true); first.expect(201); create(true).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        f=fixture(false); var take=create(false); take.expect(201); create(false).expect(201); assertThat(take.body.path("tableSessionId").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where restaurant_id=?",Long.class,f.restaurant.getId())).isZero();
    }
    @Test void serviceTypeAndOpenSessionRulesAreEnforced() throws Exception {
        var missing=body(true); missing.remove("tableSessionId"); postCreate(missing,key()).error(400,"DINE_IN_SESSION_REQUIRED");
        var take=body(false); take.put("tableSessionId",f.session.getId()); postCreate(take,key()).error(400,"TAKEAWAY_SESSION_NOT_ALLOWED");
        for(String status:List.of("CLOSED","CANCELLED")) {
            jdbc.update("update table_sessions set status=?,closed_at=opened_at,closed_by=opened_by,cancel_reason='Fixture' where id=?",status,f.session.getId());
            postCreate(body(true),key()).error(409,"TABLE_SESSION_NOT_OPEN");
        }
    }
    @Test void tableAndAreaEligibilityIsCheckedOnCreation() throws Exception {
        jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",f.table.getId()); postCreate(body(true),key()).error(409,"TABLE_INACTIVE");
        jdbc.update("update restaurant_tables set status='AVAILABLE',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),f.table.getId()); postCreate(body(true),key()).error(404,"TABLE_NOT_FOUND");
        jdbc.update("update restaurant_tables set deleted_at=null where id=?",f.table.getId());
        var area=new TableArea(); area.initialize(f.restaurant.getId(),NOW); area.setName("Area"); area.setActive(false); area=areas.saveAndFlush(area);
        jdbc.update("update restaurant_tables set area_id=? where id=?",area.getId(),f.table.getId()); postCreate(body(true),key()).error(409,"TABLE_AREA_INACTIVE");
    }
    @Test void foreignSessionMenuOrderAndLineAreHidden() throws Exception {
        var own=f; var ownOrder=create(true); f=fixture(true); var foreign=f; var foreignOrder=create(true); f=own;
        var request=body(true); request.put("tableSessionId",foreign.session.getId()); postCreate(request,key()).error(404,"TABLE_SESSION_NOT_FOUND");
        request=body(false); request.put("items",List.of(lineRequest(foreign.item.getId(),"1",null))); postCreate(request,key()).error(404,"MENU_ITEM_NOT_FOUND");
        call(get(path(foreignOrder))).error(404,"ORDER_NOT_FOUND");
        call(patch(path(ownOrder)+"/items/"+foreignOrder.lineId()),Map.of("expectedVersion",ownOrder.version(),"quantity",1)).error(404,"ORDER_ITEM_NOT_FOUND");
    }
    @Test void ingredientInactiveDeletedOutOfStockAndHiddenGroupsAreRejected() throws Exception {
        var item=f.item; item.setActive(false); item=menuItems.saveAndFlush(item); postCreate(body(false),key()).error(409,"ITEM_NOT_SELLABLE");
        item.setActive(true); item.setDeletedAt(NOW); item=menuItems.saveAndFlush(item); postCreate(body(false),key()).error(404,"MENU_ITEM_NOT_FOUND");
        item.setDeletedAt(null); item.setGroupId(null); item.setItemType(ItemType.INGREDIENT); item=menuItems.saveAndFlush(item); postCreate(body(false),key()).error(409,"ITEM_NOT_SELLABLE");
        item.setItemType(ItemType.MENU_ITEM); item.setGroupId(f.group.getId()); item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); item=menuItems.saveAndFlush(item); postCreate(body(false),key()).error(409,"ITEM_OUT_OF_STOCK");
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE); menuItems.saveAndFlush(item);
        var group=f.group; group.setActive(false); group=groups.saveAndFlush(group); postCreate(body(false),key()).error(409,"ITEM_NOT_SELLABLE");
        group.setActive(true); group.setDeletedAt(NOW); groups.saveAndFlush(group); postCreate(body(false),key()).error(409,"ITEM_NOT_SELLABLE");
    }
    @Test void zeroPriceUngroupedItemAndDecimalRoundingAreSupported() throws Exception {
        var item=f.item; item.setGroupId(null); item.setSalePrice(BigDecimal.ZERO); item=menuItems.saveAndFlush(item);
        var order=create(false); order.expect(201); assertThat(order.body.path("totalAmount").decimalValue()).isEqualByComparingTo("0.00");
        item.setSalePrice(new BigDecimal("15.00")); menuItems.saveAndFlush(item);
        var request=body(false); request.put("items",List.of(lineRequest(item.getId(),"0.001",null))); var rounded=postCreate(request,key()); rounded.expect(201);
        assertThat(rounded.body.path("totalAmount").decimalValue()).isEqualByComparingTo("0.02");
    }
    @Test void snapshotsStayImmutableAcrossMenuAndCurrencyChanges() throws Exception {
        var order=create(false); var item=f.item; item.setName("New name"); item.setBaseUnit("new unit"); item.setSalePrice(new BigDecimal("90000")); menuItems.saveAndFlush(item);
        var updated=call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3)); updated.expect(200);
        assertThat(updated.body.path("items").get(0).path("itemName").asText()).isEqualTo("Pho");
        assertThat(updated.body.path("totalAmount").decimalValue()).isEqualByComparingTo("150000.00");
        jdbc.update("update restaurants set currency_code='USD' where id=?",f.restaurant.getId());
        call(post(path(order)+"/items"),Map.of("expectedVersion",updated.version(),"items",List.of(lineRequest(f.item.getId(),"1",null)))).error(409,"ORDER_CURRENCY_MISMATCH");
        assertThat(detail(order).body.path("currencyCode").asText()).isEqualTo("VND");
        jdbc.update("update items set deleted_at=? where id=?",java.sql.Timestamp.from(NOW),f.item.getId());
        assertThat(detail(order).body.path("items").get(0).path("itemName").asText()).isEqualTo("Pho");
    }
    @Test void addingNewLinesUsesNewSnapshotsWithoutMergingOldLines() throws Exception {
        var order=create(false); var item=f.item; item.setSalePrice(new BigDecimal("60000")); menuItems.saveAndFlush(item);
        var added=call(post(path(order)+"/items"),Map.of("expectedVersion",order.version(),"items",List.of(lineRequest(item.getId(),"1","different")))); added.expect(200);
        assertThat(added.body.path("items").size()).isEqualTo(2); assertThat(added.body.path("totalAmount").decimalValue()).isEqualByComparingTo("160000.00");
        assertThat(added.body.path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("50000");
        assertThat(added.body.path("items").get(1).path("unitPrice").decimalValue()).isEqualByComparingTo("60000");
        assertThat(detail(order).body).isEqualTo(added.body);
    }
    @Test void normalizedIdempotencyReplayReturnsCurrentResourceWithoutAuditOrReprice() throws Exception {
        String key=key(); var request=body(true); var created=postCreate(request,key); created.expect(201);
        var patched=call(patch(path(created)),Map.of("expectedVersion",created.version(),"note","updated")); patched.expect(200);
        jdbc.update("update items set is_active=false where id=?",f.item.getId());
        jdbc.update("update table_sessions set status='CANCELLED',closed_at=opened_at,closed_by=opened_by,cancel_reason='Fixture' where id=?",f.session.getId());
        request.put("items",List.of(lineRequest(f.item.getId(),"2.0"," no onions ")));
        var replay=postCreate(request,key); replay.expect(200); assertThat(replay.replay).isEqualTo("true"); assertThat(replay.body).isEqualTo(patched.body);
        assertThat(events("ORDER_CREATED")).isEqualTo(1); assertThat(history(created.lineId())).hasSize(1);
        request.put("note","different request"); postCreate(request,key).error(409,"IDEMPOTENCY_KEY_REUSED");
    }
    @Test void idempotencyActorMismatchDoesNotExposeAnotherStaffResult() throws Exception {
        String key=key(); var request=body(false); postCreate(request,key).expect(201);
        var user=new User(); user.setRestaurantId(f.restaurant.getId()); user.setRoleId(f.user.getRoleId()); user.setName("Other"); user.setEmail(UUID.randomUUID()+"@example.test"); user.setPasswordHash("fixture"); user=users.saveAndFlush(user);
        callAs(post(ORDERS).header("Idempotency-Key",key),request,new CurrentUser(user.getId(),f.restaurant.getId(),user.getEmail(),"OWNER",ALL)).error(409,"IDEMPOTENCY_KEY_REUSED");
    }
    @Test void twoCreatesWithSameKeyProduceOneOrderAuditAndHistory() throws Exception {
        var request=body(false); String key=key(); var results=race(()->postCreate(request,key),()->postCreate(request,key));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(201,200);
        assertThat(results.get(0).id()).isEqualTo(results.get(1).id()); assertThat(events("ORDER_CREATED")).isEqualTo(1);
    }
    @Test void zeroTotalChildNoteUpdateStillBumpsAggregateVersionUnderFixedClock() throws Exception {
        var item=f.item; item.setSalePrice(BigDecimal.ZERO); menuItems.saveAndFlush(item); var order=create(false);
        var updated=call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"note","new note")); updated.expect(200);
        assertThat(updated.version()).isEqualTo(order.version()+1); assertThat(updated.body.path("updatedAt")).isEqualTo(order.body.path("updatedAt"));
        assertThat(detail(order).body).isEqualTo(updated.body);
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3)).error(409,"CONCURRENT_ORDER_UPDATE");
        var noOp=call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",updated.version(),"note","new note")); noOp.expect(200);
        assertThat(noOp.body).isEqualTo(updated.body); assertThat(events("ORDER_ITEM_UPDATED")).isEqualTo(1);
    }
    @Test void twoMutationsUsingSameVersionHaveOnlyOneWinner() throws Exception {
        var order=create(false); var results=race(()->call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3)),
                ()->call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",4)));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200,409); assertThat(events("ORDER_ITEM_UPDATED")).isEqualTo(1);
    }
    @Test void confirmIsIdempotentAndBlocksFurtherEditsWithoutFakeKitchenHistory() throws Exception {
        var order=create(false); var confirmed=call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version())); confirmed.expect(200);
        assertThat(confirmed.body.path("status").asText()).isEqualTo("CONFIRMED"); assertThat(confirmed.body.path("items").get(0).path("sentToKitchenAt").isNull()).isTrue();
        var replay=call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version())); assertThat(replay.body).isEqualTo(confirmed.body);
        call(post(path(order)+"/items"),Map.of("expectedVersion",confirmed.version(),"items",List.of(lineRequest(f.item.getId(),"1",null)))).error(409,"ORDER_NOT_EDITABLE");
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",confirmed.version(),"quantity",3)).error(409,"ORDER_NOT_EDITABLE");
        assertThat(events("ORDER_CONFIRMED")).isEqualTo(1); assertThat(history(order.lineId())).hasSize(1);
    }
    @Test void confirmRechecksAvailabilityButDoesNotReprice() throws Exception {
        var order=create(false); var item=f.item; item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); item=menuItems.saveAndFlush(item);
        call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version())).error(409,"ITEM_OUT_OF_STOCK");
        item.setAvailabilityStatus(AvailabilityStatus.AVAILABLE); item.setSalePrice(new BigDecimal("80000")); menuItems.saveAndFlush(item);
        var confirmed=call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version())); confirmed.expect(200);
        assertThat(confirmed.body.path("totalAmount").decimalValue()).isEqualByComparingTo("100000");
    }
    @Test void draftLineCancellationUsesUpdatePermissionAndCancelsEmptyOrder() throws Exception {
        var order=create(true); var updater=actor(Set.of("ORDER_UPDATE"));
        var cancelled=callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),Map.of("expectedVersion",order.version(),"reason","changed mind"),updater); cancelled.expect(200);
        assertThat(cancelled.body.path("status").asText()).isEqualTo("CANCELLED"); assertThat(cancelled.body.path("totalAmount").decimalValue()).isEqualByComparingTo("0");
        assertThat(cancelled.body.path("items").get(0).path("lineTotal").decimalValue()).isEqualByComparingTo("100000");
        var again=callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),Map.of("expectedVersion",0,"reason","retry"),updater);
        assertThat(again.body).isEqualTo(cancelled.body); assertThat(history(order.lineId())).hasSize(2);
        assertThat(events("ORDER_ITEM_CANCELLED")).isEqualTo(1); assertThat(events("ORDER_CANCELLED")).isEqualTo(1);
    }
    @Test void confirmedLineCancellationRequiresCancelPermissionAndRetainsOrderedHistory() throws Exception {
        var order=create(false); var confirmed=call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version()));
        var request=Map.of("expectedVersion",confirmed.version(),"reason","void");
        callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),request,actor(Set.of("ORDER_UPDATE"))).error(403,"FORBIDDEN");
        var cancelled=callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),request,actor(Set.of("ORDER_CANCEL"))); cancelled.expect(200);
        var entries=history(order.lineId()); assertThat(entries).hasSize(2);
        assertThat(entries.get(1).getChangeSequence()).isGreaterThan(entries.get(0).getChangeSequence());
        assertThat(entries.get(0).getChangedAt()).isEqualTo(entries.get(1).getChangedAt());
        callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),request,actor(Set.of("ORDER_UPDATE"))).error(403,"FORBIDDEN");
    }
    @Test void wholeOrderCancellationIsIdempotentAndCannotCancelCookingOrPaidOrder() throws Exception {
        var order=create(false); var cancelled=call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","void")); cancelled.expect(200);
        var again=call(post(path(order)+"/cancel"),Map.of("expectedVersion",0,"reason","different")); assertThat(again.body).isEqualTo(cancelled.body);
        assertThat(events("ORDER_CANCELLED")).isEqualTo(1); assertThat(history(order.lineId())).hasSize(2);
        var cooking=create(false); jdbc.update("update order_items set status='COOKING' where order_id=?",cooking.id());
        call(post(path(cooking)+"/cancel"),Map.of("expectedVersion",cooking.version(),"reason","no")).error(409,"ORDER_ITEM_NOT_EDITABLE");
        var paid=create(false); jdbc.update("update orders set paid_amount=1,payment_status='PAID' where id=?",paid.id());
        call(post(path(paid)+"/cancel"),Map.of("expectedVersion",paid.version(),"reason","no")).error(409,"ORDER_HAS_FINANCIAL_OBLIGATIONS");
    }
    @Test void usageBlocksSessionCancellationUntilAllOrdersAreCancelledAndUnpaid() throws Exception {
        var first=create(true);
        cancelSession().error(409,"TABLE_SESSION_IN_USE");
        call(post(path(first)+"/cancel"),Map.of("expectedVersion",first.version(),"reason","void")).expect(200);
        var second=create(true); second.expect(201); cancelSession().error(409,"TABLE_SESSION_IN_USE");
        call(post(path(second)+"/cancel"),Map.of("expectedVersion",second.version(),"reason","void")).expect(200);
        jdbc.update("update orders set paid_amount=1 where id=?",first.id()); cancelSession().error(409,"TABLE_SESSION_IN_USE");
        jdbc.update("update orders set paid_amount=0,payment_status='REFUNDED' where id=?",first.id()); cancelSession().error(409,"TABLE_SESSION_IN_USE");
        jdbc.update("update orders set payment_status='UNPAID' where id=?",first.id()); cancelSession().expect(200);
    }
    @Test void createAndCancelSessionRaceCannotProduceOrderOnCancelledSession() throws Exception {
        var results=race(()->create(true),this::cancelSession); var create=results.get(0); var cancel=results.get(1);
        if(create.status==201) { cancel.error(409,"TABLE_SESSION_IN_USE"); assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN); }
        else { create.error(409,"TABLE_SESSION_NOT_OPEN"); cancel.expect(200); assertThat(orderCount()).isZero(); }
    }
    @Test void auditAndHistoryFailureRollBackCreateAndMutation() throws Exception {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_CREATED"),any(),any(),any(),any(),any());
        create(false).expect(500); assertThat(orderCount()).isZero(); reset(audit);
        doThrow(new IllegalStateException("history unavailable")).when(histories).saveAll(any());
        create(false).expect(500); assertThat(orderCount()).isZero(); reset(histories);
        var order=create(false); doThrow(new IllegalStateException("audit unavailable")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_ITEM_UPDATED"),any(),any(),any(),any(),any());
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"note","failed")).expect(500);
        assertThat(detail(order).body).isEqualTo(order.body); reset(audit);
        doThrow(new IllegalStateException("history unavailable")).when(histories).saveAll(any());
        call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","failed")).expect(500);
        assertThat(detail(order).body).isEqualTo(order.body); assertThat(history(order.lineId())).hasSize(1);
    }
    @Test void usageQueryFailureFailsClosedAndDoesNotCancelSession() throws Exception {
        // Simulate a downstream outage at the real usage query, not an always-empty adapter.
        jdbc.execute("ALTER TABLE orders RENAME TO unavailable_orders");
        try { cancelSession().expect(500); assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN); }
        finally { jdbc.execute("ALTER TABLE unavailable_orders RENAME TO orders"); }
    }
    @Test void unknownReadonlyFieldsInvalidQuantitiesAndMissingHeaderAreRejected() throws Exception {
        for(String field:List.of("restaurantId","createdBy","unitPrice","totalAmount","paymentStatus","currencyCode")) {
            var request=body(false); request.put(field,"injected"); postCreate(request,key()).error(400,"INVALID_REQUEST_BODY");
        }
        for(String quantity:List.of("0","-1","0.0001","1000000000")) { var request=body(false); request.put("items",List.of(lineRequest(f.item.getId(),quantity,null))); postCreate(request,key()).error(400,"VALIDATION_ERROR"); }
        call(post(ORDERS),body(false)).error(400,"INVALID_IDEMPOTENCY_KEY"); postCreate(body(false)," ").error(400,"INVALID_IDEMPOTENCY_KEY");
        var empty=body(false); empty.put("items",List.of()); postCreate(empty,key()).error(400,"VALIDATION_ERROR");
        var qr=body(false); qr.put("sourceChannel","QR_TABLE"); postCreate(qr,key()).error(400,"UNSUPPORTED_ORDER_SOURCE");
        var order=create(false); call(patch(path(order)),Map.of("expectedVersion",order.version())).error(400,"EMPTY_UPDATE_REQUEST");
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("quantity",1)).error(400,"VALIDATION_ERROR");
        call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason"," ")).error(400,"VALIDATION_ERROR");
    }
    @Test void permissionsFeaturesAndSystemTenantAreEnforced() throws Exception {
        assertThat(mvc.perform(get(ORDERS)).andReturn().getResponse().getStatus()).isEqualTo(401);
        callAs(post(ORDERS).header("Idempotency-Key",key()),body(false),actor(Set.of("ORDER_VIEW"))).error(403,"FORBIDDEN");
        var system=new CurrentUser(UUID.randomUUID(),null,"system@example.test","SUPER_ADMIN",ALL);
        callAs(get(ORDERS),null,system).error(403,"TENANT_ACCESS_DENIED"); callAs(post(ORDERS).header("Idempotency-Key",key()),body(false),system).error(403,"TENANT_ACCESS_DENIED");
        for(String feature:List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT")) {
            var available=new ArrayList<>(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT")); available.remove(feature); setFeatures(available);
            postCreate(body(feature.equals("TABLE_MANAGEMENT")),key()).error(403,"FEATURE_NOT_ENTITLED");
        }
        setFeatures(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT"));
        jdbc.update("update restaurant_subscriptions set end_at=? where id=?",java.sql.Timestamp.from(NOW),f.subscription.getId()); create(false).error(403,"SUBSCRIPTION_NOT_ACTIVE");
    }
    @Test void defaultCashierManagerAndUserGrantDenyUseRealJwtPermissions() throws Exception {
        for(String role:List.of("CASHIER","MANAGER")) {
            jdbc.update("update users set role_id=(select id from roles where code=? and restaurant_id is null) where id=?",role,f.user.getId());
            var granted=permissions.getEffectivePermissionCodes(f.user.getId()); assertThat(granted).contains("ORDER_CREATE","ORDER_UPDATE","TABLE_VIEW");
            var who=new CurrentUser(f.user.getId(),f.restaurant.getId(),f.user.getEmail(),role,granted);
            bearer(post(ORDERS).header("Idempotency-Key",key()),body(false),who).expect(201);
        }
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'DENY',? from permissions where code='ORDER_CREATE'",f.user.getId(),f.user.getId());
        var denied=new CurrentUser(f.user.getId(),f.restaurant.getId(),f.user.getEmail(),"MANAGER",permissions.getEffectivePermissionCodes(f.user.getId()));
        bearer(post(ORDERS).header("Idempotency-Key",key()),body(false),denied).error(403,"FORBIDDEN");
        jdbc.update("delete from user_permissions where user_id=?",f.user.getId());
        jdbc.update("update users set role_id=(select id from roles where code='KITCHEN' and restaurant_id is null) where id=?",f.user.getId());
        jdbc.update("insert into user_permissions(user_id,permission_id,effect,created_by) select ?,id,'GRANT',? from permissions where code='ORDER_CREATE'",f.user.getId(),f.user.getId());
        var custom=new CurrentUser(f.user.getId(),f.restaurant.getId(),f.user.getEmail(),"KITCHEN",permissions.getEffectivePermissionCodes(f.user.getId()));
        bearer(post(ORDERS).header("Idempotency-Key",key()),body(false),custom).expect(201);
    }
    @Test void everyMutationRequiresItsOwnPermission() throws Exception {
        var order=create(false); var viewer=actor(Set.of("ORDER_VIEW"));
        callAs(patch(path(order)),Map.of("expectedVersion",order.version(),"note","no"),viewer).error(403,"FORBIDDEN");
        callAs(post(path(order)+"/items"),Map.of("expectedVersion",order.version(),"items",List.of(lineRequest(f.item.getId(),"1",null))),viewer).error(403,"FORBIDDEN");
        callAs(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",1),viewer).error(403,"FORBIDDEN");
        callAs(post(path(order)+"/items/"+order.lineId()+"/cancel"),Map.of("expectedVersion",order.version(),"reason","no"),viewer).error(403,"FORBIDDEN");
        callAs(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version()),viewer).error(403,"FORBIDDEN");
        callAs(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","no"),actor(Set.of("ORDER_UPDATE"))).error(403,"FORBIDDEN");
    }
    @Test void snapshotFeatureNotCatalogAndAccountRestaurantGuardsAreRespected() throws Exception {
        setFeatures(List.of("QR_MENU_VIEW")); create(false).error(403,"FEATURE_NOT_ENTITLED");
        setFeatures(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT"));
        var who=actor(ALL); jdbc.update("update users set is_active=false where id=?",f.user.getId()); bearer(post(ORDERS).header("Idempotency-Key",key()),body(false),who).error(401,"ACCOUNT_INACTIVE");
        jdbc.update("update users set is_active=true where id=?",f.user.getId()); jdbc.update("update restaurants set status='SUSPENDED' where id=?",f.restaurant.getId());
        bearer(get(ORDERS),null,who).error(403,"RESTAURANT_INACTIVE");
    }
    @Test void listPaginationFiltersAndQueryCountAvoidNPlusOne() throws Exception {
        for(int i=0;i<8;i++) create(false).expect(201);
        var page=call(get(ORDERS).param("size","3")); page.expect(200); assertThat(page.body.path("totalElements").asInt()).isEqualTo(8); assertThat(page.body.path("totalPages").asInt()).isEqualTo(3);
        var statistics=emf.unwrap(SessionFactory.class).getStatistics(); statistics.setStatisticsEnabled(true);
        try { statistics.clear(); call(get(ORDERS).param("size","3")); long small=statistics.getPrepareStatementCount(); statistics.clear(); call(get(ORDERS).param("size","8")); assertThat(statistics.getPrepareStatementCount()).isEqualTo(small); }
        finally { statistics.setStatisticsEnabled(false); }
        call(get(ORDERS).param("sortBy","requestHash")).error(400,"VALIDATION_ERROR");
        call(get(ORDERS).param("size","101")).error(400,"VALIDATION_ERROR");
        assertThat(call(get(ORDERS).param("serviceType","DINE_IN")).body.path("totalElements").asInt()).isZero();
        var order=create(true); jdbc.update("update orders set order_code='MATCH%_\\' where id=?",order.id());
        assertThat(call(get(ORDERS).param("q","%_\\")).body.path("totalElements").asInt()).isEqualTo(1);
        assertThat(call(get(ORDERS).param("tableSessionId",UUID.randomUUID().toString())).body.path("totalElements").asInt()).isZero();
    }
    @Test void batchCreationQueryCountDoesNotGrowWithNumberOfItems() throws Exception {
        var other=menuItem(f.restaurant.getId(),null,"Other",new BigDecimal("1.00")); var stats=emf.unwrap(SessionFactory.class).getStatistics(); stats.setStatisticsEnabled(true);
        try {
            stats.clear(); create(false).expect(201); long small=stats.getPrepareStatementCount()-2;
            var requests=new ArrayList<Map<String,Object>>(); for(int i=0;i<20;i++) requests.add(lineRequest(i%2==0?f.item.getId():other.getId(),"1","line "+i));
            clearInvocations(menuItems); stats.clear(); var request=body(false); request.put("items",requests); postCreate(request,key()).expect(201);
            assertThat(stats.getPrepareStatementCount()-40).isEqualTo(small);
            verify(menuItems,times(1)).findAllByRestaurantIdAndIdIn(eq(f.restaurant.getId()),any());
        } finally { stats.setStatisticsEnabled(false); }
    }
    @Test void databaseConstraintsAndHistoryAppendOnlyProtectTenantAndSnapshots() throws Exception {
        var order=create(true); var own=f; f=fixture(true); var foreign=f; var foreignOrder=create(true); f=own;
        assertThatThrownBy(()->jdbc.update("update orders set table_session_id=? where id=?",foreign.session.getId(),order.id())).hasStackTraceContaining("fk_order_session");
        assertThatThrownBy(()->jdbc.update("update order_items set item_id=? where id=?",foreign.item.getId(),order.lineId())).hasStackTraceContaining("fk_order_item_menu");
        assertThatThrownBy(()->jdbc.update("update order_items set order_id=?,line_number=999 where id=?",foreignOrder.id(),order.lineId())).hasStackTraceContaining("fk_order_item_order");
        assertThatThrownBy(()->jdbc.update("update order_item_status_history set note='changed' where order_item_id=?",order.lineId())).hasStackTraceContaining("append-only");
        assertThatThrownBy(()->jdbc.update("delete from order_item_status_history where order_item_id=?",order.lineId())).hasStackTraceContaining("append-only");
        for(String assignment:List.of("quantity=0","unit_price=-1","line_total=-1","status='UNKNOWN'"))
            assertThatThrownBy(()->jdbc.update("update order_items set "+assignment+" where id=?",order.lineId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("""
            insert into orders(restaurant_id,order_code,table_session_id,service_type,source_channel,status,currency_code,created_at,updated_at,idempotency_key,request_hash)
            select restaurant_id,'DUPLICATE',table_session_id,service_type,source_channel,status,currency_code,created_at,updated_at,idempotency_key,request_hash from orders where id=?
            """,order.id())).hasStackTraceContaining("uq_order_idempotency");
    }
    @Test void codeCollisionIsStableConflictAndRollbackHasNoFalseAudit(CapturedOutput output) throws Exception {
        var first=create(false); doReturn(first.body.path("orderCode").asText()).when(codes).generate(); create(false).error(409,"ORDER_CODE_CONFLICT");
        assertThat(orderCount()).isEqualTo(1); assertThat(events("ORDER_CREATED")).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("fixture-only-password");
    }
    @Test void publicQrTokenDoesNotGrantOrderMutationAndThereIsNoPaymentOrCloseRoute() throws Exception {
        assertThat(mvc.perform(post(ORDERS).header("Authorization","Bearer "+"q".repeat(43)).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(false)))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(post("/api/v1/public/menu/tables/"+"q".repeat(43))).andReturn().getResponse().getStatus()).isEqualTo(401);
        assertThat(orderCount()).isZero();
    }

    @Test void draftMetadataUpdatesAndNoOpPreserveCorrectVersionWithoutHistory() throws Exception {
        var order=create(false);
        var updated=call(patch(path(order)),Map.of("expectedVersion",order.version(),"customerName"," Name ","customerPhone"," 0900000000 ","guestCount",2,"note"," note "));
        updated.expect(200); assertThat(updated.body.path("customerName").asText()).isEqualTo("Name");
        assertThat(updated.version()).isEqualTo(order.version()+1); assertThat(detail(order).body).isEqualTo(updated.body);
        var unchanged=call(patch(path(order)),Map.of("expectedVersion",updated.version(),"customerName","Name","guestCount",2,"note","note"));
        assertThat(unchanged.body).isEqualTo(updated.body); assertThat(events("ORDER_UPDATED")).isEqualTo(1); assertThat(history(order.lineId())).hasSize(1);
        var cleared=call(patch(path(order)),Map.of("expectedVersion",updated.version(),"customerPhone","")); cleared.expect(200);
        assertThat(cleared.body.path("customerPhone").isNull()).isTrue();
    }
    @Test void confirmationAndQuantityUpdateSerializeAtTheSameVersion() throws Exception {
        var order=create(false);
        var results=race(()->call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version())),
                ()->call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3)));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200,409);
    }
    @Test void sameIdempotencyKeyInDifferentTenantsIsIndependent() throws Exception {
        String key=key(); var first=postCreate(body(false),key); first.expect(201); f=fixture(false);
        var second=postCreate(body(false),key); second.expect(201); assertThat(second.id()).isNotEqualTo(first.id());
    }
    @Test void amountOverflowRollsBackAndNestedClientPricesAreRejected() throws Exception {
        var item=f.item; item.setSalePrice(new BigDecimal("999999999999.99")); menuItems.saveAndFlush(item);
        postCreate(body(false),key()).error(400,"ORDER_AMOUNT_LIMIT_EXCEEDED"); assertThat(orderCount()).isZero();
        var request=body(false); var line=lineRequest(f.item.getId(),"1",null); line.put("unitPrice",BigDecimal.ONE); request.put("items",List.of(line));
        postCreate(request,key()).error(400,"INVALID_REQUEST_BODY");
    }
    @Test void noSuccessfulAuditLeaksCustomerOrIdempotencyData() throws Exception {
        String key=key(); var request=body(false); request.put("customerName","private customer"); request.put("customerPhone","private-phone"); request.put("note","private note");
        var order=postCreate(request,key); order.expect(201);
        String rows=jdbc.queryForList("select before_data,after_data from audit_logs where restaurant_id=?",f.restaurant.getId()).toString();
        assertThat(rows).doesNotContain(key,"private customer","private-phone","private note","requestHash");
    }
    @Test void corsAllowsIdempotencyHeaderAndExposesReplayWithoutWildcardOrigin() throws Exception {
        var preflight=mvc.perform(options(ORDERS).header("Origin","http://localhost:3000")
                .header("Access-Control-Request-Method","POST").header("Access-Control-Request-Headers","Authorization,Content-Type,Idempotency-Key")).andReturn().getResponse();
        assertThat(preflight.getStatus()).isEqualTo(200);
        assertThat(preflight.getHeader("Access-Control-Allow-Origin")).isEqualTo("http://localhost:3000");
        assertThat(preflight.getHeader("Access-Control-Allow-Headers")).contains("Idempotency-Key");
        var who=actor(ALL);
        var request=post(ORDERS).header("Origin","http://localhost:3000").header("Idempotency-Key",key()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body(false))).with(authentication(new UsernamePasswordAuthenticationToken(who,null,who.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
        var response=mvc.perform(request).andReturn().getResponse(); assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Idempotency-Replayed");
        assertThat(mvc.perform(options(ORDERS).header("Origin","https://untrusted.example").header("Access-Control-Request-Method","POST")).andReturn().getResponse().getStatus()).isEqualTo(403);
    }
    @Test void pendingLineCanBeCancelledWithoutVoidingAnotherCookingLine() throws Exception {
        var request=body(false); request.put("items",List.of(lineRequest(f.item.getId(),"1",null),lineRequest(f.item.getId(),"1","second")));
        var order=postCreate(request,key()); order.expect(201);
        var confirmed=call(post(path(order)+"/confirm"),Map.of("expectedVersion",order.version()));
        UUID cooking=UUID.fromString(order.body.path("items").get(1).path("id").asText());
        jdbc.update("update order_items set status='COOKING' where id=?",cooking);
        var cancelled=call(post(path(order)+"/items/"+order.lineId()+"/cancel"),Map.of("expectedVersion",confirmed.version(),"reason","void pending")); cancelled.expect(200);
        assertThat(cancelled.body.path("items").get(1).path("status").asText()).isEqualTo("COOKING");
        assertThat(cancelled.body.path("status").asText()).isEqualTo("CONFIRMED");
        call(post(path(order)+"/items/"+cooking+"/cancel"),Map.of("expectedVersion",cancelled.version(),"reason","not allowed")).error(409,"ORDER_ITEM_NOT_EDITABLE");
    }

    @Test void absentNullAndExplicitDraftModesKeepLegacySemanticsAndHash() throws Exception {
        String key=key(); var request=body(false); var first=postCreate(request,key); first.expect(201);
        request.put("submissionMode","DRAFT"); var replay=postCreate(request,key); replay.expect(200);
        assertThat(replay.body).isEqualTo(first.body); assertThat(replay.replay).isEqualTo("true");
        request.put("submissionMode",null); assertThat(postCreate(request,key).body).isEqualTo(first.body);
        assertThat(first.body.path("status").asText()).isEqualTo("OPEN"); assertThat(first.body.path("confirmedAt").isNull()).isTrue();
        assertThat(events("ORDER_CONFIRMED")).isZero();
    }
    @Test void legacyStoredHashReplaysWithoutRewritingIt() throws Exception {
        var request=body(false); String key=key(); var first=postCreate(request,key); first.expect(201);
        var old=new LinkedHashMap<String,Object>(); old.put("serviceType","TAKEAWAY"); old.put("sourceChannel","CASHIER"); old.put("tableSessionId",null);
        old.put("guestCount",null); old.put("customerName",null); old.put("customerPhone",null); old.put("note",null);
        old.put("items",List.of(lineRequest(f.item.getId(),"2.000","no onions")));
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest((f.user.getId()+"\n"+json.writeValueAsString(old)).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        jdbc.update("update orders set request_hash=? where id=?",hash,first.id());
        request.put("submissionMode","DRAFT"); var replay=postCreate(request,key); replay.expect(200); assertThat(replay.body).isEqualTo(first.body);
        assertThat(orders.findById(first.id()).orElseThrow().getRequestHash()).isEqualTo(hash);
    }
    @Test void submitCreatesConfirmedDineInAndTakeawayWithPendingLinesAndNoFakeKitchen() throws Exception {
        for(boolean dine:List.of(true,false)) {
            var order=submit(dine); order.expect(201);
            assertThat(order.body.path("status").asText()).isEqualTo("CONFIRMED");
            assertThat(Instant.parse(order.body.path("confirmedAt").asText())).isEqualTo(NOW);
            assertThat(order.body.path("paymentStatus").asText()).isEqualTo("UNPAID");
            assertThat(order.body.path("paidAmount").decimalValue()).isZero();
            assertThat(order.body.path("items").get(0).path("status").asText()).isEqualTo("PENDING");
            assertThat(order.body.path("items").get(0).path("sentToKitchenAt").isNull()).isTrue();
            assertThat(detail(order).body).isEqualTo(order.body); assertThat(history(order.lineId())).hasSize(1);
        }
        assertThat(events("ORDER_CREATED")).isEqualTo(2); assertThat(events("ORDER_CONFIRMED")).isEqualTo(2);
    }
    @Test void createSubmitNeedsBothPermissionsEvenOnReplayButDraftDoesNot() throws Exception {
        var request=body(false); request.put("submissionMode","SUBMIT"); String key=key();
        callAs(post(ORDERS).header("Idempotency-Key",key),request,actor(Set.of("ORDER_CREATE"))).error(403,"FORBIDDEN");
        var created=postCreate(request,key); created.expect(201);
        callAs(post(ORDERS).header("Idempotency-Key",key),request,actor(Set.of("ORDER_CREATE"))).error(403,"FORBIDDEN");
        callAs(post(ORDERS).header("Idempotency-Key",key()),body(false),actor(Set.of("ORDER_CREATE"))).expect(201);
    }
    @Test void creationModeChangesConflictAndSubmitReplayDoesNotReconfirm() throws Exception {
        var request=body(false); String key=key(); postCreate(request,key).expect(201);
        request.put("submissionMode","SUBMIT"); postCreate(request,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        String submittedKey=key(); var submitted=postCreate(request,submittedKey); submitted.expect(201);
        var replay=postCreate(request,submittedKey); replay.expect(200); assertThat(replay.body).isEqualTo(submitted.body);
        assertThat(events("ORDER_CONFIRMED")).isEqualTo(1);
    }
    @Test void createSubmitConfirmationAuditOrHistoryFailureRollsBackEverything() throws Exception {
        doThrow(new IllegalStateException("confirmation audit unavailable")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_CONFIRMED"),any(),any(),any(),any(),any());
        submit(false).expect(500); assertThat(orderCount()).isZero(); assertThat(events("ORDER_CREATED")).isZero(); reset(audit);
        doThrow(new IllegalStateException("history unavailable")).when(histories).saveAll(any());
        submit(false).expect(500); assertThat(orderCount()).isZero(); assertThat(events("ORDER_CONFIRMED")).isZero();
    }
    @Test void confirmedAdditionKeepsRootAndOldSnapshotsWhileCreatingSeparateCurrentPriceLines() throws Exception {
        var order=submit(true); order.expect(201); var oldRoot=orders.findById(order.id()).orElseThrow(); var oldLine=order.body.path("items").get(0).deepCopy();
        jdbc.update("update items set sale_price=60000,name='New Pho',base_unit='plate' where id=?",f.item.getId());
        var request=addition(order); request.put("items",List.of(lineRequest(f.item.getId(),"1","no onions"),lineRequest(f.item.getId(),"1","no onions")));
        var added=append(order,request,key()); added.expect(200);
        assertThat(added.id()).isEqualTo(order.id()); assertThat(added.body.path("items").get(0)).isEqualTo(oldLine);
        assertThat(added.body.path("items").size()).isEqualTo(3);
        assertThat(added.body.path("confirmedAt")).isEqualTo(order.body.path("confirmedAt")); assertThat(added.body.path("status").asText()).isEqualTo("CONFIRMED");
        for(int index:List.of(1,2)) { var line=added.body.path("items").get(index); assertThat(line.path("unitPrice").decimalValue()).isEqualByComparingTo("60000");
            assertThat(line.path("itemName").asText()).isEqualTo("New Pho"); assertThat(line.path("unit").asText()).isEqualTo("plate");
            assertThat(line.path("id").asText()).isNotEqualTo(order.lineId().toString()); assertThat(line.path("status").asText()).isEqualTo("PENDING");
            assertThat(line.path("sentToKitchenAt").isNull()).isTrue(); assertThat(history(UUID.fromString(line.path("id").asText()))).hasSize(1); }
        assertThat(added.body.path("items").get(1).path("id")).isNotEqualTo(added.body.path("items").get(2).path("id"));
        assertThat(added.body.path("totalAmount").decimalValue()).isEqualByComparingTo("220000");
        var root=orders.findById(order.id()).orElseThrow(); assertThat(root.getIdempotencyKey()).isEqualTo(oldRoot.getIdempotencyKey()); assertThat(root.getRequestHash()).isEqualTo(oldRoot.getRequestHash());
        assertThat(events("ORDER_CONFIRMED")).isEqualTo(1); assertThat(detail(order).body).isEqualTo(added.body);
    }
    @Test void openSubmittedAdditionConfirmsAndPreservesOldPrice() throws Exception {
        var order=create(false); jdbc.update("update items set sale_price=60000 where id=?",f.item.getId());
        var added=append(order,addition(order),key()); added.expect(200);
        assertThat(added.body.path("status").asText()).isEqualTo("CONFIRMED"); assertThat(added.body.path("items").get(0)).isEqualTo(order.body.path("items").get(0));
        assertThat(events("ORDER_ITEMS_ADDED")).isEqualTo(1); assertThat(events("ORDER_CONFIRMED")).isEqualTo(1); assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void openSubmitRechecksOldMenuAndRollsBackNewLinesAuditHistoryAndLedger() throws Exception {
        var order=create(false); var other=menuItem(f.restaurant.getId(),null,"Other",BigDecimal.ONE);
        jdbc.update("update items set is_active=false where id=?",f.item.getId());
        var request=addition(order); request.put("items",List.of(lineRequest(other.getId(),"1",null)));
        append(order,request,key()).error(409,"ITEM_NOT_SELLABLE");
        assertThat(detail(order).body).isEqualTo(order.body); assertThat(ledgerCount()).isZero(); assertThat(events("ORDER_ITEMS_ADDED")).isZero(); assertThat(events("ORDER_CONFIRMED")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from order_item_status_history where restaurant_id=?",Long.class,f.restaurant.getId())).isEqualTo(1);
    }
    @Test void appendAuditHistoryAndConfirmationFailuresLeaveNoSuccessLedger() throws Exception {
        var order=create(false); var request=addition(order); String key=key();
        doThrow(new IllegalStateException("confirmation unavailable")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_CONFIRMED"),any(),any(),any(),any(),any());
        append(order,request,key).expect(500); assertThat(detail(order).body).isEqualTo(order.body); assertThat(ledgerCount()).isZero(); reset(audit);
        doThrow(new IllegalStateException("history unavailable")).when(histories).saveAll(any());
        append(order,request,key).expect(500); assertThat(detail(order).body).isEqualTo(order.body); assertThat(ledgerCount()).isZero(); reset(histories);
        doThrow(new IllegalStateException("append audit unavailable")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_ITEMS_ADDED"),any(),any(),any(),any(),any());
        append(order,request,key).expect(500); assertThat(detail(order).body).isEqualTo(order.body); assertThat(ledgerCount()).isZero(); reset(audit);
        append(order,request,key).expect(200); assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void submittedAdditionChecksCurrencySessionAndPhysicalSeating() throws Exception {
        var order=submit(true); var request=addition(order);
        jdbc.update("update restaurants set currency_code='USD' where id=?",f.restaurant.getId()); append(order,request,key()).error(409,"ORDER_CURRENCY_MISMATCH");
        jdbc.update("update restaurants set currency_code='VND' where id=?",f.restaurant.getId());
        for(String state:List.of("CLOSED","CANCELLED")) { jdbc.update("update table_sessions set status=?,closed_at=opened_at,closed_by=opened_by,cancel_reason='fixture' where id=?",state,f.session.getId()); append(order,request,key()).error(409,"TABLE_SESSION_NOT_OPEN"); }
        jdbc.update("update table_sessions set status='OPEN',closed_at=null,closed_by=null,cancel_reason=null where id=?",f.session.getId());
        jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",f.table.getId()); append(order,request,key()).error(409,"TABLE_INACTIVE");
        jdbc.update("update restaurant_tables set status='AVAILABLE',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),f.table.getId()); append(order,request,key()).error(404,"TABLE_NOT_FOUND");
        jdbc.update("update restaurant_tables set deleted_at=null where id=?",f.table.getId());
        var area=new TableArea(); area.initialize(f.restaurant.getId(),NOW); area.setName("Area"); area.setActive(false); area=areas.saveAndFlush(area);
        jdbc.update("update restaurant_tables set area_id=? where id=?",area.getId(),f.table.getId()); append(order,request,key()).error(409,"TABLE_AREA_INACTIVE");
        assertThat(ledgerCount()).isZero(); assertThat(detail(order).body).isEqualTo(order.body);
    }
    @Test void submittedAdditionRejectsFinancialAndTerminalParents() throws Exception {
        var order=submit(false); var request=addition(order);
        for(String payment:List.of("PARTIALLY_PAID","PAID","PARTIALLY_REFUNDED","REFUNDED")) {
            jdbc.update("update orders set payment_status=? where id=?",payment,order.id()); append(order,request,key()).error(409,"ORDER_HAS_FINANCIAL_OBLIGATIONS"); }
        jdbc.update("update orders set payment_status='UNPAID',paid_amount=1 where id=?",order.id()); append(order,request,key()).error(409,"ORDER_HAS_FINANCIAL_OBLIGATIONS");
        jdbc.update("update orders set paid_amount=0 where id=?",order.id());
        for(String status:List.of("PREPARING","READY","SERVED","COMPLETED","CANCELLED")) {
            jdbc.update("update orders set status=?,cancelled_at=created_at,cancel_reason='fixture' where id=?",status,order.id()); append(order,request,key()).error(409,"INVALID_ORDER_TRANSITION"); }
        assertThat(ledgerCount()).isZero(); assertThat(events("ORDER_ITEMS_ADDED")).isZero();
    }
    @Test void oldEditsRemainOpenOnlyAndAutoConfirmCancellationUsesCancelPermission() throws Exception {
        var order=submit(false); var updater=actor(Set.of("ORDER_UPDATE"));
        callAs(patch(path(order)),Map.of("expectedVersion",order.version(),"note","no"),updater).error(409,"ORDER_NOT_EDITABLE");
        callAs(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3),updater).error(409,"ORDER_NOT_EDITABLE");
        var added=append(order,addition(order),key()); added.expect(200); String lineId=added.body.path("items").get(1).path("id").asText();
        var cancel=Map.of("expectedVersion",added.version(),"reason","void");
        callAs(post(path(order)+"/items/"+lineId+"/cancel"),cancel,updater).error(403,"FORBIDDEN");
        callAs(post(path(order)+"/items/"+lineId+"/cancel"),cancel,actor(Set.of("ORDER_CANCEL"))).expect(200);
    }
    @Test void appendReplayReturnsCurrentCancelledResourceBeforeVersionMenuAndSeatingChecks() throws Exception {
        var order=submit(true); var request=addition(order); String key=key(); var added=append(order,request,key); added.expect(200);
        var cancelled=call(post(path(order)+"/cancel"),Map.of("expectedVersion",added.version(),"reason","void")); cancelled.expect(200);
        cancelSession().expect(200); jdbc.update("update items set is_active=false where id=?",f.item.getId());
        var replay=append(order,request,key); replay.expect(200); assertThat(replay.replay).isEqualTo("true"); assertThat(replay.body).isEqualTo(cancelled.body);
        assertThat(events("ORDER_ITEMS_ADDED")).isEqualTo(1); assertThat(ledgerCount()).isEqualTo(1);
        assertThat(history(UUID.fromString(added.body.path("items").get(1).path("id").asText()))).hasSize(2);
    }
    @Test void appendReplayRetainsPermissionAndFeatureChecks() throws Exception {
        var order=submit(false); var request=addition(order); String key=key(); append(order,request,key).expect(200);
        callAs(post(path(order)+"/items").header("Idempotency-Key",key),request,actor(Set.of("ORDER_VIEW"))).error(403,"FORBIDDEN");
        setFeatures(List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT")); append(order,request,key).error(403,"FEATURE_NOT_ENTITLED");
        assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void appendKeyRejectsDifferentActorTargetPayloadAndVersion() throws Exception {
        var order=submit(false); var request=addition(order); String key=key(); var added=append(order,request,key); added.expect(200);
        var otherOrder=submit(false); append(otherOrder,addition(otherOrder),key).error(409,"IDEMPOTENCY_KEY_REUSED");
        var changed=new LinkedHashMap<>(request); changed.put("expectedVersion",added.version()); append(order,changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        changed=new LinkedHashMap<>(request); changed.put("items",List.of(lineRequest(f.item.getId(),"2",null))); append(order,changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        var user=new User(); user.setRestaurantId(f.restaurant.getId()); user.setRoleId(f.user.getRoleId()); user.setName("Other"); user.setEmail(UUID.randomUUID()+"@example.test"); user.setPasswordHash("fixture"); user=users.saveAndFlush(user);
        var who=new CurrentUser(user.getId(),f.restaurant.getId(),user.getEmail(),"OWNER",ALL);
        callAs(post(path(order)+"/items").header("Idempotency-Key",key),request,who).error(409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(detail(order).body).isEqualTo(added.body); assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void simultaneousSameKeyAppendHasExactlyOneMutationAndOneReplay() throws Exception {
        var order=submit(false); var request=addition(order); String key=key(); var results=race(()->append(order,request,key),()->append(order,request,key));
        assertThat(results).extracting(Result::status).containsOnly(200); assertThat(results).extracting(Result::replay).containsExactlyInAnyOrder("false","true");
        assertThat(results.get(0).body).isEqualTo(results.get(1).body); assertThat(detail(order).body.path("items").size()).isEqualTo(2);
        assertThat(events("ORDER_ITEMS_ADDED")).isEqualTo(1); assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void simultaneousDifferentKeysSameVersionAppendHasOneWinner() throws Exception {
        var order=submit(false); var request=addition(order); var results=race(()->append(order,request,key()),()->append(order,request,key()));
        assertThat(results).extracting(Result::status).containsExactlyInAnyOrder(200,409);
        results.stream().filter(r->r.status==409).forEach(r->r.error(409,"CONCURRENT_ORDER_UPDATE"));
        assertThat(detail(order).body.path("items").size()).isEqualTo(2); assertThat(ledgerCount()).isEqualTo(1);
    }
    @Test void zeroPriceFixedClockAppendStillAdvancesVersionAndReplaysNormalizedPayload() throws Exception {
        jdbc.update("update items set sale_price=0 where id=?",f.item.getId()); var order=submit(false); var request=addition(order); String key=key();
        var added=append(order,request,key); added.expect(200); assertThat(added.version()).isGreaterThan(order.version());
        assertThat(added.body.path("updatedAt")).isEqualTo(order.body.path("updatedAt")); assertThat(added.body.path("totalAmount").decimalValue()).isZero();
        request.put("items",List.of(lineRequest(f.item.getId(),"1.000"," "))); var replay=append(order,request,key); replay.expect(200);
        assertThat(replay.body).isEqualTo(added.body); assertThat(replay.replay).isEqualTo("true");
    }
    @Test void appendSubmitRequiresValidKeyVersionModeAndUpdatePermission() throws Exception {
        var order=submit(false); var request=addition(order);
        call(post(path(order)+"/items"),request).error(400,"INVALID_IDEMPOTENCY_KEY");
        append(order,request," ").error(400,"INVALID_IDEMPOTENCY_KEY"); append(order,request,"x".repeat(101)).error(400,"INVALID_IDEMPOTENCY_KEY");
        callAs(post(path(order)+"/items").header("Idempotency-Key",key()),request,actor(Set.of("ORDER_CREATE"))).error(403,"FORBIDDEN");
        request.remove("expectedVersion"); append(order,request,key()).error(400,"VALIDATION_ERROR");
        request=addition(order); request.put("submissionMode","COOKING"); append(order,request,key()).error(400,"INVALID_REQUEST_BODY");
        request=body(false); request.put("submissionMode","COOKING"); postCreate(request,key()).error(400,"INVALID_REQUEST_BODY");
    }
    @Test void draftAppendStaysOpenOnlyAndDoesNotUseSubmitLedger() throws Exception {
        var order=create(false); var request=addition(order); request.put("submissionMode","DRAFT");
        var added=call(post(path(order)+"/items"),request); added.expect(200); assertThat(added.body.path("status").asText()).isEqualTo("OPEN");
        assertThat(ledgerCount()).isZero(); assertThat(added.replay).isEqualTo("false");
        call(post(path(order)+"/items"),request).error(409,"CONCURRENT_ORDER_UPDATE");
        var confirmed=submit(false); request=addition(confirmed); request.remove("submissionMode"); call(post(path(confirmed)+"/items"),request).error(409,"ORDER_NOT_EDITABLE");
    }
    @Test void tenantScopedAppendAndLedgerAllowIndependentKeysWithoutForeignAccess() throws Exception {
        var own=f; var ownOrder=submit(false); String key=key(); append(ownOrder,addition(ownOrder),key).expect(200);
        f=fixture(false); var foreignOrder=submit(false); append(ownOrder,addition(ownOrder),key()).error(404,"ORDER_NOT_FOUND");
        append(foreignOrder,addition(foreignOrder),key).expect(200); assertThat(ledgerCount()).isEqualTo(1);
        f=own; var foreignItem=foreignOrder.body.path("items").get(0).path("itemId").asText(); var request=addition(detail(ownOrder));
        request.put("items",List.of(lineRequest(UUID.fromString(foreignItem),"1",null))); append(ownOrder,request,key()).error(404,"MENU_ITEM_NOT_FOUND");
        assertThatThrownBy(()->jdbc.update("update order_item_submissions set order_id=? where restaurant_id=?",foreignOrder.id(),f.restaurant.getId())).hasStackTraceContaining("fk_order_item_submission_tenant_order");
    }
    @Test void databaseEnforcesAppendNamespaceUniqueKeyAndCreateKeyIsUnchanged() throws Exception {
        var order=submit(false); String createKey=orders.findById(order.id()).orElseThrow().getIdempotencyKey();
        append(order,addition(order),createKey).expect(200); // Separate namespaces allow the same raw key.
        assertThatThrownBy(()->jdbc.update("insert into order_item_submissions(restaurant_id,order_id,actor_user_id,idempotency_key,request_hash,created_at) select restaurant_id,order_id,actor_user_id,idempotency_key,request_hash,created_at from order_item_submissions where restaurant_id=?",f.restaurant.getId())).hasStackTraceContaining("uq_order_item_submission_key");
        assertThat(orders.findById(order.id()).orElseThrow().getIdempotencyKey()).isEqualTo(createKey);
    }
    @Test void singleConfirmedOrderAcceptsAdditionAndCancelledHistoryDoesNotOccupySlot() throws Exception {
        var first=submit(true); first.expect(201); submit(true).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        var added=append(first,addition(first),key()); added.expect(200); assertThat(added.body.path("items").size()).isEqualTo(2);
        cancelSession().error(409,"TABLE_SESSION_IN_USE");
        call(post(path(first)+"/cancel"),Map.of("expectedVersion",added.version(),"reason","replacement")).expect(200);
        var replacement=submit(true); replacement.expect(201); assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(detail(first).body.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(call(get(ORDERS).param("tableSessionId",f.session.getId().toString()).param("size","1")).body.path("totalElements").asInt()).isEqualTo(2);
    }
    @Test void appendAndSessionCancelRaceRetainsRealOpenSeatingGuard() throws Exception {
        var order=submit(true); var results=race(()->append(order,addition(order),key()),this::cancelSession);
        results.get(0).expect(200); results.get(1).error(409,"TABLE_SESSION_IN_USE"); assertThat(sessions.findById(f.session.getId()).orElseThrow().getStatus()).isEqualTo(TableSessionStatus.OPEN);
    }

    private Result submit(boolean dine) throws Exception { var request=body(dine); request.put("submissionMode","SUBMIT"); return postCreate(request,key()); }
    private Map<String,Object> addition(Result order) { var request=new LinkedHashMap<String,Object>(); request.put("expectedVersion",order.version()); request.put("submissionMode","SUBMIT"); request.put("items",List.of(lineRequest(f.item.getId(),"1",null))); return request; }
    private Result append(Result order,Object request,String key) throws Exception { return call(post(path(order)+"/items").header("Idempotency-Key",key),request); }
    private long ledgerCount() { return jdbc.queryForObject("select count(*) from order_item_submissions where restaurant_id=?",Long.class,f.restaurant.getId()); }

    @Test void allServingStatusesAndFinancialStatesOccupySlotUntilTerminal() throws Exception {
        var first=create(true); first.expect(201);
        for(String status:List.of("OPEN","CONFIRMED","PREPARING","READY","SERVED")) {
            jdbc.update("update orders set status=?,payment_status='PAID',paid_amount=1 where id=?",status,first.id());
            create(true).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        }
        jdbc.update("update orders set status='COMPLETED' where id=?",first.id());
        var next=create(true); next.expect(201); assertThat(next.id()).isNotEqualTo(first.id());
        assertThat(orderCount()).isEqualTo(2); assertThat(events("ORDER_CREATED")).isEqualTo(2);
        // Serving-slot availability is not the financial seating cancellation guard.
        call(post(path(next)+"/cancel"),Map.of("expectedVersion",next.version(),"reason","void")).expect(200);
        cancelSession().error(409,"TABLE_SESSION_IN_USE");
    }
    @Test void sameAndDifferentKeyDineInCreateRacesHaveOneServingOrder() throws Exception {
        String key=key(); var request=body(true);
        var same=race(()->postCreate(request,key),()->postCreate(request,key));
        assertThat(same).extracting(Result::status).containsExactlyInAnyOrder(201,200);
        assertThat(same.get(0).id()).isEqualTo(same.get(1).id());
        var order=same.get(0);
        call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","next race")).expect(200);
        var different=race(()->postCreate(request,key()),()->postCreate(request,key()));
        assertThat(different).extracting(Result::status).containsExactlyInAnyOrder(201,409);
        different.stream().filter(r->r.status==409).forEach(r->r.error(409,"TABLE_SESSION_HAS_SERVING_ORDER"));
        assertThat(events("ORDER_CREATED")).isEqualTo(2);
    }
    @Test void creationReplayReturnsCancelledOriginalEvenWhenReplacementIsServing() throws Exception {
        String key=key(); var request=body(true); var original=postCreate(request,key); original.expect(201);
        var cancelled=call(post(path(original)+"/cancel"),Map.of("expectedVersion",original.version(),"reason","replacement"));
        cancelled.expect(200); var replacement=submit(true); replacement.expect(201);
        jdbc.update("update items set is_active=false where id=?",f.item.getId());
        var replay=postCreate(request,key); replay.expect(200); assertThat(replay.replay).isEqualTo("true");
        assertThat(replay.id()).isEqualTo(original.id()); assertThat(replay.body).isEqualTo(cancelled.body);
        assertThat(events("ORDER_CREATED")).isEqualTo(2);
    }
    @Test void nativeServingUniqueConflictMapsBusinessCodeAndRollsBack() throws Exception {
        var original=create(true); original.expect(201);
        assertThatThrownBy(()->jdbc.update("""
            insert into orders(restaurant_id,order_code,table_session_id,service_type,source_channel,status,currency_code,created_at,updated_at,idempotency_key,request_hash)
            select restaurant_id,'NATIVE-DUP',table_session_id,service_type,source_channel,status,currency_code,created_at,updated_at,'native-other-key',request_hash from orders where id=?
            """,original.id())).hasStackTraceContaining("ux_order_serving_session");
        doNothing().when(serving).requireAvailable(eq(f.restaurant.getId()),eq(ServiceType.DINE_IN),eq(f.session.getId()));
        create(true).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        assertThat(orderCount()).isEqualTo(1); assertThat(events("ORDER_CREATED")).isEqualTo(1);
    }
    @Test void activeOrderLookupReturnsZeroOneAndPreservesSnapshotsWithoutWrites() throws Exception {
        String route="/api/v1/table-sessions/"+f.session.getId()+"/active-order";
        var empty=call(get(route)); empty.expect(204);
        var order=create(true); order.expect(201);
        jdbc.update("update items set name='New name',sale_price=90000,is_active=false where id=?",f.item.getId());
        var active=call(get(route)); active.expect(200); assertThat(active.body).isEqualTo(order.body);
        assertThat(events("ORDER_CREATED")).isEqualTo(1); assertThat(events("ORDER_UPDATED")).isZero();
        call(post(path(order)+"/cancel"),Map.of("expectedVersion",order.version(),"reason","done")).expect(200);
        call(get(route)).expect(204);
        jdbc.update("update table_sessions set status='CANCELLED',closed_at=opened_at,closed_by=opened_by,cancel_reason='fixture' where id=?",f.session.getId());
        call(get(route)).expect(204); // Read ownership is not live seating eligibility.
    }
    @Test void activeOrderLookupRequiresViewFeatureAndTenantOwnership() throws Exception {
        String route="/api/v1/table-sessions/"+f.session.getId()+"/active-order";
        callAs(get(route),null,actor(Set.of("ORDER_CREATE"))).error(403,"FORBIDDEN");
        var own=f; f=fixture(true); var foreign=f; f=own;
        call(get("/api/v1/table-sessions/"+foreign.session.getId()+"/active-order")).error(404,"TABLE_SESSION_NOT_FOUND");
        call(get("/api/v1/table-sessions/"+UUID.randomUUID()+"/active-order")).error(404,"TABLE_SESSION_NOT_FOUND");
        setFeatures(List.of("TABLE_MANAGEMENT")); call(get(route)).error(403,"FEATURE_NOT_ENTITLED");
        assertThat(mvc.perform(get(route)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }
    @Test void createRejectsServingSlotBeforeMenuOrAnyWriter() throws Exception {
        create(true).expect(201); clearInvocations(orders,menuItems,histories,audit);
        create(true).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        verify(menuItems,never()).findAllByRestaurantIdAndIdIn(any(),any());
        verify(orders,never()).save(any()); verify(histories,never()).saveAll(any());
        verify(audit,never()).record(any(),any(),any(),any(),any(),any(),any(),any());
    }
    @Test void preparationFailureDoesNotWriteManagedRootLinesOrLedger() throws Exception {
        var order=create(false); var other=menuItem(f.restaurant.getId(),null,"Other",BigDecimal.ONE);
        jdbc.update("update items set is_active=false where id=?",f.item.getId());
        var request=addition(order); request.put("items",List.of(lineRequest(other.getId(),"1",null)));
        clearInvocations(orders,histories,audit,submissionLedger);
        append(order,request,key()).error(409,"ITEM_NOT_SELLABLE");
        verify(orders,never()).saveAndFlush(any()); verify(histories,never()).saveAll(any());
        verify(submissionLedger,never()).saveAndFlush(any());
        assertThat(detail(order).body).isEqualTo(order.body);
    }
    @Test void ledgerFailureRollsBackAppendConfirmationHistoryAndAudit() throws Exception {
        var order=create(false);
        doThrow(new IllegalStateException("ledger unavailable")).when(submissionLedger).saveAndFlush(any());
        append(order,addition(order),key()).expect(500);
        assertThat(detail(order).body).isEqualTo(order.body); assertThat(ledgerCount()).isZero();
        assertThat(events("ORDER_ITEMS_ADDED")).isZero(); assertThat(events("ORDER_CONFIRMED")).isZero();
        assertThat(history(order.lineId())).hasSize(1);
    }
    @Test void updateNoOpShortCircuitsAfterVersionAndPendingGuards() throws Exception {
        var order=create(false); clearInvocations(orders,audit,histories);
        var noOp=call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",2,"note"," no onions "));
        noOp.expect(200); assertThat(noOp.body).isEqualTo(order.body);
        verify(orders,never()).saveAndFlush(any()); assertThat(events("ORDER_ITEM_UPDATED")).isZero();
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",99,"quantity",2)).error(409,"CONCURRENT_ORDER_UPDATE");
        jdbc.update("update order_items set status='COOKING' where id=?",order.lineId());
        call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",2)).error(409,"ORDER_ITEM_NOT_EDITABLE");
    }
    @Test void updateUsesHistoricalPriceEvenWhenCatalogBecomesUnavailable() throws Exception {
        var order=create(false); jdbc.update("update items set sale_price=80000,is_active=false where id=?",f.item.getId());
        var changed=call(patch(path(order)+"/items/"+order.lineId()),Map.of("expectedVersion",order.version(),"quantity",3));
        changed.expect(200); assertThat(changed.body.path("items").get(0).path("unitPrice").decimalValue()).isEqualByComparingTo("50000");
        assertThat(changed.body.path("totalAmount").decimalValue()).isEqualByComparingTo("150000");
        assertThat(history(order.lineId())).hasSize(1);
    }

    @Test void tableSubmitOpensSessionAndConfirmsAtomicallyWithActualIdAndClock() throws Exception {
        var table=freeTable(); String qr=table.getQrToken(); var request=tableBody(table.getId()); request.put("guestCount",10); request.put("note","order-only note");
        var order=postCreate(request,key()); order.expect(201); assertThat(order.replay).isEqualTo("false");
        var session=sessions.findById(UUID.fromString(order.body.path("tableSessionId").asText())).orElseThrow();
        assertThat(session.getTableId()).isEqualTo(table.getId()); assertThat(session.getStatus()).isEqualTo(TableSessionStatus.OPEN);
        assertThat(session.getOpenedBy()).isEqualTo(f.user.getId()); assertThat(session.getOpenedAt()).isEqualTo(NOW);
        assertThat(session.getCreatedAt()).isEqualTo(NOW); assertThat(session.getNote()).isNull(); assertThat(session.getGuestCount()).isEqualTo((short)10);
        assertThat(session.getSessionCode()).matches("TS-[0-9a-f]{32}"); assertThat(sessionCount(table.getId())).isEqualTo(1);
        assertThat(order.body.path("status").asText()).isEqualTo("CONFIRMED"); assertThat(Instant.parse(order.body.path("confirmedAt").asText())).isEqualTo(NOW);
        assertThat(order.body.path("paymentStatus").asText()).isEqualTo("UNPAID"); assertThat(order.body.path("paidAmount").decimalValue()).isZero();
        assertThat(order.body.path("items").get(0).path("status").asText()).isEqualTo("PENDING"); assertThat(order.body.path("items").get(0).path("sentToKitchenAt").isNull()).isTrue();
        assertThat(order.body.toString()).doesNotContain("requestHash","idempotencyKey","costPrice","qrToken");
        assertThat(tables.findById(table.getId()).orElseThrow().getQrToken()).isEqualTo(qr);
        assertThat(tables.findById(table.getId()).orElseThrow().getVersion()).isZero();
        assertThat(history(order.lineId())).hasSize(1); assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1);
        assertThat(events("ORDER_CREATED")).isEqualTo(1); assertThat(events("ORDER_CONFIRMED")).isEqualTo(1);
        assertThat(call(get("/api/v1/table-sessions/"+session.getId()+"/active-order")).body).isEqualTo(order.body);
    }

    @Test void tableSubmitDefaultsNewSessionGuestsToOneAndReturnsStableNormalizedReplay() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId()); String key=key(); var first=postCreate(request,key); first.expect(201);
        assertThat(first.body.path("guestCount").asInt()).isEqualTo(1);
        request.put("items",List.of(lineRequest(f.item.getId(),"2.000"," no onions "))); request.put("tableSessionId",null);
        clearInvocations(sessions,seatingQuery,menuItems);
        var replay=postCreate(request,key); replay.expect(200); assertThat(replay.replay).isEqualTo("true"); assertThat(replay.body).isEqualTo(first.body);
        verify(seatingQuery,never()).resolveTable(any(),any()); verify(sessions,never()).saveAndFlush(any());
        verify(menuItems,never()).findAllByRestaurantIdAndIdIn(any(),any()); assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1);
    }

    @Test void tableSubmitReusesOpenSessionWithoutOpenPermissionOrUpdatingItsMetadata() throws Exception {
        jdbc.update("update table_sessions set guest_count=4,note='session-only note' where id=?",f.session.getId());
        var request=tableBody(f.table.getId()); var who=actor(Set.of("ORDER_CREATE","ORDER_UPDATE"));
        var order=callAs(post(ORDERS).header("Idempotency-Key",key()),request,who); order.expect(201);
        assertThat(order.body.path("tableSessionId").asText()).isEqualTo(f.session.getId().toString()); assertThat(order.body.path("guestCount").asInt()).isEqualTo(4);
        var stored=sessions.findById(f.session.getId()).orElseThrow(); assertThat(stored.getNote()).isEqualTo("session-only note"); assertThat(stored.getVersion()).isZero();
        assertThat(events("TABLE_SESSION_OPENED")).isZero(); assertThat(sessionCount(f.table.getId())).isEqualTo(1);
    }

    @Test void tableSubmitGuestOverrideAffectsOrderOnlyAndCanReplaceTerminalHistory() throws Exception {
        var first=create(true); first.expect(201);
        call(post(path(first)+"/cancel"),Map.of("expectedVersion",first.version(),"reason","replacement")).expect(200);
        var request=tableBody(f.table.getId()); request.put("guestCount",5);
        var next=postCreate(request,key()); next.expect(201); assertThat(next.body.path("guestCount").asInt()).isEqualTo(5);
        assertThat(sessions.findById(f.session.getId()).orElseThrow().getGuestCount()).isEqualTo((short)1);
        jdbc.update("update orders set status='COMPLETED',completed_at=created_at where id=?",next.id());
        var third=postCreate(tableBody(f.table.getId()),key()); third.expect(201); assertThat(third.id()).isNotEqualTo(next.id());
        assertThat(orderCount()).isEqualTo(3); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void tableSubmitRejectsEveryServingStatusBeforeMenuAndWrites() throws Exception {
        var first=create(true); first.expect(201); clearInvocations(menuItems,sessions,orders,audit);
        for(String state:List.of("OPEN","CONFIRMED","PREPARING","READY","SERVED")) {
            jdbc.update("update orders set status=? where id=?",state,first.id());
            postCreate(tableBody(f.table.getId()),key()).error(409,"TABLE_SESSION_HAS_SERVING_ORDER");
        }
        verify(menuItems,never()).findAllByRestaurantIdAndIdIn(any(),any()); verify(sessions,never()).saveAndFlush(any());
        verify(orders,never()).save(any()); assertThat(events("TABLE_SESSION_OPENED")).isZero(); assertThat(orderCount()).isEqualTo(1);
    }

    @Test void tableSubmitShapeAndGuestValidationNeverOpenSession() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId());
        request.put("submissionMode","DRAFT"); postCreate(request,key()).error(400,"TABLE_ORDER_REQUIRES_SUBMIT");
        request.remove("submissionMode"); postCreate(request,key()).error(400,"TABLE_ORDER_REQUIRES_SUBMIT");
        request.put("submissionMode",null); postCreate(request,key()).error(400,"TABLE_ORDER_REQUIRES_SUBMIT");
        request=tableBody(table.getId()); request.put("tableSessionId",f.session.getId()); postCreate(request,key()).error(400,"AMBIGUOUS_ORDER_SEATING");
        request=tableBody(table.getId()); request.remove("tableId"); postCreate(request,key()).error(400,"DINE_IN_SESSION_REQUIRED");
        request=tableBody(table.getId()); request.put("serviceType","TAKEAWAY"); postCreate(request,key()).error(400,"TAKEAWAY_TABLE_NOT_ALLOWED");
        for(int guests:List.of(0,-1,32768)) { request=tableBody(table.getId()); request.put("guestCount",guests); postCreate(request,key()).error(400,"VALIDATION_ERROR"); }
        assertThat(sessionCount(table.getId())).isZero(); assertThat(orderCount()).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void tableSubmitRejectsForeignMissingDeletedInactiveAndUnavailableArea() throws Exception {
        var own=f; f=fixture(true); var foreignTable=f.table; f=own;
        postCreate(tableBody(foreignTable.getId()),key()).error(404,"TABLE_NOT_FOUND");
        postCreate(tableBody(UUID.randomUUID()),key()).error(404,"TABLE_NOT_FOUND");
        var table=freeTable(); jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",table.getId());
        postCreate(tableBody(table.getId()),key()).error(409,"TABLE_INACTIVE");
        jdbc.update("update restaurant_tables set status='AVAILABLE',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),table.getId());
        postCreate(tableBody(table.getId()),key()).error(404,"TABLE_NOT_FOUND");
        jdbc.update("update restaurant_tables set deleted_at=null where id=?",table.getId());
        var area=new TableArea(); area.initialize(f.restaurant.getId(),NOW); area.setName("Hidden"); area.setActive(false); area=areas.saveAndFlush(area);
        jdbc.update("update restaurant_tables set area_id=? where id=?",area.getId(),table.getId());
        postCreate(tableBody(table.getId()),key()).error(409,"TABLE_AREA_INACTIVE");
        jdbc.update("update table_areas set is_active=true,deleted_at=? where id=?",java.sql.Timestamp.from(NOW),area.getId());
        postCreate(tableBody(table.getId()),key()).error(404,"TABLE_AREA_NOT_FOUND");
        assertThat(sessionCount(table.getId())).isZero(); assertThat(orderCount()).isZero();
    }

    @Test void tableSubmitRequiresCurrentOrderPermissionsFeaturesAndConditionalTableOpen() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId());
        for(Set<String> codes:List.of(Set.of("ORDER_CREATE","TABLE_OPEN"),Set.of("ORDER_UPDATE","TABLE_OPEN"),Set.of("ORDER_CREATE","ORDER_UPDATE")))
            callAs(post(ORDERS).header("Idempotency-Key",key()),request,actor(codes)).error(403,"FORBIDDEN");
        for(String missing:List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT")) {
            var features=new ArrayList<>(List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT","POS_QUICK_ORDER")); features.remove(missing); setFeatures(features);
            postCreate(request,key()).error(403,"FEATURE_NOT_ENTITLED");
        }
        setFeatures(List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT","POS_QUICK_ORDER"));
        assertThat(sessionCount(table.getId())).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
        callAs(post(ORDERS).header("Idempotency-Key",key()),request,actor(Set.of("ORDER_CREATE","ORDER_UPDATE","TABLE_OPEN"))).expect(201);
    }

    @Test void tableSubmitInvalidMenuAndOverflowStopBeforeSessionPersistence() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId());
        jdbc.update("update items set is_active=false where id=?",f.item.getId()); clearInvocations(sessions);
        postCreate(request,key()).error(409,"ITEM_NOT_SELLABLE"); verify(sessions,never()).saveAndFlush(any());
        jdbc.update("update items set is_active=true,sale_price=999999999999.99 where id=?",f.item.getId());
        postCreate(request,key()).error(400,"ORDER_AMOUNT_LIMIT_EXCEEDED"); verify(sessions,never()).saveAndFlush(any());
        assertThat(sessionCount(table.getId())).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void tableSubmitFailuresAfterSessionWriteRollBackSessionOrderAndAllAudits() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId());
        for(String action:List.of("TABLE_SESSION_OPENED","ORDER_CREATED","ORDER_CONFIRMED")) {
            doThrow(new IllegalStateException("fixture audit failure")).when(audit).record(eq(f.restaurant.getId()),any(),eq(action),any(),any(),any(),any(),any());
            clearInvocations(sessions); postCreate(request,key()).expect(500); verify(sessions,times(1)).saveAndFlush(any());
            assertThat(sessionCount(table.getId())).isZero(); assertThat(orderCount()).isZero();
            for(String event:List.of("TABLE_SESSION_OPENED","ORDER_CREATED","ORDER_CONFIRMED")) assertThat(events(event)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from order_items where restaurant_id=?",Long.class,f.restaurant.getId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from order_item_status_history where restaurant_id=?",Long.class,f.restaurant.getId())).isZero();
            reset(audit);
        }
    }

    @Test void tableSubmitFailureKeepsExistingSessionCompletelyUnchanged() throws Exception {
        var before=json.valueToTree(jdbc.queryForMap("select * from table_sessions where id=?",f.session.getId()));
        doThrow(new IllegalStateException("fixture confirmation failure")).when(audit).record(eq(f.restaurant.getId()),any(),eq("ORDER_CONFIRMED"),any(),any(),any(),any(),any());
        var request=tableBody(f.table.getId()); request.put("guestCount",7); request.put("note","order only"); postCreate(request,key()).expect(500);
        JsonNode after=json.valueToTree(jdbc.queryForMap("select * from table_sessions where id=?",f.session.getId()));
        assertThat(after).isEqualTo(before);
        assertThat(orderCount()).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void simultaneousTableCreatesWithDifferentKeysHaveOneServingOrderAndOneSession() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId());
        var result=race(()->postCreate(request,key()),()->postCreate(request,key()));
        assertThat(result).extracting(Result::status).containsExactlyInAnyOrder(201,409);
        result.stream().filter(r->r.status==409).forEach(r->r.error(409,"TABLE_SESSION_HAS_SERVING_ORDER"));
        assertThat(sessionCount(table.getId())).isEqualTo(1); assertThat(orderCount()).isEqualTo(1);
        assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1); assertThat(events("ORDER_CREATED")).isEqualTo(1);
    }

    @Test void simultaneousTableCreatesWithSameKeyHaveOneCommitAndOneReplay() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId()); String key=key();
        var result=race(()->postCreate(request,key),()->postCreate(request,key));
        assertThat(result).extracting(Result::status).containsExactlyInAnyOrder(201,200);
        assertThat(result).extracting(Result::replay).containsExactlyInAnyOrder("false","true");
        assertThat(result.get(0).body).isEqualTo(result.get(1).body); assertThat(sessionCount(table.getId())).isEqualTo(1);
        assertThat(events("ORDER_CONFIRMED")).isEqualTo(1);
    }

    @Test void tableSubmitAndManualOpenRaceReuseOneSessionWithoutDuplicateOpeningAudit() throws Exception {
        var table=freeTable(); var result=race(()->postCreate(tableBody(table.getId()),key()),
            ()->call(post("/api/v1/tables/"+table.getId()+"/sessions"),Map.of("expectedVersion",0)));
        result.get(0).expect(201); assertThat(result.get(1).status).isIn(201,409);
        assertThat(sessionCount(table.getId())).isEqualTo(1); assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1);
        assertThat(orderCount()).isEqualTo(1);
        if(result.get(1).status==201) assertThat(result.get(0).body.path("tableSessionId").asText()).isEqualTo(result.get(1).id().toString());
    }

    @Test void tableSubmitAndCancelRaceNeverAttachOrderToCancelledSession() throws Exception {
        var result=race(()->postCreate(tableBody(f.table.getId()),key()),this::cancelSession);
        result.get(0).expect(201); var old=sessions.findById(f.session.getId()).orElseThrow();
        String servingSession=result.get(0).body.path("tableSessionId").asText();
        if(result.get(1).status==200) {
            assertThat(old.getStatus()).isEqualTo(TableSessionStatus.CANCELLED); assertThat(servingSession).isNotEqualTo(old.getId().toString());
        } else {
            result.get(1).error(409,"TABLE_SESSION_IN_USE"); assertThat(old.getStatus()).isEqualTo(TableSessionStatus.OPEN);
            assertThat(servingSession).isEqualTo(old.getId().toString());
        }
        assertThat(jdbc.queryForObject("select count(*) from table_sessions where table_id=? and status='OPEN'",Long.class,f.table.getId())).isEqualTo(1);
    }

    @Test void tableSubmitAndPhysicalTableDisableOrDeleteAreSerialized() throws Exception {
        var permitted=new HashSet<>(ALL); permitted.add("TABLE_UPDATE"); var who=actor(permitted);
        for(boolean deletion:List.of(false,true)) {
            var table=freeTable(); String path="/api/v1/tables/"+table.getId();
            var result=race(()->postCreate(tableBody(table.getId()),key()),
                ()->deletion?callAs(delete(path).param("expectedVersion","0"),null,who)
                    :callAs(patch(path+"/status"),Map.of("expectedVersion",0,"status","INACTIVE"),who));
            assertThat(result.stream().filter(r->r.status>=200 && r.status<300).count()).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from table_sessions s join restaurant_tables t on t.id=s.table_id where t.id=? and s.status='OPEN' and (t.status<>'AVAILABLE' or t.deleted_at is not null)",Long.class,table.getId())).isZero();
        }
    }

    @Test void tableSubmitAndMenuMutationRespectTheSharedRestaurantLock() throws Exception {
        var table=freeTable(); var result=race(()->postCreate(tableBody(table.getId()),key()),()-> {
            new TransactionTemplate(transactions).executeWithoutResult(tx->{
                restaurants.findByIdForUpdate(f.restaurant.getId()).orElseThrow();
                jdbc.update("update items set is_active=false where id=?",f.item.getId());
            });
            return new Result(200,json.createObjectNode(),null);
        });
        if(result.get(0).status==201) {
            assertThat(sessionCount(table.getId())).isEqualTo(1); assertThat(detail(result.get(0)).body.path("items").get(0).path("itemName").asText()).isEqualTo("Pho");
        } else {
            result.get(0).error(409,"ITEM_NOT_SELLABLE"); assertThat(sessionCount(table.getId())).isZero(); assertThat(orderCount()).isZero();
        }
    }

    @Test void tableReplayReturnsOriginalCancelledOrderDespiteReplacementAndUnavailableLiveSeating() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId()); String key=key(); var first=postCreate(request,key); first.expect(201);
        var cancelled=call(post(path(first)+"/cancel"),Map.of("expectedVersion",first.version(),"reason","replace")); cancelled.expect(200);
        String session=first.body.path("tableSessionId").asText();
        call(post("/api/v1/table-sessions/"+session+"/cancel"),Map.of("expectedVersion",0,"reason","new seating")).expect(200);
        var replacement=postCreate(request,key()); replacement.expect(201); assertThat(replacement.id()).isNotEqualTo(first.id());
        assertThat(replacement.body.path("tableSessionId").asText()).isNotEqualTo(session);
        jdbc.update("update items set is_active=false,sale_price=90000 where id=?",f.item.getId());
        jdbc.update("update restaurant_tables set status='INACTIVE',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),table.getId());
        clearInvocations(seatingQuery,sessions,menuItems);
        var replay=postCreate(request,key); replay.expect(200); assertThat(replay.replay).isEqualTo("true"); assertThat(replay.body).isEqualTo(cancelled.body);
        verify(seatingQuery,never()).resolveTable(any(),any()); verify(sessions,never()).saveAndFlush(any()); verify(menuItems,never()).findAllByRestaurantIdAndIdIn(any(),any());
        assertThat(sessionCount(table.getId())).isEqualTo(2); assertThat(orderCount()).isEqualTo(2);
        assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(2); assertThat(events("ORDER_CREATED")).isEqualTo(2);
    }

    @Test void tableReplayNeedsCurrentOrderPermissionsAndFeatureButNotRevokedTableOpen() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId()); String key=key(); var first=postCreate(request,key); first.expect(201);
        var orderOnly=actor(Set.of("ORDER_CREATE","ORDER_UPDATE"));
        var replay=callAs(post(ORDERS).header("Idempotency-Key",key),request,orderOnly); replay.expect(200); assertThat(replay.body).isEqualTo(first.body);
        callAs(post(ORDERS).header("Idempotency-Key",key()),tableBody(freeTable().getId()),orderOnly).error(403,"FORBIDDEN");
        callAs(post(ORDERS).header("Idempotency-Key",key),request,actor(Set.of("ORDER_CREATE","TABLE_OPEN"))).error(403,"FORBIDDEN");
        for(String missing:List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT")) {
            var grants=new ArrayList<>(List.of("ORDER_MANAGEMENT","TABLE_MANAGEMENT","POS_QUICK_ORDER")); grants.remove(missing); setFeatures(grants);
            postCreate(request,key).error(403,"FEATURE_NOT_ENTITLED");
        }
        assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1); assertThat(orderCount()).isEqualTo(1);
    }

    @Test void tableCreateKeyRejectsOtherTableActorPayloadAndSeatingIntent() throws Exception {
        var table=freeTable(); var request=tableBody(table.getId()); String key=key(); var first=postCreate(request,key); first.expect(201);
        var changed=new LinkedHashMap<>(request); changed.put("tableId",freeTable().getId()); postCreate(changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        for(String field:List.of("guestCount","customerName","customerPhone","note","sourceChannel")) {
            changed=new LinkedHashMap<>(request); changed.put(field,field.equals("guestCount")?1:field.equals("sourceChannel")?"CASHIER":"different");
            postCreate(changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        }
        changed=new LinkedHashMap<>(request); changed.put("items",List.of(lineRequest(f.item.getId(),"3","no onions"))); postCreate(changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        changed=new LinkedHashMap<>(request); changed.remove("tableId"); changed.put("tableSessionId",first.body.path("tableSessionId").asText()); postCreate(changed,key).error(409,"IDEMPOTENCY_KEY_REUSED");
        var user=new User(); user.setRestaurantId(f.restaurant.getId()); user.setRoleId(f.user.getRoleId()); user.setName("Other"); user.setEmail(UUID.randomUUID()+"@example.test"); user.setPasswordHash("fixture"); user=users.saveAndFlush(user);
        callAs(post(ORDERS).header("Idempotency-Key",key),request,new CurrentUser(user.getId(),f.restaurant.getId(),user.getEmail(),"OWNER",ALL)).error(409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(orderCount()).isEqualTo(1); assertThat(events("TABLE_SESSION_OPENED")).isEqualTo(1);
    }

    @Test void tableCreateKeysRemainTenantScopedAndLegacyNullTableIdKeepsReplay() throws Exception {
        String key=key(); var first=postCreate(tableBody(freeTable().getId()),key); first.expect(201);
        f=fixture(false); var second=postCreate(tableBody(freeTable().getId()),key); second.expect(201); assertThat(second.id()).isNotEqualTo(first.id());
        var oldRequest=body(false); String oldKey=key(); var draft=postCreate(oldRequest,oldKey); draft.expect(201);
        oldRequest.put("tableId",null); oldRequest.put("submissionMode","DRAFT"); assertThat(postCreate(oldRequest,oldKey).body).isEqualTo(draft.body);
        var submit=body(false); submit.put("submissionMode","SUBMIT"); String submittedKey=key(); var sent=postCreate(submit,submittedKey); sent.expect(201);
        submit.put("tableId",null); assertThat(postCreate(submit,submittedKey).body).isEqualTo(sent.body);
    }

    @Test void tableWriterRequiresOuterTransactionAndRuntimeOpenPermission() throws Exception {
        var table=freeTable();
        assertThatThrownBy(()->seatingCommand.open(f.restaurant.getId(),table.getId(),(short)1,NOW,"127.0.0.1"))
            .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
        var who=actor(Set.of("ORDER_CREATE","ORDER_UPDATE"));
        var context=org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(who,null,who.permissions().stream().map(SimpleGrantedAuthority::new).toList()));
        org.springframework.security.core.context.SecurityContextHolder.setContext(context);
        try {
            assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->seatingCommand.open(f.restaurant.getId(),table.getId(),(short)1,NOW,"127.0.0.1")))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        } finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        assertThat(sessionCount(table.getId())).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void nativeOpenSessionConstraintViaOrderMapsStableConflictAndRollsBack() throws Exception {
        doReturn(new OrderSeatingQuery.Unoccupied(f.table.getId())).when(seatingQuery).resolveTable(f.restaurant.getId(),f.table.getId());
        doNothing().when(tableLifecycle).requireOpenable(any(),any(),anyBoolean());
        postCreate(tableBody(f.table.getId()),key()).error(409,"TABLE_ALREADY_OCCUPIED");
        assertThat(sessionCount(f.table.getId())).isEqualTo(1); assertThat(orderCount()).isZero(); assertThat(events("TABLE_SESSION_OPENED")).isZero();
    }

    @Test void legacySessionCreateNeverFallsBackToOpeningAndTakeawayNeverUsesSeating() throws Exception {
        jdbc.update("update table_sessions set status='CLOSED',closed_at=opened_at,closed_by=opened_by where id=?",f.session.getId());
        clearInvocations(sessions,seatingQuery);
        submit(true).error(409,"TABLE_SESSION_NOT_OPEN"); verify(sessions,never()).saveAndFlush(any());
        verify(seatingQuery,never()).resolveTable(any(),any());
        clearInvocations(seatingQuery);
        submit(false).expect(201); verifyNoInteractions(seatingQuery); assertThat(sessionCount(f.table.getId())).isEqualTo(1);
    }

    private RestaurantTable freeTable() {
        var table=new RestaurantTable(); table.initialize(f.restaurant.getId(),NOW); table.setCode("FREE-"+UUID.randomUUID().toString().substring(0,8));
        table.setName("Free table"); table.setQrToken("t"+UUID.randomUUID().toString().replace("-","")+"a".repeat(10));
        return tables.saveAndFlush(table);
    }
    private Map<String,Object> tableBody(UUID tableId) {
        var request=body(false); request.put("serviceType","DINE_IN"); request.put("sourceChannel","WAITER"); request.remove("tableSessionId");
        request.put("tableId",tableId); request.put("submissionMode","SUBMIT"); return request;
    }
    private long sessionCount(UUID tableId) { return jdbc.queryForObject("select count(*) from table_sessions where table_id=?",Long.class,tableId); }

    private Fixture fixture(boolean seated) {
        var r=new Restaurant(); r.setCode("ORD"+UUID.randomUUID().toString().replace("-","")); r.setName("Order fixture"); r=restaurants.saveAndFlush(r);
        var u=new User(); u.setRestaurantId(r.getId()); u.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId()); u.setName("Owner"); u.setEmail(UUID.randomUUID()+"@example.test"); u.setPasswordHash("fixture-only"); u=users.saveAndFlush(u);
        var s=new RestaurantSubscription(); s.setRestaurantId(r.getId()); s.setPackageId(packages.findByCode("PRO").orElseThrow().getId()); s.setStatus(SubscriptionStatus.ACTIVE);
        s.setStartAt(NOW.minusSeconds(60)); s.setEndAt(NOW.plusSeconds(3600)); s.setPriceAmount(BigDecimal.ZERO); s.setCurrencyCode("VND"); s.setFeatureSnapshot(snapshot(List.of("ORDER_MANAGEMENT","POS_QUICK_ORDER","TABLE_MANAGEMENT"))); s=subscriptions.saveAndFlush(s);
        var g=new ItemGroup(); g.initialize(r.getId(),NOW); g.setName("Main"); g=groups.saveAndFlush(g); var item=menuItem(r.getId(),g.getId(),"Pho",new BigDecimal("50000.00"));
        RestaurantTable table=null; TableSession session=null;
        if(seated) { table=new RestaurantTable(); table.initialize(r.getId(),NOW); table.setCode("B01"); table.setName("Table 1"); table.setQrToken("t"+UUID.randomUUID().toString().replace("-","")+"a".repeat(10)); table=tables.saveAndFlush(table);
            session=new TableSession(); session.initialize(r.getId(),NOW); session.setTableId(table.getId()); session.setSessionCode("SESSION"); session.setOpenedBy(u.getId()); session.setOpenedAt(NOW); session=sessions.saveAndFlush(session); }
        return new Fixture(r,u,s,g,item,table,session);
    }
    private Item menuItem(UUID tenant,UUID group,String name,BigDecimal price) { var i=new Item(); i.initialize(tenant,NOW); i.setGroupId(group); i.setName(name); i.setItemType(ItemType.MENU_ITEM); i.setBaseUnit("bowl"); i.setSalePrice(price); i.setCostPrice(new BigDecimal("12000.00")); return menuItems.saveAndFlush(i); }
    private Map<String,Object> snapshot(List<String> features) { return new SubscriptionFeatureSnapshot(1,"PRO",features.stream().map(code->new SubscriptionFeatureSnapshot.FeatureGrant(code,Map.of())).toList(),NOW).toMap(); }
    private void setFeatures(List<String> features) { var s=subscriptions.findById(f.subscription.getId()).orElseThrow(); s.setFeatureSnapshot(snapshot(features)); subscriptions.saveAndFlush(s); }
    private Map<String,Object> body(boolean dineIn) { var map=new LinkedHashMap<String,Object>(); map.put("serviceType",dineIn?"DINE_IN":"TAKEAWAY"); map.put("sourceChannel",dineIn?"WAITER":"CASHIER"); map.put("tableSessionId",dineIn?f.session.getId():null); map.put("items",List.of(lineRequest(f.item.getId(),"2","no onions"))); return map; }
    private Map<String,Object> lineRequest(UUID id,String quantity,String note) { var map=new LinkedHashMap<String,Object>(); map.put("itemId",id); map.put("quantity",new BigDecimal(quantity)); map.put("note",note); return map; }
    private String key() { return "key-"+UUID.randomUUID(); }
    private String path(Result order) { return ORDERS+"/"+order.id(); }
    private Result create(boolean dineIn) throws Exception { return postCreate(body(dineIn),key()); }
    private Result postCreate(Object body,String key) throws Exception { return call(post(ORDERS).header("Idempotency-Key",key),body); }
    private Result detail(Result order) throws Exception { return call(get(path(order))); }
    private Result cancelSession() throws Exception { return call(post("/api/v1/table-sessions/"+f.session.getId()+"/cancel"),Map.of("expectedVersion",0,"reason","cancel seating")); }
    private CurrentUser actor(Set<String> codes) { return new CurrentUser(f.user.getId(),f.restaurant.getId(),f.user.getEmail(),"OWNER",codes); }
    private Result call(MockHttpServletRequestBuilder request) throws Exception { return call(request,null); }
    private Result call(MockHttpServletRequestBuilder request,Object body) throws Exception { return callAs(request,body,actor(ALL)); }
    private Result callAs(MockHttpServletRequestBuilder request,Object body,CurrentUser who) throws Exception {
        if(body!=null) request.content(json.writeValueAsString(body));
        request.contentType(MediaType.APPLICATION_JSON).with(authentication(new UsernamePasswordAuthenticationToken(who,null,who.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
        var response=mvc.perform(request).andReturn().getResponse();
        return new Result(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()),response.getHeader("Idempotency-Replayed"));
    }
    private Result bearer(MockHttpServletRequestBuilder request,Object body,CurrentUser who) throws Exception {
        if(body!=null) request.content(json.writeValueAsString(body)); var response=mvc.perform(request.contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer "+jwt.createAccessToken(who))).andReturn().getResponse();
        return new Result(response.getStatus(),json.readTree(response.getContentAsString()),response.getHeader("Idempotency-Replayed"));
    }
    private List<OrderItemStatusHistory> history(UUID lineId) { return histories.findAllByRestaurantIdAndOrderItemIdOrderByChangeSequenceAsc(f.restaurant.getId(),lineId); }
    private long events(String action) { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",Long.class,f.restaurant.getId(),action); }
    private long orderCount() { return jdbc.queryForObject("select count(*) from orders where restaurant_id=?",Long.class,f.restaurant.getId()); }
    private List<Result> race(Callable<Result> a,Callable<Result> b) throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try { var first=pool.submit(()->{ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException(); return a.call();});
            var second=pool.submit(()->{ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException(); return b.call();});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown(); return List.of(first.get(30,TimeUnit.SECONDS),second.get(30,TimeUnit.SECONDS));
        } finally { start.countDown(); pool.shutdownNow(); }
    }
}
