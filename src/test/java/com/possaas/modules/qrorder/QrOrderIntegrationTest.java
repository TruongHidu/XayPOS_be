package com.possaas.modules.qrorder;

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
import com.possaas.modules.menu.repository.ItemRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties={"spring.jpa.show-sql=false", "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false", "logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(QrOrderIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class QrOrderIntegrationTest {
    static final Instant NOW=Instant.parse("2026-10-14T00:00:00Z");
    static final String SCHEMA="it_qr_order_"+UUID.randomUUID().toString().replace("-", "");
    static final String PUBLIC="/api/v1/public/qr-order", STAFF="/api/v1/qr-order-requests";
    static final Set<String> ALL=Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE","ORDER_CANCEL","ORDER_COMPLETE",
        "ORDER_MARK_SERVED","TABLE_OPEN","TABLE_CLOSE","TABLE_VIEW","TABLE_TRANSFER","TABLE_UPDATE","PAYMENT_CREATE","PAYMENT_CONFIRM","MENU_UPDATE","RESTAURANT_PROFILE_UPDATE");
    static final List<String> FEATURES=List.of("QR_MENU_VIEW","QR_TABLE_ORDER","QR_STATIC_ORDER","ORDER_MANAGEMENT",
        "TABLE_MANAGEMENT","POS_QUICK_ORDER","PAYMENT_MANAGEMENT");
    @TestConfiguration static class FixedTime { @Bean @Primary Clock qrClock() { return Clock.fixed(NOW,ZoneOffset.UTC); } }
    @DynamicPropertySource static void schema(DynamicPropertyRegistry p) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA)
            :base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url); p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA); p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants; @Autowired UserRepository users; @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages; @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired ItemRepository items; @Autowired RestaurantTableRepository tables; @Autowired TableSessionRepository sessions;
    @MockitoSpyBean AuditService audit;
    @Autowired com.possaas.modules.menu.repository.RecipeRepository recipes;
    @Autowired com.possaas.modules.menu.repository.RecipeItemRepository recipeItems;
    Restaurant restaurant; User user; RestaurantSubscription subscription; RestaurantTable table; Item item;
    record Reply(int status,JsonNode body,String replay,String cache) {
        UUID requestId() { return UUID.fromString(body.path("requestId").asText()); }
        UUID orderId() { return UUID.fromString(body.path("order").path("id").asText()); }
        long orderVersion() { return body.path("order").path("version").asLong(); }
        void expect(int code) { assertThat(status).as(body.toString()).isEqualTo(code); }
        void error(int code,String error) { expect(code); assertThat(body.path("code").asText()).isEqualTo(error); }
    }
    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
        restaurant=new Restaurant(); restaurant.setCode("QR"+UUID.randomUUID().toString().replace("-",""));
        restaurant.setName("QR restaurant"); restaurant.setPublicOrderToken(token()); restaurant=restaurants.saveAndFlush(restaurant);
        user=new User(); user.setRestaurantId(restaurant.getId()); user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Reviewer"); user.setEmail(UUID.randomUUID()+"@example.test"); user.setPasswordHash("fixture"); user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId()); subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setStartAt(NOW.minusSeconds(60)); subscription.setEndAt(NOW.plusSeconds(7200));
        subscription.setCurrencyCode("VND"); subscription.setPriceAmount(BigDecimal.ZERO); subscription.setFeatureSnapshot(snapshot(FEATURES));
        subscription=subscriptions.saveAndFlush(subscription);
        item=new Item(); item.initialize(restaurant.getId(),NOW); item.setItemType(ItemType.MENU_ITEM); item.setName("Pho");
        item.setBaseUnit("bowl"); item.setSalePrice(new BigDecimal("50000")); item.setCostPrice(new BigDecimal("10000")); item=items.saveAndFlush(item);
        table=freeTable();
    }
    @AfterEach void clearFailures() { reset(audit); }
    @AfterAll void cleanup() { if(!SCHEMA.matches("it_qr_order_[0-9a-f]{32}")) throw new IllegalStateException(); jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE"); }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void mintAndSubmitOnlyCreateGuestAndRequestNotFinancialState(boolean dine) throws Exception {
        String guest=mint(dine); var r=submit(guest,"key",payload()); r.expect(201);
        assertThat(r.cache).contains("no-store"); assertThat(r.body.path("status").asText()).isEqualTo("PENDING");
        assertThat(r.body.path("sourceChannel").asText()).isEqualTo(dine?"QR_TABLE":"QR_STATIC");
        assertThat(count("orders")).isZero(); assertThat(count("order_items")).isZero(); assertThat(count("table_sessions")).isZero();
        assertThat(count("order_item_status_history")).isZero(); assertThat(count("payments")).isZero();
        assertThat(count("users")).isEqualTo(1); assertThat(events("QR_ORDER_REQUEST_CREATED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select actor_user_id from audit_logs where restaurant_id=? and action_code='QR_ORDER_REQUEST_CREATED'",UUID.class,restaurant.getId())).isNull();
        String stored=jdbc.queryForObject("select token_hash from qr_guest_authorizations where restaurant_id=?",String.class,restaurant.getId());
        assertThat(stored).hasSize(64).isNotEqualTo(guest);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void approvalCreatesSubmittedOrderWithRealActorAndReplayDoesNotDuplicate(boolean dine) throws Exception {
        String guest=mint(dine); var request=submit(guest,"create",payload()); request.expect(201);
        var review=review(request); var a=approve(request,review,null,ALL); a.expect(200);
        assertThat(a.body.path("order").path("status").asText()).isEqualTo("CONFIRMED");
        assertThat(a.body.path("order").path("paymentStatus").asText()).isEqualTo("UNPAID");
        assertThat(a.body.path("order").path("items").get(0).path("status").asText()).isEqualTo("PENDING");
        assertThat(a.body.path("order").path("items").get(0).path("sentToKitchenAt").isNull()).isFalse();
        assertThat(jdbc.queryForObject("select created_by from orders where id=?",UUID.class,a.orderId())).isEqualTo(user.getId());
        assertThat(jdbc.queryForObject("select changed_by from order_item_status_history where restaurant_id=?",UUID.class,restaurant.getId())).isEqualTo(user.getId());
        assertThat(count("table_sessions")).isEqualTo(dine?1:0);
        if(dine) assertThat(jdbc.queryForObject("select opened_by from table_sessions where restaurant_id=?",UUID.class,restaurant.getId())).isEqualTo(user.getId());
        var again=approve(request,review,null,ALL); again.expect(200); assertThat(again.orderId()).isEqualTo(a.orderId());
        assertThat(count("orders")).isEqualTo(1); assertThat(count("order_items")).isEqualTo(1);
        assertThat(events("QR_ORDER_REQUEST_APPROVED")).isEqualTo(1);
        var receipt=guestGet(guest,request); receipt.expect(200);
        assertThat(receipt.body.path("approvedAmount").decimalValue()).isEqualByComparingTo("50000");
        for(String forbidden:List.of("order","approvedOrderId","approvedSessionId","customerPhone","paidAmount","totalAmount","guestToken","costPrice"))
            assertThat(receipt.body.has(forbidden)).as(forbidden).isFalse();
    }
    @Test void twoTakeawayRequestsNeverMergeAndDoNotNeedTableOrPosQuickFeature() throws Exception {
        features(List.of("QR_MENU_VIEW","QR_STATIC_ORDER","ORDER_MANAGEMENT")); String guest=mint(false);
        var r1=submit(guest,"a",payload()); var r2=submit(guest,"b",payload()); r1.expect(201); r2.expect(201);
        var a1=approve(r1,review(r1),null,Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE")); a1.expect(200);
        var a2=approve(r2,review(r2),null,Set.of("ORDER_VIEW","ORDER_CREATE","ORDER_UPDATE")); a2.expect(200);
        assertThat(a1.orderId()).isNotEqualTo(a2.orderId()); assertThat(count("orders")).isEqualTo(2);
        assertThat(count("table_sessions")).isZero();
        var staff=staff(post("/api/v1/orders").header("Idempotency-Key","staff"),Map.of("serviceType","TAKEAWAY","sourceChannel","CASHIER",
            "submissionMode","SUBMIT","items",List.of(Map.of("itemId",item.getId(),"quantity",1))),ALL);
        staff.expect(403);
    }
    @Test void callingMoreAppendsOnlyOwnLinesToServingRootAndKeepsOriginalSnapshots() throws Exception {
        String guest=mint(true); var first=submit(guest,"a",payload()); var a=approve(first,review(first),null,ALL); a.expect(200);
        UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        var before=jdbc.queryForList("select * from order_items where order_id=?",a.orderId());
        item.setSalePrice(new BigDecimal("70000")); items.saveAndFlush(item);
        var more=submit(guest,"b",payload()); more.expect(201);
        var b=approve(more,review(more),session,ALL); b.expect(200);
        assertThat(b.orderId()).isEqualTo(a.orderId()); assertThat(count("orders")).isEqualTo(1); assertThat(count("order_items")).isEqualTo(2);
        assertThat(jdbc.queryForList("select * from order_items where id=?",before.getFirst().get("id"))).isEqualTo(before);
        assertThat(b.body.path("order").path("totalAmount").decimalValue()).isEqualByComparingTo("120000");
        var receipt=guestGet(guest,more); assertThat(receipt.body.path("items").size()).isEqualTo(1);
        assertThat(receipt.body.path("approvedAmount").decimalValue()).isEqualByComparingTo("70000");
    }
    @Test void nullObservedRequestRequiresExplicitCurrentSessionSelectionAfterAnotherApproval() throws Exception {
        String guest=mint(true); var first=submit(guest,"a",payload()); var other=submit(guest,"b",payload());
        var old=review(other); var a=approve(first,review(first),null,ALL); a.expect(200);
        approve(other,old,null,ALL).error(409,"QR_REVIEW_CONTEXT_CHANGED");
        var fresh=review(other); approve(other,fresh,null,ALL).expect(409);
        UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        approve(other,fresh,session,ALL).expect(200);
    }
    @Test void priceChangeNeedsFreshReviewButGuestEstimatedAmountStaysUnchanged() throws Exception {
        String guest=mint(false); var r=submit(guest,"a",payload()); var old=review(r);
        item.setSalePrice(new BigDecimal("60000")); items.saveAndFlush(item);
        approve(r,old,null,ALL).error(409,"QR_REVIEW_CONTEXT_CHANGED"); assertThat(count("orders")).isZero();
        var a=approve(r,review(r),null,ALL); a.expect(200);
        var receipt=guestGet(guest,r); assertThat(receipt.body.path("estimatedAmount").decimalValue()).isEqualByComparingTo("50000");
        assertThat(receipt.body.path("approvedAmount").decimalValue()).isEqualByComparingTo("60000");
    }
    @Test void changedItemAvailabilityPreventsApprovalWithoutOpeningTable() throws Exception {
        var r=submit(mint(true),"a",payload()); var d=review(r);
        item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); items.saveAndFlush(item);
        approve(r,d,null,ALL).expect(409); assertThat(count("orders")).isZero(); assertThat(count("table_sessions")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"restaurantId","tableId","tableSessionId","orderId","sourceChannel","serviceType","unitPrice","status","createdBy"})
    void guestCannotSupplyTrustedFields(String field) throws Exception {
        var body=payload(); body.put(field,UUID.randomUUID().toString()); submit(mint(false),"a",body).expect(400);
        assertThat(count("qr_order_requests")).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"0","-1","20.001","0.0001","10000000000000000000"})
    void quantityBoundsCannotOverflowPrices(String qty) throws Exception {
        var body=payload(); body.put("items",List.of(Map.of("itemId",item.getId(),"quantity",new BigDecimal(qty))));
        submit(mint(false),"a",body).expect(400);
    }
    @Test void takeoverCannotReadOthersReceiptEvenSameRestaurantOrSupplyStaffBearerInsteadOfGrant() throws Exception {
        String a=mint(false),b=mint(false); var r=submit(a,"a",payload()); r.expect(201);
        guestGet(b,r).expect(404); perform(get(PUBLIC+"/requests/"+r.requestId()).header("Authorization","Bearer nonsense"),null).expect(400);
        perform(post(PUBLIC+"/requests").header("Idempotency-Key","b"),payload()).expect(400);
        var valid=perform(post(PUBLIC+"/requests").header("X-QR-Guest-Token",a).header("Idempotency-Key","c").header("Authorization","Bearer nonsense"),payload()); valid.expect(201);
    }
    @Test void idempotencyBoundToActorPayloadAndDoesNotRepriceReplay() throws Exception {
        String a=mint(false),b=mint(false); var r=submit(a,"same",payload()); r.expect(201);
        item.setSalePrice(new BigDecimal("99999")); items.saveAndFlush(item);
        var replay=submit(a,"same",payload()); replay.expect(200); assertThat(replay.replay).isEqualTo("true"); assertThat(replay.requestId()).isEqualTo(r.requestId());
        submit(b,"same",payload()).error(409,"IDEMPOTENCY_KEY_REUSED");
        var body=payload(); body.put("note","changed"); submit(a,"same",body).error(409,"IDEMPOTENCY_KEY_REUSED");
        assertThat(count("qr_order_requests")).isEqualTo(1);
    }
    @Test void submitReplayRetainsInitiallyNullObservedSessionAfterSeatingAppears() throws Exception {
        String guest=mint(true); var r=submit(guest,"same",payload()); approve(r,review(r),null,ALL).expect(200);
        var replay=submit(guest,"same",payload()); replay.expect(200); assertThat(replay.requestId()).isEqualTo(r.requestId());
        assertThat(jdbc.queryForObject("select observed_session_id from qr_order_requests where id=?",UUID.class,r.requestId())).isNull();
    }
    @Test void rejectedReplayIsIdempotentAndNeverOverwritesReasonOrReviewer() throws Exception {
        String guest=mint(false); var r=submit(guest,"a",payload()); var d=review(r);
        var body=Map.of("expectedRequestVersion",d.body.path("request").path("version").asLong(),"reason","Unavailable");
        staff(post(STAFF+"/"+r.requestId()+"/reject"),body,ALL).expect(200);
        staff(post(STAFF+"/"+r.requestId()+"/reject"),Map.of("expectedRequestVersion",0,"reason","Other reason"),ALL).expect(200);
        assertThat(events("QR_ORDER_REQUEST_REJECTED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select rejection_reason from qr_order_requests where id=?",String.class,r.requestId())).isEqualTo("Unavailable");
        approve(r,d,null,ALL).error(409,"INVALID_QR_REQUEST_TRANSITION"); submit(guest,"a",payload()).expect(200);
    }
    @Test void expiryIsEffectiveWithoutSchedulerAndQueueExcludesExpired() throws Exception {
        var r=submit(mint(false),"a",payload()); jdbc.update("update qr_order_requests set created_at=?,expires_at=? where id=?",Timestamp.from(NOW.minusSeconds(60)),Timestamp.from(NOW),r.requestId());
        var d=review(r); d.expect(200); assertThat(d.body.path("request").path("expired").asBoolean()).isTrue();
        assertThat(d.body.path("canApprove").asBoolean()).isFalse(); approve(r,d,null,ALL).expect(409);
        var queue=staff(get(STAFF),null,ALL); queue.expect(200); assertThat(queue.body.path("totalElements").asInt()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"ORDER_CREATE","ORDER_UPDATE"})
    void approvalRequiresBothPermissions(String missing) throws Exception {
        var r=submit(mint(false),"a",payload()); var reduced=new HashSet<>(ALL); reduced.remove(missing);
        approve(r,review(r),null,reduced).expect(403); assertThat(count("orders")).isZero();
    }
    @Test void readAndRejectNeedNoGuestEntitlementButCreatingAndApprovingDo() throws Exception {
        String guest=mint(false); var r=submit(guest,"a",payload()); var d=review(r);
        features(List.of("ORDER_MANAGEMENT")); review(r).expect(200); approve(r,d,null,ALL).expect(403);
        staff(post(STAFF+"/"+r.requestId()+"/reject"),Map.of("expectedRequestVersion",0,"reason","Disabled QR"),ALL).expect(200);
        perform(post(PUBLIC+"/restaurants/"+restaurant.getPublicOrderToken()+"/guest-access"),Map.of()).expect(403);
    }
    @Test void newTableApprovalRequiresTableOpenOnlyForOpening() throws Exception {
        String guest=mint(true); var r=submit(guest,"a",payload()); var reduced=new HashSet<>(ALL); reduced.remove("TABLE_OPEN");
        approve(r,review(r),null,reduced).expect(403); assertThat(count("table_sessions")).isZero();
        TableSession session=openSession(); var next=review(r); approve(r,next,session.getId(),reduced).expect(200);
    }
    @Test void approveRejectAndGuestEndpointsCannotCrossStaffBoundary() throws Exception {
        String guest=mint(false); var r=submit(guest,"a",payload());
        perform(get(STAFF).header("X-QR-Guest-Token",guest),null).expect(401);
        perform(post(STAFF+"/"+r.requestId()+"/approve").header("X-QR-Guest-Token",guest),Map.of()).expect(401);
        perform(post("/api/v1/orders").header("X-QR-Guest-Token",guest),Map.of()).expect(401);
        perform(post(PUBLIC+"/requests/"+r.requestId()+"/approve").header("X-QR-Guest-Token",guest),Map.of()).expect(401);
    }
    @Test void missingTargetAndTakeawayTargetUuidAreRejectedWithoutMutation() throws Exception {
        var r=submit(mint(false),"a",payload()); var d=review(r); var body=approvalBody(d,null); body.remove("targetSessionId");
        staff(post(STAFF+"/"+r.requestId()+"/approve"),body,ALL).expect(400);
        approve(r,d,UUID.randomUUID(),ALL).error(400,"TAKEAWAY_SESSION_NOT_ALLOWED"); assertThat(count("orders")).isZero();
    }
    @Test void checkoutCancelsOnlyRequestsThatObservedTheActualSession() throws Exception {
        String tableGuest=mint(true),takeGuest=mint(false);
        var initial=submit(tableGuest,"a",payload()); var unbound=submit(tableGuest,"unbound",payload());
        var approved=approve(initial,review(initial),null,ALL); approved.expect(200);
        var bound=submit(tableGuest,"bound",payload()); var take=submit(takeGuest,"take",payload()); bound.expect(201); take.expect(201);
        pay(approved).expect(201);
        assertThat(status(bound)).isEqualTo("CANCELLED"); assertThat(status(unbound)).isEqualTo("PENDING"); assertThat(status(take)).isEqualTo("PENDING");
        submit(tableGuest,"new",payload()).error(409,"QR_ORDER_CHECKOUT_IN_PROGRESS");
        approve(initial,review(initial),null,ALL).expect(200);
        approve(take,review(take),null,ALL).expect(200);
    }
    @Test void failedPaymentRollsBackPendingCancellationAndMoney() throws Exception {
        String guest=mint(true); var r=submit(guest,"a",payload()); var a=approve(r,review(r),null,ALL); a.expect(200);
        var pending=submit(guest,"b",payload()); pending.expect(201);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(eq(restaurant.getId()),any(),eq("PAYMENT_RECORDED"),any(),any(),any(),any(),any());
        var paid=pay(a); paid.expect(500); assertThat(status(pending)).isEqualTo("PENDING"); assertThat(count("payments")).isZero();
        assertThat(jdbc.queryForObject("select payment_status from orders where id=?",String.class,a.orderId())).isEqualTo("UNPAID");
    }
    @Test void successfulTableCompletionClosesSessionInvalidatesGrantButApprovedReplayStillWorks() throws Exception {
        String guest=mint(true); var r=submit(guest,"a",payload()); var old=review(r); var a=approve(r,old,null,ALL); a.expect(200);
        var paid=pay(a); paid.expect(201);
        var detail=staff(get("/api/v1/orders/"+a.orderId()),null,ALL); detail.expect(200);
        var served=staff(post("/api/v1/orders/"+a.orderId()+"/mark-served"),Map.of("expectedOrderVersion",detail.body.path("version").asLong()),ALL); served.expect(200);
        var complete=staff(post("/api/v1/orders/"+a.orderId()+"/complete"),Map.of("expectedVersion",served.body.path("version").asLong()),ALL); complete.expect(200);
        assertThat(jdbc.queryForObject("select status from table_sessions where restaurant_id=?",String.class,restaurant.getId())).isEqualTo("CLOSED");
        submit(guest,"after",payload()).error(401,"QR_GUEST_ACCESS_INVALID"); approve(r,old,null,ALL).expect(200);
        assertThat(count("orders")).isEqualTo(1);
    }
    @Test void tableQrRotationCancelsOnlyTableRequestsAndRevokesOldTableGuest() throws Exception {
        String guest=mint(true),take=mint(false); var t=submit(guest,"a",payload()); var r=submit(take,"b",payload());
        var rotated=staff(post("/api/v1/tables/"+table.getId()+"/qr/rotate"),Map.of("expectedVersion",table.getVersion()),ALL);
        rotated.expect(200); assertThat(status(t)).isEqualTo("CANCELLED"); assertThat(status(r)).isEqualTo("PENDING");
        submit(guest,"c",payload()).error(401,"QR_GUEST_ACCESS_INVALID"); submit(take,"d",payload()).expect(201);
    }
    @Test void sessionCancellationInvalidatesTableAccessWithoutTouchingRestaurantGuest() throws Exception {
        var s=openSession(); String tg=mint(true),rg=mint(false); var t=submit(tg,"a",payload()); var r=submit(rg,"b",payload());
        staff(post("/api/v1/table-sessions/"+s.getId()+"/cancel"),Map.of("expectedVersion",0,"reason","Customer left"),ALL).expect(200);
        assertThat(status(t)).isEqualTo("CANCELLED"); assertThat(status(r)).isEqualTo("PENDING"); submit(tg,"c",payload()).error(401,"QR_GUEST_ACCESS_INVALID");
    }
    @Test void approvalAuditFailureRollsBackLazySessionOrderHistoryAndRequest() throws Exception {
        var r=submit(mint(true),"a",payload()); var d=review(r);
        doThrow(new IllegalStateException("audit unavailable")).when(audit).record(eq(restaurant.getId()),any(),eq("QR_ORDER_REQUEST_APPROVED"),any(),any(),any(),any(),any());
        approve(r,d,null,ALL).expect(500); assertThat(status(r)).isEqualTo("PENDING");
        assertThat(count("orders")).isZero(); assertThat(count("table_sessions")).isZero(); assertThat(count("order_item_status_history")).isZero();
        assertThat(events("ORDER_CREATED")).isZero();
    }
    @Test void concurrentSubmitAndConcurrentApproveHaveExactlyOneWrite() throws Exception {
        String guest=mint(true); var submitted=race(()->submit(guest,"race",payload()),()->submit(guest,"race",payload()));
        assertThat(submitted).extracting(Reply::status).containsExactlyInAnyOrder(201,200); assertThat(count("qr_order_requests")).isEqualTo(1);
        var r=submitted.getFirst(); var d=review(r); var approvals=race(()->approve(r,d,null,ALL),()->approve(r,d,null,ALL));
        assertThat(approvals).extracting(Reply::status).containsExactly(200,200); assertThat(approvals.get(0).orderId()).isEqualTo(approvals.get(1).orderId());
        assertThat(count("orders")).isEqualTo(1); assertThat(count("table_sessions")).isEqualTo(1); assertThat(events("QR_ORDER_REQUEST_APPROVED")).isEqualTo(1);
    }
    @Test void crossTenantStaffCannotReviewOrRejectRequest() throws Exception {
        var r=submit(mint(false),"a",payload()); setup();
        staff(get(STAFF+"/"+r.requestId()),null,ALL).error(404,"QR_ORDER_REQUEST_NOT_FOUND");
    }
    @Test void databaseEnforcesGuestTenantAndTakeawayHasNoTableFields() throws Exception {
        var r=submit(mint(false),"a",payload());
        assertThatThrownBy(()->jdbc.update("update qr_order_requests set table_id=? where id=?",table.getId(),r.requestId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("update qr_order_requests set restaurant_id=? where id=?",UUID.randomUUID(),r.requestId())).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void itemGroupAndIngredientCannotBypassSellability() throws Exception {
        item.setItemType(ItemType.INGREDIENT); item.setGroupId(null); items.saveAndFlush(item);
        submit(mint(false),"a",payload()).expect(409); assertThat(count("qr_order_requests")).isZero();
    }

    @Test void unavailableQuoteIsAnUnapprovableReviewNotAnUnexpectedRollback() throws Exception {
        var r=submit(mint(false),"a",payload()); item.setAvailabilityStatus(AvailabilityStatus.OUT_OF_STOCK); items.saveAndFlush(item);
        var d=review(r); d.expect(200); assertThat(d.body.path("canApprove").asBoolean()).isFalse();
        assertThat(d.body.path("reasonCode").asText()).isEqualTo("ITEM_OUT_OF_STOCK"); assertThat(count("orders")).isZero();
    }
    @Test void transferInvalidatesBothPhysicalTablesWithoutChangingServingOrderOrRestaurantRequest() throws Exception {
        String sourceGuest=mint(true); var first=submit(sourceGuest,"a",payload()); var a=approve(first,review(first),null,ALL); a.expect(200);
        var sourcePending=submit(sourceGuest,"b",payload()); var target=freeTable();
        var current=table; table=target; String targetGuest=mint(true); var targetPending=submit(targetGuest,"c",payload()); table=current;
        var take=submit(mint(false),"d",payload()); UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        var moved=staff(post("/api/v1/table-sessions/"+session+"/transfer").header("Idempotency-Key","move"),
            Map.of("expectedVersion",0,"targetTableId",target.getId()),ALL); moved.expect(200);
        assertThat(status(sourcePending)).isEqualTo("CANCELLED"); assertThat(status(targetPending)).isEqualTo("CANCELLED");
        assertThat(status(take)).isEqualTo("PENDING"); assertThat(count("orders")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select table_session_id from orders where id=?",UUID.class,a.orderId())).isEqualTo(session);
        assertThat(jdbc.queryForObject("select version from restaurant_tables where id=?",Long.class,current.getId())).isZero();
        assertThat(jdbc.queryForObject("select version from restaurant_tables where id=?",Long.class,target.getId())).isZero();
        submit(sourceGuest,"e",payload()).error(401,"QR_GUEST_ACCESS_INVALID"); submit(targetGuest,"f",payload()).error(401,"QR_GUEST_ACCESS_INVALID");
    }
    @Test void restaurantTokenRotationOnlyCancelsOldRestaurantScopeAndNeverChangesApprovedOrder() throws Exception {
        String restaurantGuest=mint(false),tableGuest=mint(true); var first=submit(restaurantGuest,"a",payload());
        var a=approve(first,review(first),null,ALL); a.expect(200); var orderBefore=jdbc.queryForMap("select * from orders where id=?",a.orderId());
        var pending=submit(restaurantGuest,"b",payload()); var tablePending=submit(tableGuest,"c",payload());
        staff(post("/api/v1/restaurants/me/menu-link/rotate"),Map.of("expectedToken",restaurant.getPublicOrderToken()),ALL).expect(200);
        assertThat(status(pending)).isEqualTo("CANCELLED"); assertThat(status(tablePending)).isEqualTo("PENDING");
        assertThat(status(first)).isEqualTo("APPROVED"); assertThat(jdbc.queryForMap("select * from orders where id=?",a.orderId())).isEqualTo(orderBefore);
        submit(restaurantGuest,"d",payload()).error(401,"QR_GUEST_ACCESS_INVALID"); submit(tableGuest,"e",payload()).expect(201);
    }
    @Test void pendingPaymentIntentBlocksTableRequestsUntilCancelledWithoutBlockingTakeaway() throws Exception {
        String tableGuest=mint(true),takeGuest=mint(false); var r=submit(tableGuest,"a",payload()); var a=approve(r,review(r),null,ALL); a.expect(200);
        UUID payment=UUID.randomUUID();
        jdbc.update("""
            insert into payments(id,restaurant_id,order_id,payment_code,amount,currency_code,payment_method,status,
                confirmation_source,requested_at,created_at,updated_at)
            select ?,restaurant_id,id,?,total_amount,currency_code,'TRANSFER','PENDING','PROVIDER_WEBHOOK',?,?,? from orders where id=?
            """,payment,"INTENT-"+payment,Timestamp.from(NOW),Timestamp.from(NOW),Timestamp.from(NOW),a.orderId());
        submit(tableGuest,"b",payload()).error(409,"QR_ORDER_CHECKOUT_IN_PROGRESS"); submit(takeGuest,"c",payload()).expect(201);
        jdbc.update("update payments set status='CANCELLED' where id=?",payment);
        submit(tableGuest,"d",payload()).expect(201);
    }
    @Test void staffDraftMustBeSubmittedBeforeApprovingQrLines() throws Exception {
        var s=openSession();
        var draft=staff(post("/api/v1/orders").header("Idempotency-Key","draft"),Map.of("serviceType","DINE_IN","sourceChannel","WAITER",
            "tableSessionId",s.getId(),"items",List.of(Map.of("itemId",item.getId(),"quantity",1))),ALL); draft.expect(201);
        var r=submit(mint(true),"a",payload()); r.expect(201); var d=review(r); d.expect(200);
        assertThat(d.body.path("reasonCode").asText()).isEqualTo("QR_ORDER_DRAFT_NOT_SUBMITTED");
        approve(r,d,s.getId(),ALL).error(409,"QR_ORDER_DRAFT_NOT_SUBMITTED"); assertThat(count("order_items")).isEqualTo(1);
    }
    @Test void reviewHashIncludesSessionGuestCountAndVersion() throws Exception {
        var s=openSession(); var r=submit(mint(true),"a",payload()); var old=review(r); old.expect(200);
        jdbc.update("update table_sessions set guest_count=4,version=version+1 where id=?",s.getId());
        approve(r,old,s.getId(),ALL).error(409,"QR_REVIEW_CONTEXT_CHANGED");
        approve(r,review(r),s.getId(),ALL).expect(200);
    }
    @Test void malformedAndOversizedBodiesAreSanitizedBeforePersistence() throws Exception {
        String guest=mint(false);
        perform(post(PUBLIC+"/requests").header("X-QR-Guest-Token",guest).header("Idempotency-Key","huge")
            .content(" ".repeat(32769)),null).error(413,"QR_REQUEST_BODY_TOO_LARGE");
        perform(post(PUBLIC+"/tables/"+table.getQrToken()+"/guest-access"),Map.of("tableSessionId",UUID.randomUUID())).expect(400);
        assertThat(count("qr_order_requests")).isZero();
    }
    @Test void absentTakeawayCustomerNameAndTooManyLinesAreRejected() throws Exception {
        String guest=mint(false); var noName=payload(); noName.put("customerName","   "); submit(guest,"a",noName).expect(400);
        var excess=payload(); excess.put("items",Collections.nCopies(21,Map.of("itemId",item.getId(),"quantity",1))); submit(guest,"b",excess).expect(400);
    }
    @Test void staleRequestVersionReturnsStableConflictWithoutSuccessfulAudit() throws Exception {
        var r=submit(mint(false),"a",payload()); var d=review(r); var body=approvalBody(d,null); body.put("expectedRequestVersion",1);
        staff(post(STAFF+"/"+r.requestId()+"/approve"),body,ALL).error(409,"CONCURRENT_QR_REQUEST_UPDATE");
        assertThat(events("QR_ORDER_REQUEST_APPROVED")).isZero(); assertThat(count("orders")).isZero();
    }
    @Test void differentTakeawayRequestsCanBeApprovedConcurrentlyAsIndependentOrders() throws Exception {
        String guest=mint(false); var a=submit(guest,"a",payload()); var b=submit(guest,"b",payload());
        var da=review(a); var db=review(b); var result=race(()->approve(a,da,null,ALL),()->approve(b,db,null,ALL));
        assertThat(result).extracting(Reply::status).containsExactly(200,200); assertThat(result.get(0).orderId()).isNotEqualTo(result.get(1).orderId());
        assertThat(count("orders")).isEqualTo(2); assertThat(count("table_sessions")).isZero();
    }
    @Test void privateQueueAppliesCredentialValidityBeforePaginationAndReadsNeverWrite() throws Exception {
        String guest=mint(false); var stale=submit(guest,"a",payload()); var staleGrant=jdbc.queryForObject("select guest_authorization_id from qr_order_requests where id=?",UUID.class,stale.requestId());
        jdbc.update("update qr_guest_authorizations set revoked_at=? where id=?",Timestamp.from(NOW),staleGrant);
        var live=submit(mint(false),"b",payload());
        long requests=count("qr_order_requests"), audits=count("audit_logs");
        var queue=staff(get(STAFF).param("size","1"),null,ALL); queue.expect(200);
        assertThat(queue.body.path("totalElements").asLong()).isEqualTo(1); assertThat(queue.body.path("content").get(0).path("requestId").asText()).isEqualTo(live.requestId().toString());
        review(live).expect(200); assertThat(count("qr_order_requests")).isEqualTo(requests); assertThat(count("audit_logs")).isEqualTo(audits);
        assertThat(count("orders")).isZero(); assertThat(count("table_sessions")).isZero();
    }
    @Test void mvcRunsScopedDeserializerAndRejectsNumericStringsNullLinesAndLinePriceOverrides() throws Exception {
        String guest=mint(false);
        for(Object line:List.of(Map.of("itemId",item.getId(),"quantity","1"),Map.of("itemId",item.getId(),"quantity",1,"unitPrice",0))) {
            var body=payload(); body.put("items",List.of(line)); submit(guest,UUID.randomUUID().toString(),body).expect(400);
        }
        var body=payload(); body.put("items",Collections.singletonList(null)); submit(guest,"null-line",body).expect(400);
        assertThat(count("qr_order_requests")).isZero();
    }
    @Test void normalizedWhitespaceIsStableForMvcReplayAndSavedCustomerFields() throws Exception {
        String guest=mint(false); var padded=payload(); padded.put("customerName","  Guest  "); padded.put("note","  No onion  ");
        var first=submit(guest,"trim",padded); first.expect(201);
        var replay=submit(guest,"trim",payload()); replay.expect(200); assertThat(replay.requestId()).isEqualTo(first.requestId());
        assertThat(jdbc.queryForObject("select customer_name from qr_order_requests where id=?",String.class,first.requestId())).isEqualTo("Guest");
    }

    @ParameterizedTest @ValueSource(booleans={true,false})
    void recipeSnapshotIsChosenAtApprovalAndNeverAtGuestSubmitOrReview(boolean dine) throws Exception {
        var features=new ArrayList<>(FEATURES);features.add("RECIPE_MANAGEMENT");features(features);
        Recipe first=qrRecipe(1,"100");
        String guest=mint(dine);var request=submit(guest,"snapshot",payload());request.expect(201);var reviewed=review(request);reviewed.expect(200);
        assertThat(count("order_item_recipe_snapshots")).isZero();
        first.deactivate(NOW);recipes.saveAndFlush(first);Recipe second=qrRecipe(2,"200");
        var approved=approve(request,reviewed,null,ALL);approved.expect(200);
        assertThat(jdbc.queryForObject("select recipe_id from order_item_recipe_snapshots where restaurant_id=?",UUID.class,restaurant.getId())).isEqualTo(second.getId());
        assertThat(jdbc.queryForObject("select required_quantity from order_item_recipe_usages where restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("200");
        var replay=approve(request,reviewed,null,ALL);replay.expect(200);
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);assertThat(count("order_item_recipe_usages")).isEqualTo(1);
        assertThat(events("ORDER_ITEM_RECIPE_SNAPSHOTTED")).isEqualTo(1);
        var publicReceipt=guestGet(guest,request);publicReceipt.expect(200);
        assertThat(publicReceipt.body.toString()).doesNotContain("recipeId","recipeVersion","requiredQuantity","ingredientId");
    }
    @Test void qrAppendCapturesOnlyNewLinesUsingNewRecipeVersion() throws Exception {
        var codes=new ArrayList<>(FEATURES);codes.add("RECIPE_MANAGEMENT");features(codes);Recipe firstRecipe=qrRecipe(1,"100");
        String guest=mint(true);var first=submit(guest,"snapshot-a",payload());var a=approve(first,review(first),null,ALL);a.expect(200);
        UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        UUID oldLine=UUID.fromString(a.body.at("/order/items/0/id").asText());
        firstRecipe.deactivate(NOW);recipes.saveAndFlush(firstRecipe);Recipe next=qrRecipe(2,"200");
        var additional=submit(guest,"snapshot-b",payload());var reviewed=review(additional);var b=approve(additional,reviewed,session,ALL);b.expect(200);
        assertThat(b.orderId()).isEqualTo(a.orderId());
        assertThat(jdbc.queryForObject("select recipe_id from order_item_recipe_snapshots where order_item_id=?",UUID.class,oldLine)).isEqualTo(firstRecipe.getId());
        UUID newLine=UUID.fromString(b.body.at("/order/items/1/id").asText());
        assertThat(jdbc.queryForObject("select recipe_id from order_item_recipe_snapshots where order_item_id=?",UUID.class,newLine)).isEqualTo(next.getId());
        approve(additional,reviewed,session,ALL).expect(200);
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(2);assertThat(events("ORDER_ITEM_RECIPE_SNAPSHOTTED")).isEqualTo(2);
    }
    @Test void rejectedQrRequestDoesNotCaptureRecipe() throws Exception {
        var codes=new ArrayList<>(FEATURES);codes.add("RECIPE_MANAGEMENT");features(codes);qrRecipe(1,"100");
        var request=submit(mint(false),"snapshot-reject",payload());request.expect(201);var reviewed=review(request);reviewed.expect(200);
        staff(post(STAFF+"/"+request.requestId()+"/reject"),Map.of("expectedRequestVersion",reviewed.body.at("/request/version").asLong(),"reason","reject"),ALL).expect(200);
        assertThat(count("order_item_recipe_snapshots")).isZero();assertThat(events("ORDER_ITEM_RECIPE_SNAPSHOTTED")).isZero();
    }
    @Test void failedQrSnapshotRollsBackRequestOrderSessionAndCanBeRetried() throws Exception {
        var codes=new ArrayList<>(FEATURES);codes.add("RECIPE_MANAGEMENT");features(codes);qrRecipe(1,"100");
        var request=submit(mint(true),"snapshot-failure",payload());request.expect(201);var reviewed=review(request);
        doThrow(new IllegalStateException("forced snapshot audit failure")).when(audit).recordAll(anyList());
        approve(request,reviewed,null,ALL).expect(500);
        assertThat(status(request)).isEqualTo("PENDING");
        for(String name:List.of("orders","order_items","table_sessions","order_item_recipe_snapshots","order_item_recipe_usages"))assertThat(count(name)).as(name).isZero();
        assertThat(events("ORDER_ITEM_RECIPE_SNAPSHOTTED")).isZero();reset(audit);
        approve(request,reviewed,null,ALL).expect(200);assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);
    }
    @Test void failedQrAppendSnapshotPreservesExistingOrderAndRequestPending() throws Exception {
        var codes=new ArrayList<>(FEATURES);codes.add("RECIPE_MANAGEMENT");features(codes);qrRecipe(1,"100");
        String guest=mint(true);var first=submit(guest,"snapshot-existing",payload());var a=approve(first,review(first),null,ALL);a.expect(200);
        UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        var more=submit(guest,"snapshot-more",payload());var reviewed=review(more);
        doThrow(new IllegalStateException("forced append snapshot audit failure")).when(audit).recordAll(anyList());
        approve(more,reviewed,session,ALL).expect(500);reset(audit);
        assertThat(status(more)).isEqualTo("PENDING");assertThat(count("order_items")).isEqualTo(1);assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from orders where id=?",Long.class,a.orderId())).isEqualTo(a.orderVersion());
        approve(more,reviewed,session,ALL).expect(200);assertThat(count("order_item_recipe_snapshots")).isEqualTo(2);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void inventoryEnabledPendingReviewAndRejectNeverReserve(boolean dine) throws Exception {
        inventoryFixture("500");String guest=mint(dine);var request=submit(guest,"inventory-pending",payload());request.expect(201);
        var reviewed=review(request);reviewed.expect(200);
        assertThat(count("order_item_inventory_allocations")).isZero();assertThat(count("inventory_reservations")).isZero();
        assertThat(jdbc.queryForObject("select sum(reserved_quantity) from inventory_balances where restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("0");
        staff(post(STAFF+"/"+request.requestId()+"/reject"),Map.of("expectedRequestVersion",reviewed.body.at("/request/version").asLong(),"reason","reject"),ALL).expect(200);
        assertThat(count("order_item_recipe_snapshots")).isZero();assertThat(count("order_item_inventory_allocations")).isZero();
        assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isZero();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void inventoryQrApprovalReservesOnceWithoutExtraWarehousePermissions(boolean dine) throws Exception {
        inventoryFixture("500");String guest=mint(dine);var request=submit(guest,"inventory-create",payload());request.expect(201);var reviewed=review(request);reviewed.expect(200);
        var approved=approve(request,reviewed,null,ALL);approved.expect(200);approve(request,reviewed,null,ALL).expect(200);
        assertThat(count("order_item_inventory_allocations")).isEqualTo(1);assertThat(count("inventory_reservations")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from order_item_inventory_allocations where restaurant_id=?",String.class,restaurant.getId())).isEqualTo("RESERVED");
        assertThat(jdbc.queryForObject("select sum(reserved_quantity) from inventory_balances where restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("100");
        assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isEqualTo(1);
        var publicReceipt=guestGet(guest,request);publicReceipt.expect(200);
        assertThat(publicReceipt.body.toString()).doesNotContain("allocationId","reservationId","ingredientId","requiredQuantity","unitCost");
        assertThat(approved.body.path("order").toString()).doesNotContain("allocationId","reservationId","ingredients","unitCost");
    }
    @Test void inventoryQrAdditionalApprovalReservesOnlyNewLinesAndShortageLeavesRequestPending() throws Exception {
        UUID source=inventoryFixture("150");String guest=mint(true);var first=submit(guest,"inventory-first",payload());first.expect(201);
        var a=approve(first,review(first),null,ALL);a.expect(200);UUID session=UUID.fromString(a.body.path("approvedSessionId").asText());
        var next=submit(guest,"inventory-add",payload());next.expect(201);var reviewed=review(next);reviewed.expect(200);
        long audits=count("audit_logs");approve(next,reviewed,session,ALL).error(409,"INSUFFICIENT_AVAILABLE_STOCK");
        assertThat(status(next)).isEqualTo("PENDING");assertThat(count("order_items")).isEqualTo(1);assertThat(count("order_item_inventory_allocations")).isEqualTo(1);
        assertThat(count("order_item_recipe_snapshots")).isEqualTo(1);assertThat(count("audit_logs")).isEqualTo(audits);
        inventoryReceipt(source,"100");var fresh=review(next);fresh.expect(200);approve(next,fresh,session,ALL).expect(200);approve(next,fresh,session,ALL).expect(200);
        assertThat(count("orders")).isEqualTo(1);assertThat(count("inventory_reservations")).isEqualTo(2);assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("select sum(reserved_quantity) from inventory_balances where restaurant_id=?",BigDecimal.class,restaurant.getId())).isEqualByComparingTo("200");
    }
    @Test void inventoryQrShortageRollsBackNewSessionOrderAndSnapshotButCanBeApprovedAfterReceipt() throws Exception {
        UUID source=inventoryFixture("50");var request=submit(mint(true),"inventory-short",payload());request.expect(201);var reviewed=review(request);reviewed.expect(200);
        long audits=count("audit_logs");approve(request,reviewed,null,ALL).error(409,"INSUFFICIENT_AVAILABLE_STOCK");assertThat(status(request)).isEqualTo("PENDING");
        for(String name:List.of("orders","order_items","table_sessions","order_item_recipe_snapshots","order_item_recipe_usages","order_item_inventory_allocations","inventory_reservations"))assertThat(count(name)).as(name).isZero();
        assertThat(count("audit_logs")).isEqualTo(audits);inventoryReceipt(source,"100");approve(request,review(request),null,ALL).expect(200);
        assertThat(count("orders")).isEqualTo(1);assertThat(count("inventory_reservations")).isEqualTo(1);assertThat(events("ORDER_ITEM_INVENTORY_RESERVED")).isEqualTo(1);
    }
    UUID inventoryFixture(String physical) throws Exception {
        var codes=new ArrayList<>(FEATURES);codes.add("RECIPE_MANAGEMENT");codes.add("INVENTORY_MANAGEMENT");features(codes);
        Recipe snapshotRecipe=qrRecipe(1,"100");UUID source=jdbc.queryForObject("select ingredient_id from recipe_items where recipe_id=?",UUID.class,snapshotRecipe.getId());
        var permissions=new HashSet<>(ALL);permissions.add("WAREHOUSE_CREATE");
        staff(post("/api/v1/inventory/warehouses"),Map.of("code","MAIN","name","Main"),permissions).expect(201);
        inventoryReceipt(source,physical);return source;
    }
    void inventoryReceipt(UUID ingredientId,String quantity) throws Exception {
        UUID target=jdbc.queryForObject("select id from warehouses where restaurant_id=? and is_default=true",UUID.class,restaurant.getId());
        var permissions=new HashSet<>(ALL);permissions.add("INVENTORY_RECEIVE");
        var receipt=staff(post("/api/v1/inventory/stock-documents").header("Idempotency-Key",UUID.randomUUID().toString()),Map.of("documentType","RECEIPT","warehouseId",target,"items",List.of(Map.of("ingredientId",ingredientId,"quantity",new BigDecimal(quantity),"unitCost",10))),permissions);receipt.expect(201);
        staff(post("/api/v1/inventory/stock-documents/"+receipt.body.path("id").asText()+"/confirm"),Map.of("expectedVersion",receipt.body.path("version").asLong()),permissions).expect(200);
    }

    Recipe qrRecipe(int version,String quantity) {
        Item ingredient=new Item();ingredient.initialize(restaurant.getId(),NOW);ingredient.setName("Ingredient snapshot");ingredient.setItemType(ItemType.INGREDIENT);
        ingredient.setBaseUnit("g");ingredient.setTrackInventory(true);ingredient.setSalePrice(BigDecimal.ZERO);ingredient=items.saveAndFlush(ingredient);
        Recipe recipe=new Recipe();recipe.initialize(restaurant.getId(),item.getId(),version,BigDecimal.ONE,item.getBaseUnit(),NOW);recipe=recipes.saveAndFlush(recipe);
        RecipeItem line=new RecipeItem();line.initialize(restaurant.getId(),recipe.getId(),ingredient,new BigDecimal(quantity),BigDecimal.ZERO,NOW);recipeItems.saveAndFlush(line);return recipe;
    }

    String mint(boolean dine) throws Exception {
        var r=perform(post(PUBLIC+(dine?"/tables/"+table.getQrToken():"/restaurants/"+restaurant.getPublicOrderToken())+"/guest-access"),Map.of());
        r.expect(201); return r.body.path("guestToken").asText();
    }
    Reply submit(String guest,String key,Object body) throws Exception { return perform(post(PUBLIC+"/requests").header("X-QR-Guest-Token",guest).header("Idempotency-Key",key),body); }
    Reply guestGet(String guest,Reply r) throws Exception { return perform(get(PUBLIC+"/requests/"+r.requestId()).header("X-QR-Guest-Token",guest),null); }
    Reply review(Reply r) throws Exception { return staff(get(STAFF+"/"+r.requestId()),null,ALL); }
    Reply approve(Reply r,Reply d,UUID target,Set<String> permissions) throws Exception { return staff(post(STAFF+"/"+r.requestId()+"/approve"),approvalBody(d,target),permissions); }
    Map<String,Object> approvalBody(Reply d,UUID target) {
        var body=new LinkedHashMap<String,Object>(); body.put("expectedRequestVersion",d.body.path("request").path("version").asLong());
        body.put("expectedReviewContextHash",d.body.path("reviewContextHash").asText("0".repeat(64))); body.put("targetSessionId",target); return body;
    }
    Reply pay(Reply a) throws Exception { return staff(post("/api/v1/orders/"+a.orderId()+"/payments").header("Idempotency-Key","pay-"+UUID.randomUUID()),
        Map.of("expectedOrderVersion",a.orderVersion(),"paymentMethod","CASH","amount",a.body.path("order").path("totalAmount").decimalValue()),ALL); }
    String status(Reply r) { return jdbc.queryForObject("select status from qr_order_requests where id=?",String.class,r.requestId()); }
    Map<String,Object> payload() { var result=new LinkedHashMap<String,Object>(); result.put("customerName","Guest"); result.put("customerPhone","0900000000"); result.put("note","No onion"); result.put("items",List.of(Map.of("itemId",item.getId(),"quantity",1,"note","Hot"))); return result; }
    Reply staff(MockHttpServletRequestBuilder request,Object body,Set<String> permissions) throws Exception {
        return perform(request.with(authentication(auth(new CurrentUser(user.getId(),restaurant.getId(),user.getEmail(),"OWNER",permissions)))),body);
    }
    UsernamePasswordAuthenticationToken auth(CurrentUser actor) { return new UsernamePasswordAuthenticationToken(actor,null,actor.permissions().stream().map(SimpleGrantedAuthority::new).toList()); }
    Reply perform(MockHttpServletRequestBuilder request,Object body) throws Exception {
        if(body!=null) request.content(json.writeValueAsString(body));
        var exchange=mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andReturn();
        var r=exchange.getResponse();
        var responseBody=r.getContentAsString().isBlank()?json.createObjectNode():json.readTree(r.getContentAsString());
        if (r.getStatus()==500 && exchange.getResolvedException()!=null && responseBody instanceof com.fasterxml.jackson.databind.node.ObjectNode node) {
            var e=exchange.getResolvedException();
            node.put("testFailureLocation",e.getClass().getName()+" at "+Arrays.stream(e.getStackTrace()).filter(s->s.getClassName().startsWith("com.possaas.modules.qrorder")).toList());
        }
        return new Reply(r.getStatus(),responseBody,r.getHeader("Idempotency-Replayed"),r.getHeader("Cache-Control"));
    }
    RestaurantTable freeTable() { var t=new RestaurantTable(); t.initialize(restaurant.getId(),NOW); t.setCode(UUID.randomUUID().toString()); t.setName("Table"); t.setQrToken(token()); return tables.saveAndFlush(t); }
    String token() { return Base64.getUrlEncoder().withoutPadding().encodeToString(UUID.randomUUID().toString().substring(0,32).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    TableSession openSession() { var s=new TableSession(); s.initialize(restaurant.getId(),NOW); s.setTableId(table.getId()); s.setSessionCode(UUID.randomUUID().toString()); s.setOpenedBy(user.getId()); s.setOpenedAt(NOW); return sessions.saveAndFlush(s); }
    Map<String,Object> snapshot(List<String> features) { return new SubscriptionFeatureSnapshot(1,"PRO",features.stream().map(c->new SubscriptionFeatureSnapshot.FeatureGrant(c,Map.of())).toList(),NOW).toMap(); }
    void features(List<String> features) { subscription.setFeatureSnapshot(snapshot(features)); subscription=subscriptions.saveAndFlush(subscription); }
    long count(String tableName) { return jdbc.queryForObject("select count(*) from "+tableName+" where restaurant_id=?",Long.class,restaurant.getId()); }
    long events(String action) { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code=?",Long.class,restaurant.getId(),action); }
    List<Reply> race(Callable<Reply> a,Callable<Reply> b) throws Exception {
        var ready=new CountDownLatch(2); var go=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try { var x=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return a.call();}); var y=pool.submit(()->{ready.countDown();go.await(10,TimeUnit.SECONDS);return b.call();});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); go.countDown(); return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
        } finally { go.countDown(); pool.shutdownNow(); }
    }
}
