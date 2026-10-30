package com.possaas.modules.cashbook;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.*;
import com.possaas.modules.subscription.repository.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
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
import org.springframework.test.web.servlet.*;

@SpringBootTest(properties={"spring.jpa.show-sql=false","app.jobs.subscription-expiration.enabled=false","app.bootstrap.super-admin.enabled=false","logging.level.root=WARN","app.cors.allowed-origins=http://localhost:3000"})
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import(CashbookIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CashbookIntegrationTest {
    private static final String SCHEMA="it_cashbook_command_"+UUID.randomUUID().toString().replace("-","");
    private static final Instant NOW=Instant.parse("2026-10-29T12:00:00Z");
    private static final Set<String> PERMS=Set.of("CASHBOOK_VIEW","CASHBOOK_CREATE","CASHBOOK_VOID","INVENTORY_VIEW");
    private static final Set<String> FEATURES=Set.of("DETAIL_REPORT","INVENTORY_MANAGEMENT");
    private static final String BASE="/api/v1/cashbook/expenses";
    @TestConfiguration static class FixedTime { @Bean @Primary Clock cashbookClock(){return Clock.fixed(NOW,ZoneOffset.UTC);} }
    @DynamicPropertySource static void isolatedSchema(DynamicPropertyRegistry p) {
        String base=requiredEnv("DB_URL");String user=requiredEnv("DB_USERNAME"),password=requiredEnv("DB_PASSWORD");
        String url=base.contains("currentSchema=")?base.replaceAll("currentSchema=[^&]*","currentSchema="+SCHEMA):base+(base.contains("?")?"&":"?")+"currentSchema="+SCHEMA;
        p.add("spring.datasource.url",()->url);p.add("spring.datasource.username",()->user);p.add("spring.datasource.password",()->password);
        p.add("spring.flyway.schemas",()->SCHEMA);p.add("spring.flyway.default-schema",()->SCHEMA);p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    private static String requiredEnv(String name){var value=System.getenv(name);if(value==null||value.isBlank())throw new IllegalStateException(name+" must explicitly target test PostgreSQL");return value;}
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants; @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions; @MockitoSpyBean AuditService audit;
    @AfterAll void cleanup(){if(!SCHEMA.matches("it_cashbook_command_[0-9a-f]{32}"))throw new IllegalStateException("Invalid test schema");jdbc.execute("DROP SCHEMA \""+SCHEMA+"\" CASCADE");}

    @Test void manualExpenseUsesActualDateCurrencyAndReplayFingerprint() throws Exception {
        var f=fixture(FEATURES);var body=body("RENT","150.00",null,null);
        var created=create(f,body,"create-manual",PERMS,201);UUID id=UUID.fromString(created.path("id").asText());
        assertThat(created.path("currencyCode").asText()).isEqualTo("VND");assertThat(created.path("status").asText()).isEqualTo("POSTED");
        assertThat(created.path("expenseAt").asText()).isEqualTo(NOW.minusSeconds(86400).toString());
        assertThat(created.path("createdAt").asText()).isEqualTo(NOW.toString());
        jdbc.update("UPDATE restaurants SET currency_code='USD' WHERE id=?",f.restaurant().getId());
        var replay=create(f,body,"create-manual",PERMS,200);assertThat(replay.path("id").asText()).isEqualTo(id.toString());assertThat(replay.path("currencyCode").asText()).isEqualTo("VND");
        assertThat(count(f,"cashbook_expenses")).isEqualTo(1);assertThat(audits(f,"CASHBOOK_EXPENSE_RECORDED")).isEqualTo(1);
        var changed=new LinkedHashMap<>(body);changed.put("amount",new BigDecimal("160.00"));
        assertThat(create(f,changed,"create-manual",PERMS,409).path("code").asText()).isEqualTo("CASHBOOK_IDEMPOTENCY_KEY_REUSED");
    }

    @Test void linkedExpensesValidateSourceAndAllowMultipleRecordedPaymentsWithoutCap() throws Exception {
        var f=fixture(FEATURES);UUID receipt=receipt(f,"RECEIPT","CONFIRMED","VND");
        var before=sourceFacts(f,receipt);
        var first=create(f,body("INGREDIENT_PURCHASE","800.00","VND",receipt),"link-1",PERMS,201);
        create(f,body("INGREDIENT_PURCHASE","900.00","VND",receipt),"link-2",PERMS,201);
        assertThat(first.path("sourceCurrencyStatus").asText()).isEqualTo("KNOWN");
        assertThat(count(f,"cashbook_expenses")).isEqualTo(2);assertThat(sourceFacts(f,receipt)).isEqualTo(before);
        UUID unknown=receipt(f,"RECEIPT","CONFIRMED",null);
        var legacy=create(f,body("INGREDIENT_PURCHASE","50.00","USD",unknown),"link-legacy",PERMS,201);
        assertThat(legacy.path("sourceCurrencyStatus").asText()).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT currency_code FROM stock_documents WHERE id=?",String.class,unknown)).isNull();
        assertThat(create(f,body("INGREDIENT_PURCHASE","10.00","USD",receipt),"mismatch",PERMS,409).path("code").asText()).isEqualTo("STOCK_RECEIPT_CURRENCY_MISMATCH");
        create(f,body("RENT","10.00","VND",receipt),"bad-category",PERMS,400);
        create(f,body("INGREDIENT_PURCHASE","10.00","VND",receipt(f,"RECEIPT","DRAFT","VND")),"draft",PERMS,409);
        create(f,body("INGREDIENT_PURCHASE","10.00","VND",receipt(f,"ISSUE","CONFIRMED","VND")),"issue",PERMS,409);
        var other=fixture(FEATURES);create(f,body("INGREDIENT_PURCHASE","10.00","VND",receipt(other,"RECEIPT","CONFIRMED","VND")),"foreign",PERMS,404);
    }

    @Test void voidPreservesHistoryDoesNotCreateIncomeAndReplaysWithoutSourceEntitlement() throws Exception {
        var f=fixture(FEATURES);UUID receipt=receipt(f,"RECEIPT","CONFIRMED","VND");
        var body=body("INGREDIENT_PURCHASE","100.00","VND",receipt);var created=create(f,body,"void-original",PERMS,201);String id=created.path("id").asText();
        var facts=sourceFacts(f,receipt);
        var bad=postJson(f,BASE+"/"+id+"/void",Map.of("expectedVersion",99,"reason","wrong"),null,PERMS,409);
        assertThat(bad.path("code").asText()).isEqualTo("CONCURRENT_CASHBOOK_UPDATE");
        setFeatures(f,Set.of("DETAIL_REPORT"));
        var voided=postJson(f,BASE+"/"+id+"/void",Map.of("expectedVersion",created.path("version").asLong(),"reason","Ghi nhầm"),null,Set.of("CASHBOOK_VOID"),200);
        assertThat(voided.path("status").asText()).isEqualTo("VOIDED");assertThat(voided.path("amount").decimalValue()).isEqualByComparingTo("100.00");
        postJson(f,BASE+"/"+id+"/void",Map.of("expectedVersion",99,"reason","another"),null,Set.of("CASHBOOK_VOID"),200);
        assertThat(audits(f,"CASHBOOK_EXPENSE_VOIDED")).isEqualTo(1);assertThat(sourceFacts(f,receipt)).isEqualTo(facts);
        setFeatures(f,FEATURES);var replay=create(f,body,"void-original",PERMS,200);assertThat(replay.path("status").asText()).isEqualTo("VOIDED");assertThat(count(f,"cashbook_expenses")).isEqualTo(1);
        var response=mvc.perform(get("/api/v1/cashbook/summary?fromDate=2026-10-28&toDate=2026-10-29").with(authentication(auth(f,PERMS)))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(200);var summary=json.readTree(response.getContentAsString());
        assertThat(summary.at("/data/totalExpenseAmount").decimalValue()).isEqualByComparingTo("0");assertThat(summary.at("/data/totalIncomeAmount").decimalValue()).isEqualByComparingTo("0");
    }

    @Test void concurrentCreateAndVoidOnlyWriteOneVoucherAndOneAuditEach() throws Exception {
        var f=fixture(FEATURES);var request=body("OTHER","20.00","VND",null);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var start=new CountDownLatch(1);Callable<Integer> task=()->{start.await();return rawPost(f,BASE,request,"concurrent-create",PERMS).getResponse().getStatus();};
            var one=executor.submit(task);var two=executor.submit(task);start.countDown();assertThat(List.of(one.get(30,TimeUnit.SECONDS),two.get(30,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,200);
            assertThat(count(f,"cashbook_expenses")).isEqualTo(1);assertThat(audits(f,"CASHBOOK_EXPENSE_RECORDED")).isEqualTo(1);
            UUID id=jdbc.queryForObject("SELECT id FROM cashbook_expenses WHERE restaurant_id=?",UUID.class,f.restaurant().getId());
            var gate=new CountDownLatch(1);Callable<Integer> action=()->{gate.await();return rawPost(f,BASE+"/"+id+"/void",Map.of("expectedVersion",0,"reason","duplicate"),null,PERMS).getResponse().getStatus();};
            var v1=executor.submit(action);var v2=executor.submit(action);gate.countDown();assertThat(List.of(v1.get(30,TimeUnit.SECONDS),v2.get(30,TimeUnit.SECONDS))).containsOnly(200);
            assertThat(audits(f,"CASHBOOK_EXPENSE_VOIDED")).isEqualTo(1);
        }
    }

    @Test void auditFailureRollsBackExpenseAndAllowsRetry() throws Exception {
        var f=fixture(FEATURES);var request=body("OTHER","25.00","VND",null);
        doThrow(new IllegalStateException("test audit failure")).when(audit).record(eq(f.restaurant().getId()),eq(f.userId()),eq("CASHBOOK_EXPENSE_RECORDED"),eq("cashbook_expenses"),any(UUID.class),isNull(),anyMap(),any());
        try { create(f,request,"audit-failure",PERMS,500);assertThat(count(f,"cashbook_expenses")).isZero(); }
        finally { reset(audit); }
        create(f,request,"audit-failure",PERMS,201);
    }

    @Test void guardsRejectMissingPermissionsAndFeaturesBeforeAnyExpenseMutation() throws Exception {
        var f=fixture(FEATURES);var request=body("OTHER","10.00","VND",null);
        create(f,request,"no-create",Set.of("CASHBOOK_VIEW"),403);
        var noFeature=fixture(Set.of("INVENTORY_MANAGEMENT"));create(noFeature,request,"no-feature",PERMS,403);
        UUID receipt=receipt(f,"RECEIPT","CONFIRMED","VND");
        create(f,body("INGREDIENT_PURCHASE","10.00","VND",receipt),"no-inventory-perm",Set.of("CASHBOOK_CREATE"),403);
        setFeatures(f,Set.of("DETAIL_REPORT"));create(f,body("INGREDIENT_PURCHASE","10.00","VND",receipt),"no-inventory-feature",PERMS,403);
        var created=create(f,request,"basic-only",Set.of("CASHBOOK_CREATE"),201);
        postJson(f,BASE+"/"+created.path("id").asText()+"/void",Map.of("expectedVersion",0,"reason","wrong"),null,Set.of("CASHBOOK_CREATE"),403);
        assertThat(count(f,"cashbook_expenses")).isEqualTo(1);
        assertThat(mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request))).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    private Map<String,Object> body(String category,String amount,String currency,UUID source) {
        Map<String,Object> result=new LinkedHashMap<>();result.put("categoryCode",category);result.put("amount",new BigDecimal(amount));result.put("currencyCode",currency);result.put("paymentMethod","CASH");result.put("expenseAt",NOW.minusSeconds(86400).toString());result.put("stockDocumentId",source);return result;
    }
    private JsonNode create(Fixture f,Map<String,Object> body,String key,Set<String> permissions,int status)throws Exception{return postJson(f,BASE,body,key,permissions,status);}
    private JsonNode postJson(Fixture f,String path,Object body,String key,Set<String> permissions,int expected)throws Exception {
        var response=rawPost(f,path,body,key,permissions).getResponse();assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        assertThat(response.getHeader("Cache-Control")).contains("no-store");return json.readTree(response.getContentAsString());
    }
    private MvcResult rawPost(Fixture f,String path,Object body,String key,Set<String> permissions)throws Exception {
        var request=post(path).with(authentication(auth(f,permissions))).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body));if(key!=null)request.header("Idempotency-Key",key);return mvc.perform(request).andReturn();
    }
    private UsernamePasswordAuthenticationToken auth(Fixture f,Set<String> perms){return new UsernamePasswordAuthenticationToken(new CurrentUser(f.userId(),f.restaurant().getId(),"cashbook@example.test","OWNER",perms),null,perms.stream().map(SimpleGrantedAuthority::new).toList());}
    private Fixture fixture(Set<String> features) {
        var r=new Restaurant();r.setCode("CB_"+UUID.randomUUID().toString().replace("-",""));r.setName("Cashbook test");r.setTimezone("UTC");r.setCurrencyCode("VND");r=restaurants.saveAndFlush(r);
        UUID user=UUID.randomUUID();jdbc.update("INSERT INTO users(id,restaurant_id,role_id,name,email,password_hash) SELECT ?,?,id,'Test',?,'unused' FROM roles WHERE code='OWNER' AND restaurant_id IS NULL",user,r.getId(),user+"@example.test");
        var f=new Fixture(r,user);var sub=new RestaurantSubscription();sub.setRestaurantId(r.getId());sub.setPackageId(packages.findByCode("BASIC").orElseThrow().getId());sub.setStatus(SubscriptionStatus.ACTIVE);sub.setStartAt(NOW.minusSeconds(86400));sub.setEndAt(NOW.plusSeconds(86400));sub.setActivatedAt(NOW.minusSeconds(86400));sub.setPriceAmount(BigDecimal.ZERO);sub.setCurrencyCode("VND");sub.setFeatureSnapshot(snapshot(features));subscriptions.saveAndFlush(sub);return f;
    }
    private Map<String,Object> snapshot(Set<String> features){return new SubscriptionFeatureSnapshot(1,"TEST",features.stream().sorted().map(code->new SubscriptionFeatureSnapshot.FeatureGrant(code,Map.of())).toList(),NOW).toMap();}
    private void setFeatures(Fixture f,Set<String> features){
        var previous=subscriptions.findAll().stream().filter(s->s.getRestaurantId().equals(f.restaurant().getId())&&s.getStatus()==SubscriptionStatus.ACTIVE).findFirst().orElseThrow();
        previous.setStatus(SubscriptionStatus.CANCELLED);previous.setCancelledAt(NOW);subscriptions.saveAndFlush(previous);
        var next=new RestaurantSubscription();next.setRestaurantId(f.restaurant().getId());next.setPackageId(previous.getPackageId());next.setStatus(SubscriptionStatus.ACTIVE);
        next.setStartAt(NOW.minusSeconds(1));next.setEndAt(NOW.plusSeconds(86400));next.setActivatedAt(NOW);next.setPriceAmount(BigDecimal.ZERO);next.setCurrencyCode("VND");next.setFeatureSnapshot(snapshot(features));subscriptions.saveAndFlush(next);
    }
    private UUID receipt(Fixture f,String type,String status,String currency) {
        UUID warehouse=UUID.randomUUID(),id=UUID.randomUUID();jdbc.update("INSERT INTO warehouses(id,restaurant_id,code,name,created_at,updated_at) VALUES(?,?,?,'Test',?,?)",warehouse,f.restaurant().getId(),"W_"+warehouse.toString().substring(0,8),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,idempotency_key,creation_request_hash,created_by,confirmed_by,confirmed_at,total_cost,currency_code,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,f.restaurant().getId(),warehouse,"DOC_"+id.toString().substring(0,8),type,status,id.toString(),"a".repeat(64),f.userId(),status.equals("CONFIRMED")?f.userId():null,status.equals("CONFIRMED")?Timestamp.from(NOW):null,new BigDecimal("500.00"),currency,Timestamp.from(NOW),Timestamp.from(NOW));return id;
    }
    private Map<String,Object> sourceFacts(Fixture f,UUID receipt){var result=new LinkedHashMap<String,Object>();result.putAll(jdbc.queryForMap("SELECT status,total_cost,currency_code,version FROM stock_documents WHERE restaurant_id=? AND id=?",f.restaurant().getId(),receipt));for(String table:List.of("inventory_balances","inventory_transactions","payments","orders"))result.put(table,count(f,table));return result;}
    private long count(Fixture f,String table){return jdbc.queryForObject("SELECT count(*) FROM "+table+" WHERE restaurant_id=?",Long.class,f.restaurant().getId());}
    private long audits(Fixture f,String action){return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE restaurant_id=? AND action_code=?",Long.class,f.restaurant().getId(),action);}
    private record Fixture(Restaurant restaurant,UUID userId) {}
}
