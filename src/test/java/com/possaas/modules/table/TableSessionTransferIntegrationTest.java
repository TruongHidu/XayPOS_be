package com.possaas.modules.table;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
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
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
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

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false","app.bootstrap.super-admin.enabled=false","logging.level.root=WARN"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(TableSessionTransferIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TableSessionTransferIntegrationTest {
    private static final String SCHEMA="it_transfer_"+UUID.randomUUID().toString().replace("-","");
    private static final Instant NOW=Instant.parse("2026-10-11T00:00:00Z");
    private static final Set<String> ALL=Set.of("TABLE_TRANSFER","TABLE_VIEW","TABLE_OPEN","TABLE_CLOSE","ORDER_CREATE","ORDER_UPDATE","ORDER_VIEW");
    @TestConfiguration static class FixedTime { @Bean @Primary Clock transferClock() { return Clock.fixed(NOW,ZoneOffset.UTC); } }
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry p) {
        String base=System.getenv().getOrDefault("DB_URL","jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA):base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url); p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.flyway.default-schema",()->SCHEMA); p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants; @Autowired UserRepository users; @Autowired RoleRepository roles;
    @Autowired PackagePlanRepository packages; @Autowired RestaurantSubscriptionRepository subscriptions;
    @Autowired RestaurantTableRepository tables; @Autowired TableSessionRepository sessions; @Autowired TableAreaRepository areas;
    @Autowired PermissionService permissions;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    @Autowired com.possaas.infrastructure.security.JwtTokenService jwt;
    @MockitoSpyBean TableSessionTransferRepository transfers; @MockitoSpyBean AuditService audit;
    private Restaurant restaurant; private User user; private RestaurantSubscription subscription;
    private RestaurantTable source; private TableSession session;
    private record Result(int status,JsonNode body,String replay,String cache) {
        void expect(int expected) { assertThat(status).as(body.toString()).isEqualTo(expected); }
        void error(int expected,String code) { expect(expected); assertThat(body.path("code").asText()).isEqualTo(code); }
        UUID id() { return UUID.fromString(body.path("id").asText()); }
    }
    @BeforeEach void setup() {
        assertThat(jdbc.queryForObject("select current_schema()",String.class)).isEqualTo(SCHEMA);
        restaurant=new Restaurant(); restaurant.setCode("TR"+UUID.randomUUID().toString().replace("-","")); restaurant.setName("Transfer tenant"); restaurant=restaurants.saveAndFlush(restaurant);
        user=new User(); user.setRestaurantId(restaurant.getId()); user.setRoleId(roles.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow().getId());
        user.setName("Owner"); user.setEmail(UUID.randomUUID()+"@example.test"); user.setPasswordHash("fixture-only"); user=users.saveAndFlush(user);
        subscription=new RestaurantSubscription(); subscription.setRestaurantId(restaurant.getId()); subscription.setPackageId(packages.findByCode("PRO").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE); subscription.setStartAt(NOW.minusSeconds(60)); subscription.setEndAt(NOW.plusSeconds(3600)); subscription.setPriceAmount(BigDecimal.ZERO); subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(snapshot(List.of("TABLE_MANAGEMENT","ORDER_MANAGEMENT","POS_QUICK_ORDER","STAFF_MANAGEMENT","STAFF_PERMISSION"))); subscription=subscriptions.saveAndFlush(subscription);
        source=table(); session=seat(source);
    }
    @AfterAll void cleanup() {
        if(!SCHEMA.matches("it_transfer_[0-9a-f]{32}")) throw new IllegalStateException("Not a test schema");
        jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");
    }
    @Test void emptySessionMovesWithoutNewSessionOrConfigurationChanges() throws Exception {
        var target=table(); var before=sessionRow(); var sourceBefore=tableRow(source); var targetBefore=tableRow(target);
        var response=move(target,0,key()); response.expect(200); assertThat(response.replay).isEqualTo("false"); assertThat(response.cache).contains("no-store");
        assertThat(response.id()).isEqualTo(session.getId()); assertThat(response.body.path("tableId").asText()).isEqualTo(target.getId().toString());
        assertThat(response.body.path("version").asLong()).isEqualTo(1); assertThat(response.body.path("updatedAt").asText()).isEqualTo(NOW.toString());
        var after=sessionRow(); for(String field:List.of("table_id","updated_at","version")) {before.remove(field); after.remove(field);} assertThat(after).isEqualTo(before);
        assertThat(tableRow(source)).isEqualTo(sourceBefore); assertThat(tableRow(target)).isEqualTo(targetBefore);
        assertThat(call(get("/api/v1/tables/"+source.getId()),null,ALL).body.path("occupied").asBoolean()).isFalse();
        assertThat(call(get("/api/v1/tables/"+target.getId()),null,ALL).body.path("occupied").asBoolean()).isTrue();
        assertThat(count("table_sessions")).isEqualTo(1); assertThat(count("table_session_transfers")).isEqualTo(1); assertThat(events()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code in ('TABLE_SESSION_OPENED','TABLE_SESSION_CANCELLED','ORDER_UPDATED')",Long.class,restaurant.getId())).isZero();
    }
    @Test void confirmedOrderAndSnapshotsStayUnchangedThenAppendAndBothReplaysWork() throws Exception {
        // Create by tableId, the original request must remain replayable after moving its session.
        var originalRequest=orderBody(source); String createKey=key(); var order=call(post("/api/v1/orders").header("Idempotency-Key",createKey),originalRequest,ALL); order.expect(201);
        var append=Map.of("expectedVersion",order.body.path("version").asLong(),"submissionMode","SUBMIT","items",originalRequest.get("items")); String appendKey=key();
        var appended=call(post("/api/v1/orders/"+order.id()+"/items").header("Idempotency-Key",appendKey),append,ALL); appended.expect(200);
        var orderBefore=jdbc.queryForMap("select * from orders where id=?",order.id());
        var itemsBefore=jdbc.queryForList("select * from order_items where order_id=? order by id",order.id());
        long historyBefore=jdbc.queryForObject("select count(*) from order_item_status_history where restaurant_id=?",Long.class,restaurant.getId());
        var target=table(); move(target,0,key()).expect(200);
        assertThat(jdbc.queryForMap("select * from orders where id=?",order.id())).isEqualTo(orderBefore);
        assertThat(jdbc.queryForList("select * from order_items where order_id=? order by id",order.id())).isEqualTo(itemsBefore);
        assertThat(jdbc.queryForObject("select count(*) from order_item_status_history where restaurant_id=?",Long.class,restaurant.getId())).isEqualTo(historyBefore);
        var active=call(get(path()+"/active-order"),null,ALL); active.expect(200); assertThat(active.id()).isEqualTo(order.id());
        var createReplay=call(post("/api/v1/orders").header("Idempotency-Key",createKey),originalRequest,ALL); createReplay.expect(200); assertThat(createReplay.id()).isEqualTo(order.id()); assertThat(createReplay.replay).isEqualTo("true");
        var appendReplay=call(post("/api/v1/orders/"+order.id()+"/items").header("Idempotency-Key",appendKey),append,ALL); appendReplay.expect(200); assertThat(appendReplay.body).isEqualTo(appended.body);
        jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",source.getId()); // append must use the new table
        var next=Map.of("expectedVersion",appended.body.path("version").asLong(),"submissionMode","SUBMIT","items",originalRequest.get("items"));
        call(post("/api/v1/orders/"+order.id()+"/items").header("Idempotency-Key",key()),next,ALL).expect(200);
        assertThat(count("orders")).isEqualTo(1); assertThat(count("table_sessions")).isEqualTo(1);
    }
    @Test void laterServingStatusesAndHistoricalOrdersDoNotPreventTransfer() throws Exception {
        var order=call(post("/api/v1/orders").header("Idempotency-Key",key()),orderBody(source),ALL); order.expect(201);
        long version=0;
        for(String status:List.of("PREPARING","READY","SERVED","COMPLETED")) {
            jdbc.update("update orders set status=? where id=?",status,order.id());
            var before=jdbc.queryForMap("select * from orders where id=?",order.id());
            move(table(),version++,key()).expect(200); assertThat(jdbc.queryForMap("select * from orders where id=?",order.id())).isEqualTo(before);
        }
    }
    @Test void journeyHistoryUsesSnapshotsAndAppliedVersionWithFixedClock() throws Exception {
        var b=table(); var c=table(); String firstKey=key(); var firstBody=body(b,0,"  quiet  ");
        call(post(path()+"/transfer").header("Idempotency-Key",firstKey),firstBody,ALL).expect(200);
        move(c,1,key()).expect(200); var replay=call(post(path()+"/transfer").header("Idempotency-Key",firstKey),body(b,0,"quiet"),ALL);
        replay.expect(200); assertThat(replay.body.path("tableId").asText()).isEqualTo(c.getId().toString()); assertThat(replay.replay).isEqualTo("true");
        move(source,2,key()).expect(200);
        jdbc.update("update restaurant_tables set name='renamed',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),b.getId());
        var history=call(get(path()+"/transfers").param("size","2"),null,Set.of("TABLE_VIEW")); history.expect(200);
        assertThat(history.body.path("totalElements").asLong()).isEqualTo(3); assertThat(history.body.path("content")).hasSize(2);
        assertThat(history.body.path("content").get(0).path("sessionVersionAfter").asLong()).isEqualTo(3);
        var old=call(get(path()+"/transfers").param("size","2").param("page","1"),null,Set.of("TABLE_VIEW"));
        assertThat(old.body.path("content").get(0).at("/toTable/name").asText()).isEqualTo(b.getName());
        assertThat(old.body.toString()).doesNotContain("idempotencyKey","requestHash","qrToken","password");
        assertThat(events()).isEqualTo(3); assertThat(count("table_session_transfers")).isEqualTo(3);
        var current=jdbc.queryForMap("select * from table_sessions where id=?",session.getId());
        for(String status:List.of("CLOSED","CANCELLED")) {
            jdbc.update("update table_sessions set status=?,closed_at=opened_at,closed_by=opened_by,cancel_reason='done' where id=?",status,session.getId());
            var terminalReplay=call(post(path()+"/transfer").header("Idempotency-Key",firstKey),firstBody,ALL); terminalReplay.expect(200);
            assertThat(terminalReplay.body.path("status").asText()).isEqualTo(status); assertThat(terminalReplay.body.path("version").asLong()).isEqualTo(current.get("version"));
        }
        assertThat(events()).isEqualTo(3);
    }
    @Test void freshTransferRejectsLifecycleSameTargetAndStaleVersionWithoutHistory() throws Exception {
        move(source,0,key()).error(409,"SAME_TABLE_TRANSFER_NOT_ALLOWED"); move(table(),99,key()).error(409,"CONCURRENT_TABLE_UPDATE");
        for(String status:List.of("CLOSED","CANCELLED")) {
            jdbc.update("update table_sessions set status=?,closed_at=opened_at,closed_by=opened_by,cancel_reason='done' where id=?",status,session.getId());
            move(table(),0,key()).error(409,"INVALID_TABLE_SESSION_TRANSITION");
        }
        assertThat(count("table_session_transfers")).isZero(); assertThat(events()).isZero();
    }
    @Test void targetEligibilityAndForeignResourcesAreChecked() throws Exception {
        var target=table(); jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",target.getId()); move(target,0,key()).error(409,"TABLE_INACTIVE");
        jdbc.update("update restaurant_tables set status='AVAILABLE',deleted_at=? where id=?",java.sql.Timestamp.from(NOW),target.getId()); move(target,0,key()).error(404,"TABLE_NOT_FOUND");
        jdbc.update("update restaurant_tables set deleted_at=null where id=?",target.getId());
        var area=new TableArea(); area.initialize(restaurant.getId(),NOW); area.setName("Area"); area.setActive(false); area=areas.saveAndFlush(area);
        jdbc.update("update restaurant_tables set area_id=? where id=?",area.getId(),target.getId()); move(target,0,key()).error(409,"TABLE_AREA_INACTIVE");
        jdbc.update("update table_areas set is_active=true,deleted_at=? where id=?",java.sql.Timestamp.from(NOW),area.getId()); move(target,0,key()).error(409,"TABLE_AREA_INACTIVE");
        var busy=table(); seat(busy); move(busy,0,key()).error(409,"TABLE_ALREADY_OCCUPIED");
        var foreignRestaurant=new Restaurant(); foreignRestaurant.setCode("F"+UUID.randomUUID()); foreignRestaurant.setName("Foreign"); foreignRestaurant=restaurants.saveAndFlush(foreignRestaurant);
        var foreign=table(); jdbc.update("update restaurant_tables set restaurant_id=? where id=?",foreignRestaurant.getId(),foreign.getId()); move(foreign,0,key()).error(404,"TABLE_NOT_FOUND");
        var foreignSession=UUID.randomUUID(); jdbc.update("insert into table_sessions(id,restaurant_id,table_id,session_code,status,opened_by,opened_at,created_at,updated_at) values(?,?,?,'FOREIGN','OPEN',?,?,?,?)",foreignSession,foreignRestaurant.getId(),foreign.getId(),user.getId(),java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW));
        call(post("/api/v1/table-sessions/"+foreignSession+"/transfer").header("Idempotency-Key",key()),body(target,0,null),ALL).error(404,"TABLE_SESSION_NOT_FOUND");
        call(get("/api/v1/table-sessions/"+foreignSession+"/transfers"),null,ALL).error(404,"TABLE_SESSION_NOT_FOUND");
        assertThat(events()).isZero();
    }
    @Test void transferOnlyNeedsTransferFeatureAndPermissionAndDoesNotLimitCapacity() throws Exception {
        subscription.setFeatureSnapshot(snapshot(List.of("TABLE_MANAGEMENT"))); subscriptions.saveAndFlush(subscription);
        jdbc.update("update table_sessions set guest_count=20 where id=?",session.getId());
        jdbc.update("update restaurant_tables set status='INACTIVE' where id=?",source.getId());
        var target=table(); var result=call(post(path()+"/transfer").header("Idempotency-Key",key()),body(target,0,null),Set.of("TABLE_TRANSFER")); result.expect(200);
        assertThat(result.body.path("guestCount").asInt()).isEqualTo(20);
    }
    @Test void missingPermissionFeatureAndCurrentAuthAreCheckedEvenForReplay() throws Exception {
        var target=table(); String key=key(); var request=body(target,0,null); move(target,0,key).expect(200);
        for(Set<String> codes:List.of(Set.<String>of(),Set.of("TABLE_OPEN"),Set.of("TABLE_VIEW")))
            call(post(path()+"/transfer").header("Idempotency-Key",key),request,codes).error(403,"FORBIDDEN");
        subscription.setFeatureSnapshot(snapshot(List.of("ORDER_MANAGEMENT"))); subscriptions.saveAndFlush(subscription);
        call(post(path()+"/transfer").header("Idempotency-Key",key),request,ALL).error(403,"FEATURE_NOT_ENTITLED");
        var unauth=mvc.perform(post(path()+"/transfer").header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request))).andReturn().getResponse();
        assertThat(unauth.getStatus()).isEqualTo(401); assertThat(events()).isEqualTo(1);
    }
    @Test void fingerprintDetectsAllChangesAndBlankReasonNormalizes() throws Exception {
        var target=table(); String key=key(); move(target,0,key).expect(200);
        for(var request:List.of(body(table(),0,null),body(target,1,null),body(target,0,"different")))
            call(post(path()+"/transfer").header("Idempotency-Key",key),request,ALL).error(409,"IDEMPOTENCY_KEY_REUSED");
        call(post(path()+"/transfer").header("Idempotency-Key",key),body(target,0,"   "),ALL).expect(200);
        var otherSession=seat(table()); call(post("/api/v1/table-sessions/"+otherSession.getId()+"/transfer").header("Idempotency-Key",key),body(target,0,null),ALL).error(409,"IDEMPOTENCY_KEY_REUSED");
        var original=user; var other=new User(); other.setRestaurantId(restaurant.getId()); other.setRoleId(user.getRoleId()); other.setEmail(UUID.randomUUID()+"@example.test"); other.setPasswordHash("fixture"); other.setName("Other"); user=users.saveAndFlush(other);
        call(post(path()+"/transfer").header("Idempotency-Key",key),body(target,0,null),ALL).error(409,"IDEMPOTENCY_KEY_REUSED"); user=original;
        assertThat(events()).isEqualTo(1);
    }
    @Test void inputValidationAndEmptyHistoryContract() throws Exception {
        var target=table(); call(post(path()+"/transfer"),body(target,0,null),ALL).error(400,"INVALID_IDEMPOTENCY_KEY");
        for(String key:List.of(" ","a".repeat(101))) move(target,0,key).error(400,"INVALID_IDEMPOTENCY_KEY");
        for(var request:List.of(Map.of("expectedVersion",0),Map.of("targetTableId",target.getId()),body(target,-1,null),body(target,0,"x".repeat(1001))))
            call(post(path()+"/transfer").header("Idempotency-Key",key()),request,ALL).expect(400);
        var spoof=body(target,0,null); spoof.put("restaurantId",restaurant.getId()); call(post(path()+"/transfer").header("Idempotency-Key",key()),spoof,ALL).expect(400);
        var history=call(get(path()+"/transfers"),null,Set.of("TABLE_VIEW")); history.expect(200); assertThat(history.body.path("content")).isEmpty();
        for(String query:List.of("?page=-1","?size=0","?size=101")) call(get(path()+"/transfers"+query),null,ALL).expect(400);
        call(get(path()+"/transfers"),null,Set.of("TABLE_TRANSFER")).error(403,"FORBIDDEN");
    }
    @Test void auditAndHistoryFailureRollbackAndLeaveKeyAvailable() throws Exception {
        var target=table(); var before=sessionRow(); String key=key();
        doThrow(new IllegalStateException("test audit failure")).when(audit).record(any(),any(),eq("TABLE_SESSION_TRANSFERRED"),any(),any(),any(),any(),any());
        assertFailedMove(target,key); assertThat(sessionRow()).isEqualTo(before); assertThat(count("table_session_transfers")).isZero(); reset(audit);
        doThrow(new org.springframework.dao.DataIntegrityViolationException("fixture history persistence failure")).when(transfers).saveAndFlush(any(TableSessionTransfer.class));
        assertFailedMove(target,key); assertThat(sessionRow()).isEqualTo(before); assertThat(events()).isZero(); reset(transfers);
        move(target,0,key).expect(200); assertThat(count("table_session_transfers")).isEqualTo(1);
    }
    @Test void twoSessionsCompeteForTargetAndSameSessionCompetesWithStaleVersion() throws Exception {
        var target=table(); var other=seat(table()); var who=actor(ALL);
        var result=race(()->callAs(post(path()+"/transfer").header("Idempotency-Key",key()),body(target,0,null),who),
            ()->callAs(post("/api/v1/table-sessions/"+other.getId()+"/transfer").header("Idempotency-Key",key()),body(target,0,null),who));
        assertThat(result.stream().map(Result::status).toList()).containsExactlyInAnyOrder(200,409); assertUniqueOpen(); assertThat(count("table_session_transfers")).isEqualTo(1);
        var current=sessions.findById(session.getId()).orElseThrow(); var a=table(); var b=table();
        var next=race(()->move(a,current.getVersion(),key()),()->move(b,current.getVersion(),key()));
        assertThat(next.stream().map(Result::status).toList()).containsExactlyInAnyOrder(200,409);
        next.stream().filter(r->r.status==409).forEach(r->r.error(409,"CONCURRENT_TABLE_UPDATE")); assertUniqueOpen();
    }
    @Test void simultaneousRetryAppliesOnlyOnce() throws Exception {
        var target=table(); String key=key(); var result=race(()->move(target,0,key),()->move(target,0,key));
        assertThat(result.stream().map(Result::status).toList()).containsOnly(200); assertThat(result.stream().map(Result::replay).toList()).containsExactlyInAnyOrder("true","false");
        assertThat(events()).isEqualTo(1); assertThat(count("table_session_transfers")).isEqualTo(1);
    }
    @Test void transferRacesWithManualOpen() throws Exception {
        var target=table(); var results=race(()->move(target,0,key()),()->call(post("/api/v1/tables/"+target.getId()+"/sessions"),Map.of("guestCount",1,"expectedVersion",0),ALL));
        assertThat(results.stream().map(Result::status).toList()).contains(409); assertThat(results.stream().filter(r->r.status<300).count()).isEqualTo(1); assertUniqueOpen();
    }
    @Test void transferRacesWithSessionCancelAndUpdate() throws Exception {
        for(boolean cancel:List.of(false,true)) {
            if(cancel) { source=table(); session=seat(source); }
            var target=table();
            var result=race(()->move(target,0,key()),()->call(cancel?post(path()+"/cancel"):patch(path()),cancel?Map.of("expectedVersion",0,"reason","cancel"):Map.of("expectedVersion",0,"guestCount",3,"note","updated"),ALL));
            assertThat(result.stream().map(Result::status).toList()).containsExactlyInAnyOrder(200,409); assertUniqueOpen();
        }
    }
    @Test void transferRacesWithTableCreateAndAppendWithValidSerialResults() throws Exception {
        var target=table(); var createBody=orderBody(target);
        var results=race(()->move(target,0,key()),()->call(post("/api/v1/orders").header("Idempotency-Key",key()),createBody,ALL));
        assertThat(results.get(1).status).isEqualTo(201); assertThat(results.get(0).status).isIn(200,409); assertUniqueOpen();
        assertThat(jdbc.queryForObject("select count(*) from (select table_session_id from orders where restaurant_id=? and status in ('OPEN','CONFIRMED','PREPARING','READY','SERVED') group by table_session_id having count(*)>1) x",Long.class,restaurant.getId())).isZero();
        // Append and a later transfer of the order's session must both succeed without changing order ownership.
        var order=results.get(1); session=sessions.findById(UUID.fromString(order.body.path("tableSessionId").asText())).orElseThrow();
        var next=table(); long version=session.getVersion();
        var append=Map.of("expectedVersion",order.body.path("version").asLong(),"submissionMode","SUBMIT","items",createBody.get("items"));
        var updates=race(()->move(next,version,key()),()->call(post("/api/v1/orders/"+order.id()+"/items").header("Idempotency-Key",key()),append,ALL));
        assertThat(updates.stream().map(Result::status).toList()).containsOnly(200); assertUniqueOpen();
        assertThat(jdbc.queryForObject("select table_session_id from orders where id=?",UUID.class,order.id())).isEqualTo(session.getId());
    }
    @Test void postgresEnforcesAppendOnlyKeysVersionsAndTenantForeignKeys() throws Exception {
        var target=table(); move(target,0,key()).expect(200);
        assertThatThrownBy(()->jdbc.update("update table_session_transfers set reason='tamper' where restaurant_id=?",restaurant.getId())).hasStackTraceContaining("append-only");
        assertThatThrownBy(()->jdbc.update("delete from table_session_transfers where restaurant_id=?",restaurant.getId())).hasStackTraceContaining("append-only");
        String columns="restaurant_id,session_id,from_table_id,to_table_id,from_table_code,from_table_name,to_table_code,to_table_name,actor_user_id,reason,session_version_before,session_version_after,idempotency_key,request_hash,transferred_at";
        String select=columns;
        for(String constraint:List.of("uq_table_transfer_key","uq_table_transfer_version","fk_table_transfer_session","fk_table_transfer_target")) {
            String projection=select;
            if(!constraint.equals("uq_table_transfer_key")) projection=projection.replace("idempotency_key","'fresh-"+UUID.randomUUID()+"'");
            if(!constraint.equals("uq_table_transfer_version")) projection=projection.replace("session_version_after","session_version_after+10");
            if(constraint.equals("fk_table_transfer_session")) projection=projection.replace("session_id","'"+UUID.randomUUID()+"'::uuid");
            if(constraint.equals("fk_table_transfer_target")) projection=projection.replace("to_table_id","'"+UUID.randomUUID()+"'::uuid");
            String sql="insert into table_session_transfers("+columns+") select "+projection+" from table_session_transfers where restaurant_id=?";
            assertThatThrownBy(()->jdbc.update(sql,restaurant.getId())).hasStackTraceContaining(constraint);
        }
    }
    @Test void migrationGrantsAndExplicitDenyAreRespected() throws Exception {
        var granted=jdbc.queryForList("select r.code from roles r join role_permissions rp on rp.role_id=r.id join permissions p on p.id=rp.permission_id where p.code='TABLE_TRANSFER' order by r.code",String.class);
        assertThat(granted).containsExactly("MANAGER","OWNER","WAITER"); assertThat(permissions.getEffectivePermissionCodes(user)).contains("TABLE_TRANSFER");
        jdbc.update("insert into user_permissions(user_id,permission_id,effect) select ?,id,'DENY' from permissions where code='TABLE_TRANSFER'",user.getId());
        assertThat(permissions.getEffectivePermissionCodes(user)).doesNotContain("TABLE_TRANSFER");
        call(post(path()+"/transfer").header("Idempotency-Key",key()),body(table(),0,null),permissions.getEffectivePermissionCodes(user)).error(403,"FORBIDDEN");
        jdbc.update("update user_permissions set effect='GRANT' where user_id=?",user.getId()); assertThat(permissions.getEffectivePermissionCodes(user)).contains("TABLE_TRANSFER");
    }
    @Test void staffCatalogAndAssignmentSupportTransferWithoutHardcoding() throws Exception {
        var codes=Set.of("STAFF_PERMISSION_MANAGE");
        var catalog=call(get("/api/v1/staff/permissions"),null,codes); catalog.expect(200);
        assertThat(catalog.body.findValuesAsText("code")).contains("TABLE_TRANSFER");
        var staff=new User(); staff.setRestaurantId(restaurant.getId()); staff.setRoleId(roles.findByCodeAndRestaurantIdIsNull("CASHIER").orElseThrow().getId());
        staff.setEmail(UUID.randomUUID()+"@example.test"); staff.setName("Cashier"); staff.setPasswordHash("fixture"); staff=users.saveAndFlush(staff);
        String endpoint="/api/v1/staff/"+staff.getId()+"/permissions";
        var grant=call(put(endpoint),Map.of("grants",List.of("TABLE_TRANSFER"),"denies",List.of()),codes); grant.expect(200);
        assertThat(grant.body.path("effectivePermissions").toString()).contains("TABLE_TRANSFER");
        var deny=call(put(endpoint),Map.of("grants",List.of(),"denies",List.of("TABLE_TRANSFER")),codes); deny.expect(200);
        assertThat(deny.body.path("effectivePermissions").toString()).doesNotContain("TABLE_TRANSFER");
        assertThat(deny.body.path("denies").toString()).contains("TABLE_TRANSFER");
    }
    @Test void systemScopeCannotTransfer() throws Exception {
        var target=table(); var system=new CurrentUser(user.getId(),null,user.getEmail(),"SUPER_ADMIN",ALL);
        callAs(post(path()+"/transfer").header("Idempotency-Key",key()),body(target,0,null),system).expect(403);
    }
    @Test void currentBearerAccountAndRestaurantGuardsApplyBeforeReplay() throws Exception {
        var target=table(); String key=key(); move(target,0,key).expect(200);
        String token=jwt.createAccessToken(actor(ALL));
        jdbc.update("update users set is_active=false where id=?",user.getId());
        var request=post(path()+"/transfer").header("Authorization","Bearer "+token).header("Idempotency-Key",key).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body(target,0,null)));
        var response=mvc.perform(request).andReturn().getResponse(); assertThat(response.getStatus()).isEqualTo(401); assertThat(response.getContentAsString()).contains("ACCOUNT_INACTIVE");
        jdbc.update("update users set is_active=true where id=?",user.getId()); jdbc.update("update restaurants set status='SUSPENDED' where id=?",restaurant.getId());
        response=mvc.perform(request).andReturn().getResponse(); assertThat(response.getStatus()).isEqualTo(403); assertThat(response.getContentAsString()).contains("RESTAURANT_INACTIVE");
        assertThat(events()).isEqualTo(1);
    }
    @Test void keysAreTenantScopedAndAuditOmitsReplayAndQrSecrets() throws Exception {
        String key=key(); var target=table(); move(target,0,key).expect(200);
        String data=jdbc.queryForObject("select before_data::text || after_data::text from audit_logs where restaurant_id=? and action_code='TABLE_SESSION_TRANSFERRED'",String.class,restaurant.getId());
        assertThat(data).contains(source.getId().toString(),target.getId().toString(),"version","transferredAt").doesNotContain(key,source.getQrToken(),target.getQrToken(),"requestHash","password");
        setup(); move(table(),0,key).expect(200); assertThat(events()).isEqualTo(1);
    }
    @Test void realUniqueHistoryConflictRollsBackMovementAndHasStableError() throws Exception {
        var target=table(); var before=sessionRow();
        jdbc.update("insert into table_session_transfers(restaurant_id,session_id,from_table_id,to_table_id,from_table_code,from_table_name,to_table_code,to_table_name,actor_user_id,session_version_before,session_version_after,idempotency_key,request_hash,transferred_at) values(?,?,?,?,?,?,?,?,?,0,1,?,repeat('a',64),?)",
            restaurant.getId(),session.getId(),source.getId(),target.getId(),source.getCode(),source.getName(),target.getCode(),target.getName(),user.getId(),key(),java.sql.Timestamp.from(NOW));
        move(target,0,key()).error(409,"CONCURRENT_TABLE_UPDATE"); assertThat(sessionRow()).isEqualTo(before); assertThat(count("table_session_transfers")).isEqualTo(1); assertThat(events()).isZero();
    }
    @Test void historyUsesBoundedQueriesIndependentOfPageRowCount() throws Exception {
        var target=table(); long version=0;
        for(int i=0;i<8;i++) move(i%2==0?target:source,version++,key()).expect(200);
        var stats=emf.unwrap(org.hibernate.SessionFactory.class).getStatistics(); boolean enabled=stats.isStatisticsEnabled(); stats.setStatisticsEnabled(true);
        try {
            stats.clear(); call(get(path()+"/transfers").param("size","2"),null,Set.of("TABLE_VIEW")).expect(200); long small=stats.getPrepareStatementCount();
            stats.clear(); var response=call(get(path()+"/transfers").param("size","8"),null,Set.of("TABLE_VIEW")); response.expect(200); assertThat(response.body.path("content")).hasSize(8);
            assertThat(stats.getPrepareStatementCount()).isLessThanOrEqualTo(small+1);
        } finally { stats.setStatisticsEnabled(enabled); }
    }
    @Test void compositeFksRejectExistingForeignTenantIdentifiers() throws Exception {
        var target=table(); move(target,0,key()).expect(200);
        UUID ownTenant=restaurant.getId();
        setup(); UUID foreignSession=session.getId(), foreignTable=source.getId();
        String columns="restaurant_id,session_id,from_table_id,to_table_id,from_table_code,from_table_name,to_table_code,to_table_name,actor_user_id,reason,session_version_before,session_version_after,idempotency_key,request_hash,transferred_at";
        for(String field:List.of("session_id","from_table_id","to_table_id")) {
            String projection=columns.replace("idempotency_key","'foreign-"+UUID.randomUUID()+"'").replace("session_version_after","session_version_after+10")
                .replace(field,"'"+(field.equals("session_id")?foreignSession:foreignTable)+"'::uuid");
            String constraint=field.equals("session_id")?"fk_table_transfer_session":field.equals("from_table_id")?"fk_table_transfer_source":"fk_table_transfer_target";
            assertThatThrownBy(()->jdbc.update("insert into table_session_transfers("+columns+") select "+projection+" from table_session_transfers where restaurant_id=?",ownTenant)).hasStackTraceContaining(constraint);
        }
    }
    private void assertFailedMove(RestaurantTable target,String key) throws Exception {
        try { assertThat(move(target,0,key).status).isGreaterThanOrEqualTo(400); }
        catch(jakarta.servlet.ServletException expected) { assertThat(expected.getCause()).isNotNull(); }
    }
    private Map<String,Object> orderBody(RestaurantTable table) {
        UUID item=UUID.randomUUID(); jdbc.update("insert into items(id,restaurant_id,name,item_type,base_unit,sale_price,cost_price,availability_status,is_active,created_at,updated_at) values(?,?,'Pho','MENU_ITEM','bowl',50000,12000,'AVAILABLE',true,?,?)",item,restaurant.getId(),java.sql.Timestamp.from(NOW),java.sql.Timestamp.from(NOW));
        return Map.of("serviceType","DINE_IN","sourceChannel","WAITER","tableId",table.getId(),"submissionMode","SUBMIT","items",List.of(Map.of("itemId",item,"quantity",1)));
    }
    private RestaurantTable table() { var t=new RestaurantTable(); t.initialize(restaurant.getId(),NOW); t.setCode("T"+UUID.randomUUID()); t.setName("Table "+t.getCode()); t.setQrToken("t"+UUID.randomUUID().toString().replace("-","")+"a".repeat(10)); return tables.saveAndFlush(t); }
    private TableSession seat(RestaurantTable t) { var s=new TableSession(); s.initialize(restaurant.getId(),NOW.minusSeconds(100)); s.setTableId(t.getId()); s.setSessionCode("S"+UUID.randomUUID()); s.setOpenedBy(user.getId()); s.setOpenedAt(NOW.minusSeconds(100)); s.setGuestCount((short)2); s.setNote("unchanged"); return sessions.saveAndFlush(s); }
    private Map<String,Object> snapshot(List<String> codes) { return new SubscriptionFeatureSnapshot(1,"PRO",codes.stream().map(c->new SubscriptionFeatureSnapshot.FeatureGrant(c,Map.of())).toList(),NOW).toMap(); }
    private Map<String,Object> body(RestaurantTable target,long version,String reason) { var m=new LinkedHashMap<String,Object>(); m.put("targetTableId",target.getId()); m.put("expectedVersion",version); m.put("reason",reason); return m; }
    private String path() { return "/api/v1/table-sessions/"+session.getId(); }
    private String key() { return "transfer-"+UUID.randomUUID(); }
    private CurrentUser actor(Set<String> codes) { return new CurrentUser(user.getId(),restaurant.getId(),user.getEmail(),"OWNER",codes); }
    private Result move(RestaurantTable target,long version,String key) throws Exception { return call(post(path()+"/transfer").header("Idempotency-Key",key),body(target,version,null),ALL); }
    private Result call(MockHttpServletRequestBuilder req,Object body,Set<String> codes) throws Exception { return callAs(req,body,actor(codes)); }
    private Result callAs(MockHttpServletRequestBuilder req,Object body,CurrentUser actor) throws Exception {
        if(body!=null) req.content(json.writeValueAsString(body));
        req.contentType(MediaType.APPLICATION_JSON).with(authentication(new UsernamePasswordAuthenticationToken(actor,null,actor.permissions().stream().map(SimpleGrantedAuthority::new).toList())));
        var response=mvc.perform(req).andReturn().getResponse(); return new Result(response.getStatus(),response.getContentAsString().isBlank()?json.createObjectNode():json.readTree(response.getContentAsString()),response.getHeader("Idempotency-Replayed"),response.getHeader("Cache-Control"));
    }
    private Map<String,Object> sessionRow() { return jdbc.queryForMap("select * from table_sessions where id=?",session.getId()); }
    private Map<String,Object> tableRow(RestaurantTable t) { return jdbc.queryForMap("select * from restaurant_tables where id=?",t.getId()); }
    private long count(String table) { return jdbc.queryForObject("select count(*) from "+table+" where restaurant_id=?",Long.class,restaurant.getId()); }
    private long events() { return jdbc.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='TABLE_SESSION_TRANSFERRED'",Long.class,restaurant.getId()); }
    private void assertUniqueOpen() { assertThat(jdbc.queryForObject("select count(*) from (select table_id from table_sessions where restaurant_id=? and status='OPEN' group by table_id having count(*)>1) x",Long.class,restaurant.getId())).isZero(); }
    private List<Result> race(Callable<Result> a,Callable<Result> b) throws Exception {
        var ready=new CountDownLatch(2); var start=new CountDownLatch(1); var pool=Executors.newFixedThreadPool(2);
        try {
            var x=pool.submit(()->{ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException(); return a.call();});
            var y=pool.submit(()->{ready.countDown(); if(!start.await(10,TimeUnit.SECONDS))throw new TimeoutException(); return b.call();});
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue(); start.countDown(); return List.of(x.get(30,TimeUnit.SECONDS),y.get(30,TimeUnit.SECONDS));
        } finally {start.countDown(); pool.shutdownNow();}
    }
}
