package com.possaas.modules.subscriptionpurchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.dto.AuthResponse;
import com.possaas.modules.auth.dto.RegisterRestaurantRequest;
import com.possaas.modules.auth.dto.LoginRequest;
import com.possaas.modules.auth.dto.RefreshRequest;
import com.possaas.modules.auth.service.AuthenticationService;
import com.possaas.modules.authorization.repository.PermissionRepository;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.restaurant.service.RestaurantRegistrationService;
import com.possaas.modules.subscription.dto.CreateSubscriptionRequest;
import com.possaas.modules.subscription.dto.ChangePackageRequest;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.service.PackageFeatureCatalog;
import com.possaas.modules.subscription.service.SubscriptionCommandService;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.entity.PurchaseStatus;
import com.possaas.modules.subscriptionpurchase.service.PurchaseCommandService;
import com.possaas.modules.subscriptionpurchase.service.PurchaseQueryService;
import com.possaas.modules.user.service.TenantPermissionAssignmentPolicy;
import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"subscription-purchase.accept-new=true", "spring.jpa.properties.hibernate.generate_statistics=true",
    "app.sepay.enabled=false"})
@AutoConfigureMockMvc @Import(SubscriptionPurchaseIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionPurchaseIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-01T03:00:00Z");
    private static final String ROOT = "/api/v1/subscription-purchases";
    private static final String SCHEMA = "it_sp2_" + UUID.randomUUID().toString().replace("-", "");
    private static final Set<String> OWNER_PERMISSIONS = Set.of("SUBSCRIPTION_PURCHASE_VIEW", "SUBSCRIPTION_PURCHASE_CREATE", "SUBSCRIPTION_PURCHASE_CANCEL", "SUBSCRIPTION_VIEW");

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema="+SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        p.add("spring.datasource.url", () -> url); p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.schema", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired PurchaseCommandService commands;
    @Autowired PurchaseQueryService queries;
    @Autowired PurchaseProperties settings;
    @Autowired PackagePlanRepository packages;
    @Autowired SubscriptionCommandService admin;
    @Autowired RestaurantRepository restaurants;
    @Autowired RestaurantRegistrationService registration;
    @Autowired AuthenticationService authService;
    @Autowired MutableClock clock;
    @Autowired EntityManagerFactory entityManagers;
    @Autowired PlatformTransactionManager transactions;
    @Autowired PermissionRepository permissionRepository;
    @Autowired TenantPermissionAssignmentPolicy staffPolicy;
    @Autowired DataSource dataSource;
    @MockitoSpyBean AuditService audits;
    @MockitoSpyBean PackageFeatureCatalog featureCatalog;

    @BeforeEach void before() { clock.set(NOW); settings.setAcceptNew(true); SecurityContextHolder.clearContext(); }
    @AfterEach void after() { SecurityContextHolder.clearContext(); }
    @AfterAll void removeOnlyOwnSchema() {
        assertThat(SCHEMA).matches("it_sp2_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void registrationLoginRefreshAndEntitlementKeepExistingContract() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String password = "Purchase-test-1234";
        var request = new RegisterRestaurantRequest("SP_"+suffix, "New restaurant", null, null, null,
            "Asia/Ho_Chi_Minh", "VND", "Owner", suffix+"@example.com", null, password);
        AuthResponse signed = registration.register(request, "127.0.0.1");
        assertThat(signed.user().permissions()).containsAll(OWNER_PERMISSIONS);
        assertThat(count("restaurant_subscriptions", signed.user().restaurantId())).isZero();
        assertThat(count("subscription_purchases", signed.user().restaurantId())).isZero();
        assertThat(db.queryForObject("select status from restaurants where id=?", String.class, signed.user().restaurantId())).isEqualTo("ACTIVE");
        var logged = authService.login(new LoginRequest(request.ownerEmail(), password, null), "127.0.0.1");
        var refreshed = authService.refresh(new RefreshRequest(logged.refreshToken()), "127.0.0.1");
        assertThat(refreshed.user().permissions()).containsAll(OWNER_PERMISSIONS);
        mvc.perform(get("/api/v1/subscriptions/onboarding").header("Authorization", "Bearer " + refreshed.accessToken()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.subscriptionState").value("NONE"));
        mvc.perform(get("/api/v1/me/entitlements").header("Authorization", "Bearer " + refreshed.accessToken()))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_ACTIVE"));
    }

    @Test void registrationAuditFailureRollsBackOwnerRestaurantAndToken() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        doThrow(new IllegalStateException("audit unavailable")).when(audits).record(any(), any(), eq("RESTAURANT_REGISTERED"), anyString(), any(), any(), any(), any());
        var request = new RegisterRestaurantRequest("ROLL_"+suffix, "Rollback", null, null, null,
            "Asia/Ho_Chi_Minh", "VND", "Owner", suffix+"@example.com", null, "Example-Password-123");
        assertThatThrownBy(() -> registration.register(request, null)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("select count(*) from users where email=?", Integer.class, request.ownerEmail())).isZero();
        assertThat(db.queryForObject("select count(*) from restaurants where code=?", Integer.class, request.restaurantCode())).isZero();
    }

    @Test void onboardingAndCreateUseFrozenWholeVndQuoteWithoutSubscription() throws Exception {
        Actor a=owner(); var p=plan("399000.00", "VND", null);
        mvc.perform(get("/api/v1/subscriptions/onboarding").with(authentication(token(a.user()))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.subscriptionState").value("NONE")).andExpect(jsonPath("$.canPurchase").value(true));
        String response=mvc.perform(post(ROOT).with(authentication(token(a.user()))).contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", "purchase-http-0001").content(json.writeValueAsString(Map.of("packageCode",p.getCode()))))
            .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed","false"))
            .andExpect(jsonPath("$.quote.priceAmount").value(399000)).andExpect(jsonPath("$.quote.maxStaff").value(org.hamcrest.Matchers.nullValue()))
            .andReturn().getResponse().getContentAsString();
        JsonNode body=json.readTree(response);
        assertThat(body.path("quote").has("maxStaff")).isTrue();
        assertThat(body.path("allowedActions").toString()).doesNotContain("CREATE_PAYMENT_ATTEMPT");
        assertThat(Instant.parse(body.path("quote").path("expiresAt").asText())).isEqualTo(NOW.plusSeconds(900));
        assertThat(count("restaurant_subscriptions",a.tenant())).isZero();
        assertThat(auditCount(UUID.fromString(body.path("id").asText()),"SUBSCRIPTION_PURCHASE_CREATED")).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"EXPIRED","CANCELLED"})
    void historicalTerminalSubscriptionAllowsNewPurchase(String state) {
        Actor a=owner(); var p=plan("100","VND",5L); subscription(a,p,state,NOW.minusSeconds(100),NOW.minusSeconds(1));
        assertThat(create(a,p,"history-key-0001").body().status()).isEqualTo(PurchaseStatus.PENDING_PAYMENT);
    }

    @Test void activeBoundaryReconcilesAndFutureOrEffectiveActiveBlock() {
        for(boolean future:List.of(false,true)) {
            Actor a=owner(); var p=plan("100","VND",5L);
            subscription(a,p,"ACTIVE",future?NOW.plusSeconds(20):NOW.minusSeconds(20),NOW.plusSeconds(100));
            expectCode(() -> create(a,p,"active-key-0001"),"SUBSCRIPTION_ALREADY_ACTIVE");
            assertThat(count("subscription_purchases",a.tenant())).isZero();
        }
        Actor due=owner(); var p=plan("100","VND",5L); UUID s=subscription(due,p,"ACTIVE",NOW.minusSeconds(10),NOW);
        create(due,p,"boundary-key-0001");
        assertThat(db.queryForObject("select status from restaurant_subscriptions where id=?",String.class,s)).isEqualTo("EXPIRED");
        assertThat(auditCount(s,"SUBSCRIPTION_EXPIRED")).isEqualTo(1);
    }

    @Test void adminPendingAndExistingOpenPurchaseBlockNewIntent() {
        var p=plan("100","VND",5L); Actor a=owner(); subscription(a,p,"PENDING",NOW,NOW.plusSeconds(100));
        expectCode(() -> create(a,p,"pending-key-0001"),"SUBSCRIPTION_PENDING_EXISTS");
        Actor b=owner(); create(b,p,"open-key-0001");
        expectCode(() -> create(b,p,"open-key-0002"),"PURCHASE_IN_PROGRESS");
        assertThat(count("subscription_purchases",b.tenant())).isEqualTo(1);
    }

    @Test void duePurchaseProjectsWithoutWritesThenNewCommandReconciles() {
        Actor a=owner(); var p=plan("100","VND",5L); var initial=create(a,p,"expiry-key-0001").body();
        clock.set(NOW.plusSeconds(900)); signIn(a.user());
        var display=queries.detail(initial.id()); var onboarding=queries.onboarding();
        assertThat(display.status()).isEqualTo(PurchaseStatus.PENDING_PAYMENT);
        assertThat(display.displayStatus()).isEqualTo(PurchaseStatus.EXPIRED);
        assertThat(onboarding.canPurchase()).isTrue();
        assertThat(storedStatus(initial.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(auditCount(initial.id(),"SUBSCRIPTION_PURCHASE_EXPIRED")).isZero();
        expectCode(() -> commands.cancel(initial.id(),new PurchaseRequests.Cancel(0L,null),null),"PURCHASE_PERIOD_EXPIRED");
        assertThat(storedStatus(initial.id())).isEqualTo("PENDING_PAYMENT");
        var next=create(a,p,"expiry-key-0002");
        assertThat(next.body().id()).isNotEqualTo(initial.id());
        assertThat(storedStatus(initial.id())).isEqualTo("EXPIRED");
        assertThat(auditCount(initial.id(),"SUBSCRIPTION_PURCHASE_EXPIRED")).isEqualTo(1);
        assertThat(create(a,p,"expiry-key-0001").body()).isEqualTo(initial);
    }

    @Test void replayBeforeEligibilitySurvivesCatalogAndFlagChangesAndCancellation() {
        Actor a=owner(); var p=plan("100","VND",null); var original=create(a,p,"replay-key-0001").body();
        db.update("update packages set is_active=false,price_amount=999,max_staff=2 where id=?",p.getId());
        settings.setAcceptNew(false);
        var replay=create(a,p,"replay-key-0001");
        assertThat(replay.replayed()).isTrue(); assertThat(replay.body()).isEqualTo(original);
        signIn(a.user()); var cancelled=commands.cancel(original.id(),new PurchaseRequests.Cancel(0L,"Wrong package"),null);
        assertThat(cancelled.status()).isEqualTo(PurchaseStatus.CANCELLED);
        assertThat(create(a,p,"replay-key-0001").body()).isEqualTo(original);
        var other=plan("100","VND",5L);
        expectCode(() -> create(a,other,"replay-key-0001"),"IDEMPOTENCY_KEY_REUSED");
        expectCode(() -> create(a,other,"replay-key-0002"),"PURCHASE_UNAVAILABLE");
        assertThat(auditCount(original.id(),"SUBSCRIPTION_PURCHASE_CREATED")).isEqualTo(1);
    }

    @Test void replayAfterActivationKeepsOriginalBodyAndDoesNotExtendSubscription() {
        Actor a=owner(); var p=plan("100","VND",5L); var first=create(a,p,"activated-key-0001").body();
        markPaid(a,first,true);
        var replay=create(a,p,"activated-key-0001");
        assertThat(replay.replayed()).isTrue(); assertThat(replay.body()).isEqualTo(first);
        assertThat(storedStatus(first.id())).isEqualTo("ACTIVATED");
        assertThat(count("restaurant_subscriptions",a.tenant())).isEqualTo(1);
        signIn(a.user()); assertThat(queries.detail(first.id()).status()).isEqualTo(PurchaseStatus.ACTIVATED);
    }

    @Test void normalizedPayloadAndDifferentPayloadHaveStableReplaySemantics() {
        Actor a=owner(); var p=plan("100","VND",5L); var first=create(a,p,"normalize-key-0001").body(); signIn(a.user());
        var replay=commands.create(new PurchaseRequests.Create(" "+p.getCode().toLowerCase(Locale.ROOT)+" "),"normalize-key-0001",null);
        assertThat(replay.body()).isEqualTo(first);
        expectCode(() -> commands.create(new PurchaseRequests.Create("SOMETHING_ELSE"),"normalize-key-0001",null),"IDEMPOTENCY_KEY_REUSED");
    }

    @Test void cancelIsIdempotentAndFreesTheSlot() {
        Actor a=owner(); var p=plan("100","VND",5L); var first=create(a,p,"cancel-key-0001").body(); signIn(a.user());
        expectCode(() -> commands.cancel(first.id(),new PurchaseRequests.Cancel(5L,null),null),"CONCURRENT_SUBSCRIPTION_UPDATE");
        var one=commands.cancel(first.id(),new PurchaseRequests.Cancel(0L,"Initial reason"),null);
        var two=commands.cancel(first.id(),new PurchaseRequests.Cancel(0L,"Other reason"),null);
        assertThat(two.cancelledAt()).isEqualTo(one.cancelledAt());
        assertThat(two.version()).isEqualTo(one.version());
        assertThat(db.queryForObject("select cancel_reason from subscription_purchases where id=?",String.class,first.id())).isEqualTo("Initial reason");
        assertThat(auditCount(first.id(),"SUBSCRIPTION_PURCHASE_CANCELLED")).isEqualTo(1);
        assertThat(create(a,p,"cancel-key-0002").body().id()).isNotEqualTo(first.id());
    }

    @ParameterizedTest @ValueSource(strings={"PAID","ACTIVATED","REVIEW_REQUIRED","CLOSED"})
    void paidOrReviewStatesCannotBeCancelled(String state) {
        Actor a=owner(); var p=plan("100","VND",5L); var first=create(a,p,"protected-key-0001").body();
        if(state.equals("PAID")||state.equals("ACTIVATED")) markPaid(a,first,state.equals("ACTIVATED"));
        else {
            db.update("update subscription_purchases set status='REVIEW_REQUIRED',review_reason='AMOUNT_MISMATCH',version=version+1 where id=?",first.id());
            if(state.equals("CLOSED")) db.update("update subscription_purchases set status='CLOSED',closed_at=?,version=version+1 where id=?",ts(NOW),first.id());
        }
        signIn(a.user()); expectCode(() -> commands.cancel(first.id(),new PurchaseRequests.Cancel(0L,null),null),"PURCHASE_CANNOT_CANCEL");
        if(state.equals("PAID")) expectCode(() -> create(a,p,"protected-key-0002"),"PURCHASE_IN_PROGRESS");
        if(state.equals("REVIEW_REQUIRED")) expectCode(() -> create(a,p,"protected-key-0002"),"PURCHASE_REVIEW_REQUIRED");
        assertThat(auditCount(first.id(),"SUBSCRIPTION_PURCHASE_CANCELLED")).isZero();
    }

    @Test void catalogChangesDoNotAlterSnapshotAndNullStaffLimitIsPreserved() {
        Actor a=owner(); var p=plan("100","VND",null); UUID f=feature(p,"FROZEN_FEATURE");
        var first=create(a,p,"catalog-key-0001").body();
        db.update("update packages set price_amount=200,max_staff=7,is_active=false where id=?",p.getId());
        db.update("update features set is_active=false where id=?",f);
        db.update("delete from package_features where package_id=?",p.getId()); signIn(a.user());
        var actual=queries.detail(first.id()).quote();
        assertThat(actual).isEqualTo(first.quote()); assertThat(actual.maxStaff()).isNull();
        assertThat(actual.features()).extracting(g -> g.code()).containsExactly(first.quote().features().getFirst().code());
    }

    @ParameterizedTest @ValueSource(strings={"0","100.50"})
    void invalidWholeVndPricesAreRejected(String amount) {
        Actor a=owner(); var p=plan(amount,"VND",5L);
        expectCode(() -> create(a,p,"price-key-0001"),"PACKAGE_NOT_PURCHASABLE");
        assertThat(count("subscription_purchases",a.tenant())).isZero();
    }
    @Test void nonVndAndInactivePackagesAreRejected() {
        Actor a=owner(); var p=plan("100","USD",5L);
        expectCode(() -> create(a,p,"currency-key-0001"),"PACKAGE_NOT_PURCHASABLE");
        db.update("update packages set is_active=false where id=?",p.getId());
        expectCode(() -> create(a,p,"currency-key-0002"),"PACKAGE_NOT_FOUND");
    }

    @Test void listFiltersAreStoredStatusAndUseDeterministicPaginationWithoutNPlusOne() {
        Actor a=owner(); var p=plan("100","VND",5L);
        for(int i=0;i<21;i++) { var v=create(a,p,"list-key-"+String.format("%04d",i)).body(); signIn(a.user()); commands.cancel(v.id(),new PurchaseRequests.Cancel(0L,null),null); }
        signIn(a.user()); var statistics=entityManagers.unwrap(SessionFactory.class).getStatistics(); statistics.clear();
        var page=queries.search(PurchaseStatus.CANCELLED,NOW.toString(),NOW.plusSeconds(1).toString(),0,20);
        long statements=statistics.getPrepareStatementCount();
        assertThat(page.totalElements()).isEqualTo(21); assertThat(page.totalPages()).isEqualTo(2); assertThat(page.content()).hasSize(20);
        assertThat(statements).isLessThan(18);
        assertThat(queries.search(null,null,null,1,20).content()).hasSize(1);
        expectCode(() -> queries.search(null,NOW.toString(),NOW.toString(),0,20),"VALIDATION_ERROR");
        expectCode(() -> queries.search(null,NOW.toString(),NOW.plus(Duration.ofDays(367)).toString(),0,20),"VALIDATION_ERROR");
        expectCode(() -> queries.search(null,"2026-11-01T10:00:00+07:00",null,0,20),"VALIDATION_ERROR");
    }

    @Test void crossTenantReadsAndMutationAre404() throws Exception {
        Actor a=owner(),b=owner(); var p=plan("100","VND",5L); var v=create(a,p,"tenant-key-0001").body();
        mvc.perform(get(ROOT+"/"+v.id()).with(authentication(token(b.user())))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        mvc.perform(post(ROOT+"/"+v.id()+"/cancel").with(authentication(token(b.user()))).contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        mvc.perform(get(ROOT).with(authentication(token(b.user()))).param("restaurantId",a.tenant().toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test void rolePermissionAccountAndRestaurantGuardsRunBeforeReplay() throws Exception {
        Actor a=owner(); var p=plan("100","VND",5L); var v=create(a,p,"access-key-0001").body();
        SecurityContextHolder.clearContext();
        mvc.perform(get(ROOT)).andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control","no-store"));
        var staff=new CurrentUser(a.user().userId(),a.tenant(),a.user().email(),"WAITER",OWNER_PERMISSIONS);
        mvc.perform(get(ROOT).with(authentication(token(staff)))).andExpect(status().isForbidden());
        for(String permission:List.of("SUBSCRIPTION_PURCHASE_VIEW","SUBSCRIPTION_PURCHASE_CREATE","SUBSCRIPTION_PURCHASE_CANCEL")) {
            Set<String> allowed=new HashSet<>(OWNER_PERMISSIONS); allowed.remove(permission);
            var principal=new CurrentUser(a.user().userId(),a.tenant(),a.user().email(),"OWNER",allowed);
            var request=permission.endsWith("VIEW")?get(ROOT):permission.endsWith("CREATE")?post(ROOT).header("Idempotency-Key","access-key-0001").content("{\"packageCode\":\""+p.getCode()+"\"}")
                :post(ROOT+"/"+v.id()+"/cancel").content("{\"expectedVersion\":0}");
            mvc.perform(request.with(authentication(token(principal))).contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden());
        }
        db.update("insert into user_permissions(user_id,permission_id,effect) select ?,id,'DENY' from permissions where code='SUBSCRIPTION_PURCHASE_CREATE'",a.user().userId());
        expectCode(() -> create(a,p,"access-key-0001"),"FORBIDDEN");
        db.update("update users set is_active=false where id=?",a.user().userId());
        signIn(a.user()); expectCode(() -> queries.detail(v.id()),"ACCOUNT_INACTIVE");
        db.update("update users set is_active=true where id=?",a.user().userId());
        db.update("update users set role_id=(select id from roles where code='WAITER' and restaurant_id is null) where id=?",a.user().userId());
        expectCode(() -> queries.detail(v.id()),"FORBIDDEN");
        db.update("update users set role_id=(select id from roles where code='OWNER' and restaurant_id is null) where id=?",a.user().userId());
        db.update("update restaurants set status='SUSPENDED' where id=?",a.tenant());
        expectCode(() -> queries.detail(v.id()),"RESTAURANT_INACTIVE");
    }

    @Test void invalidJsonUnknownFieldsMissingKeyAndCoercionsAreRejected() throws Exception {
        Actor a=owner(); var p=plan("100","VND",5L);
        for(String body:List.of("{", "{\"packageCode\":123}", "{\"packageCode\":\""+p.getCode()+"\",\"paid\":true}",
            "{\"packageCode\":\""+p.getCode()+"\",\"restaurantId\":\""+a.tenant()+"\"}"))
            mvc.perform(post(ROOT).with(authentication(token(a.user()))).header("Idempotency-Key","invalid-key-0001").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(post(ROOT).with(authentication(token(a.user()))).contentType(MediaType.APPLICATION_JSON).content("{\"packageCode\":\""+p.getCode()+"\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        var v=create(a,p,"valid-key-0001").body();
        for(String value:List.of("\"0\"","0.5")) mvc.perform(post(ROOT+"/"+v.id()+"/cancel").with(authentication(token(a.user())))
            .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":"+value+"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(get(ROOT).with(authentication(token(a.user()))).param("size","101")).andExpect(status().isBadRequest());
    }

    @Test void paidReviewCountsRemainIndependentFromActiveSubscriptionAndPermissionsAreNotDelegatable() {
        Actor a=owner(); var p=plan("100","VND",5L); var v=create(a,p,"review-key-0001").body(); UUID event=markPaid(a,v,true);
        db.update("update subscription_payment_events set state='REVIEW_REQUIRED',review_reason='DUPLICATE_PAYMENT' where id=?",event);
        signIn(a.user()); var state=queries.onboarding();
        assertThat(state.subscriptionState()).isEqualTo("ACTIVE"); assertThat(state.currentPurchase()).isNull();
        assertThat(state.unresolvedPaymentReviewCount()).isEqualTo(1); assertThat(state.canPurchase()).isFalse();
        for(String permission:List.of("SUBSCRIPTION_PURCHASE_VIEW","SUBSCRIPTION_PURCHASE_CREATE","SUBSCRIPTION_PURCHASE_CANCEL","SUBSCRIPTION_PAYMENT_REVIEW"))
            assertThat(staffPolicy.isAssignable(permissionRepository.findByCodeIn(List.of(permission)).getFirst())).isFalse();
    }

    @Test void auditFailureRollsBackCreateAndReconciledExpiry() {
        Actor a=owner(); var p=plan("100","VND",5L); var v=create(a,p,"rollback-key-0001").body();
        clock.set(NOW.plusSeconds(900));
        doThrow(new IllegalStateException("audit unavailable")).when(audits).record(eq(a.tenant()),eq(a.user().userId()),eq("SUBSCRIPTION_PURCHASE_CREATED"),anyString(),any(),any(),any(),any());
        assertThatThrownBy(() -> create(a,p,"rollback-key-0002")).isInstanceOf(IllegalStateException.class);
        assertThat(count("subscription_purchases",a.tenant())).isEqualTo(1);
        assertThat(storedStatus(v.id())).isEqualTo("PENDING_PAYMENT");
        assertThat(auditCount(v.id(),"SUBSCRIPTION_PURCHASE_EXPIRED")).isZero();
    }

    @Test void adminMutationsRespectOpenPurchasesEvenWhenAcceptNewIsOff() {
        var p=plan("100","VND",5L); Actor a=owner(); var v=create(a,p,"admin-key-0001").body(); settings.setAcceptNew(false);
        expectCode(() -> admin.create(a.tenant(),adminRequest(p),null,null),"SUBSCRIPTION_PURCHASE_IN_PROGRESS");
        UUID pending=subscription(a,p,"PENDING",NOW,NOW.plusSeconds(100));
        expectCode(() -> admin.activate(a.tenant(),pending,null,null),"SUBSCRIPTION_PURCHASE_IN_PROGRESS");
        expectCode(() -> admin.changePackage(a.tenant(),pending,changeRequest(p),null,null),"SUBSCRIPTION_PURCHASE_IN_PROGRESS");
        assertThat(storedStatus(v.id())).isEqualTo("PENDING_PAYMENT");
        Actor expired=owner(); settings.setAcceptNew(true); var due=create(expired,p,"admin-due-key-0001").body(); clock.set(NOW.plusSeconds(900)); settings.setAcceptNew(false);
        admin.create(expired.tenant(),new CreateSubscriptionRequest(p.getCode(),clock.instant(),clock.instant().plusSeconds(100),false,new BigDecimal("100"),"VND"),null,null);
        assertThat(storedStatus(due.id())).isEqualTo("EXPIRED");
    }

    @Test void concurrentCreatesSameKeyReplayAndDifferentKeysHaveOneWinner() throws Exception {
        for(boolean sameKey:List.of(true,false)) {
            Actor a=owner(); var p=plan("100","VND",5L); var start=new CyclicBarrier(2); ExecutorService pool=Executors.newFixedThreadPool(2);
            try {
                List<Future<Object>> futures=new ArrayList<>();
                for(int i=0;i<2;i++) { final String key="race-key-"+(sameKey?"same":"other-"+i); futures.add(pool.submit(() -> {
                    start.await(5,TimeUnit.SECONDS); try { return create(a,p,key); } catch(BusinessException e) { return e.getCode(); } finally { SecurityContextHolder.clearContext(); }
                })); }
                List<Object> results=List.of(futures.get(0).get(15,TimeUnit.SECONDS),futures.get(1).get(15,TimeUnit.SECONDS));
                assertThat(count("subscription_purchases",a.tenant())).isEqualTo(1);
                if(sameKey) {
                    assertThat(results).allMatch(r -> r instanceof PurchaseResponses.Creation);
                    assertThat(results.stream().map(r -> ((PurchaseResponses.Creation)r).replayed())).containsExactlyInAnyOrder(false,true);
                } else { assertThat(results.stream().filter(r -> r instanceof PurchaseResponses.Creation)).hasSize(1); assertThat(results).contains("PURCHASE_IN_PROGRESS"); }
                assertThat(db.queryForObject("select count(*) from audit_logs where restaurant_id=? and action_code='SUBSCRIPTION_PURCHASE_CREATED'",Integer.class,a.tenant())).isEqualTo(1);
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void adminCommitWhileBuyerWaitsForRestaurantForcesFreshSnapshot() throws Exception {
        Actor a=owner(); var p=plan("100","VND",5L);
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch inserted=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            var adminFuture=pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                admin.create(a.tenant(),adminRequest(p),null,null); inserted.countDown(); await(release); return true;
            }));
            assertThat(inserted.await(5,TimeUnit.SECONDS)).isTrue();
            var buyer=pool.submit(() -> {try { return (Object)create(a,p,"admin-race-key-0001"); } catch(BusinessException e){return e.getCode();} finally {SecurityContextHolder.clearContext();} });
            awaitDatabaseLock("restaurants"); release.countDown(); assertThat(adminFuture.get(10,TimeUnit.SECONDS)).isTrue();
            assertThat(buyer.get(15,TimeUnit.SECONDS)).isEqualTo("SUBSCRIPTION_PENDING_EXISTS");
            assertThat(count("subscription_purchases",a.tenant())).isZero();
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void catalogueWriterDoesNotProduceMixedPriceAndFeatureQuote() throws Exception {
        Actor a=owner(); var p=plan("100","VND",5L); UUID f=feature(p,"RACING_FEATURE");
        ExecutorService pool=Executors.newFixedThreadPool(2); CountDownLatch updated=new CountDownLatch(1),release=new CountDownLatch(1);
        try {
            var writer=pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                packages.findByIdForUpdate(p.getId()).orElseThrow();
                db.update("update packages set price_amount=200,max_staff=10 where id=?",p.getId());
                db.update("update features set is_active=false where id=?",f); updated.countDown(); await(release); return true;
            }));
            assertThat(updated.await(5,TimeUnit.SECONDS)).isTrue();
            var reader=pool.submit(() -> {try {return create(a,p,"catalog-race-key-0001");} finally {SecurityContextHolder.clearContext();}});
            awaitDatabaseLock("packages"); release.countDown(); writer.get(10,TimeUnit.SECONDS);
            var quote=reader.get(15,TimeUnit.SECONDS).body().quote();
            assertThat(quote.priceAmount()).isEqualTo(200); assertThat(quote.maxStaff()).isEqualTo(10); assertThat(quote.features()).isEmpty();
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void globalFeatureChangeDuringCaptureUsesOneRepeatableReadSnapshot() throws Exception {
        Actor a=owner(); var p=plan("100","VND",5L); UUID f=feature(p,"GLOBAL_FEATURE");
        CountDownLatch entering=new CountDownLatch(1),release=new CountDownLatch(1);
        doAnswer(invocation -> {entering.countDown();await(release);return invocation.callRealMethod();}).when(featureCatalog).getActiveFeatures(p.getId());
        ExecutorService pool=Executors.newSingleThreadExecutor();
        try {
            var reader=pool.submit(() -> {try {return create(a,p,"global-race-key-0001");} finally {SecurityContextHolder.clearContext();}});
            assertThat(entering.await(5,TimeUnit.SECONDS)).isTrue(); db.update("update features set is_active=false where id=?",f); release.countDown();
            assertThat(reader.get(15,TimeUnit.SECONDS).body().quote().features()).hasSize(1);
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void databaseProtectsFinancialHistoryOpenSlotTenantAndDeferredMoneyChecks() {
        Actor a=owner(),b=owner(); var p=plan("100","VND",5L); var v=create(a,p,"db-key-0001").body();
        assertThatThrownBy(() -> db.update("update subscription_purchases set quote=jsonb_set(quote,'{priceAmount}','999') where id=?",v.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("delete from subscription_purchases where id=?",v.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("""
            insert into subscription_purchases(id,restaurant_id,created_by,package_id,status,quote,amount,currency_code,quoted_at,expires_at,idempotency_key,request_hash,creation_response,created_at,updated_at)
            select gen_random_uuid(),restaurant_id,created_by,package_id,status,quote,amount,currency_code,quoted_at,expires_at,'duplicate-open-key',request_hash,creation_response,created_at,updated_at from subscription_purchases where id=?
            """,v.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(b.tenant(),v,UUID.randomUUID(),"bad-tenant-key",v.quote().priceAmount())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertAttempt(a.tenant(),v,UUID.randomUUID(),"bad-money-key",999)).isInstanceOf(DataIntegrityViolationException.class);
        UUID attempt=UUID.randomUUID(); insertAttempt(a.tenant(),v,attempt,"pending-attempt-key",100);
        assertThatThrownBy(() -> insertAttempt(a.tenant(),v,UUID.randomUUID(),"another-pending-key",100)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("update subscription_payment_attempts set reference_code=? where id=?","PS0123456789ABCDEF01234567",attempt)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("delete from subscription_payment_attempts where id=?",attempt)).isInstanceOf(DataIntegrityViolationException.class);
        signIn(a.user()); commands.cancel(v.id(),new PurchaseRequests.Cancel(0L,null),null);
        assertThat(db.queryForObject("select status from subscription_payment_attempts where id=?",String.class,attempt)).isEqualTo("CANCELLED");
    }

    @Test void deferredPaidEvidenceCannotPointToAnotherTenantAndEventsAreImmutable() {
        Actor a=owner(),b=owner();var p=plan("100","VND",5L);
        var one=create(a,p,"evidence-key-0001").body();var two=create(b,p,"evidence-key-0002").body();
        UUID event=markPaid(b,two,false);
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(tx -> {
            db.update("update subscription_purchases set status='PAID',paid_event_id=?,paid_at=? where id=?",event,ts(NOW),one.id());
            return true;
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(storedStatus(one.id())).isEqualTo("PENDING_PAYMENT");
        assertThatThrownBy(() -> db.update("update subscription_payment_events set amount=999 where id=?",event)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("delete from subscription_payment_events where id=?",event)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> db.update("""
            insert into subscription_payment_events(id,provider,provider_account_key,provider_transaction_id,source,payload_hash,payload,direction,amount,receiver_account,receiver_bank,occurred_at,received_at,state,last_received_at,created_at,updated_at)
            select gen_random_uuid(),provider,provider_account_key,provider_transaction_id,source,payload_hash,payload,direction,amount,receiver_account,receiver_bank,occurred_at,received_at,'RECEIVED',last_received_at,created_at,updated_at
            from subscription_payment_events where id=?
            """,event)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void flywayUpgradeFromV40PreservesRestaurantSubscriptionAndCustomPermissions() throws Exception {
        String schema="it_sp2_upgrade_"+UUID.randomUUID().toString().replace("-","");
        try {
            Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target("40").load().migrate();
            String before;
            UUID tenant=UUID.randomUUID(),sub=UUID.randomUUID(),owner=UUID.randomUUID();
            try(var connection=dataSource.getConnection()) {
                connection.setSchema(schema);
                try(var ps=connection.prepareStatement("insert into restaurants(id,code,name,status,public_order_token) values(?,?,'Upgrade','ACTIVE',?)")) {
                    ps.setObject(1,tenant);ps.setString(2,"UP_"+tenant.toString().replace("-",""));ps.setString(3,UUID.randomUUID().toString());ps.executeUpdate();
                }
                try(var ps=connection.prepareStatement("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Legacy owner',?,'legacy' from roles where code='OWNER' and restaurant_id is null")) {
                    ps.setObject(1,owner);ps.setObject(2,tenant);ps.setString(3,owner+"@example.com");ps.executeUpdate();
                }
                try(var ps=connection.prepareStatement("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,feature_snapshot) select ?,?,id,'EXPIRED',now()-interval '2 months',now()-interval '1 month',price_amount,currency_code,cast('{\"schemaVersion\":1,\"packageCode\":\"PRO\",\"features\":[]}' as jsonb) from packages where code='PRO'")) {
                    ps.setObject(1,sub);ps.setObject(2,tenant);ps.executeUpdate();
                }
                try(var ps=connection.prepareStatement("insert into user_permissions(user_id,permission_id,effect) select ?,id,'DENY' from permissions where code='ORDER_VIEW'")) {ps.setObject(1,owner);ps.executeUpdate();}
                try(var ps=connection.prepareStatement("select row_to_json(s)::text from restaurant_subscriptions s where id=?")) {
                    ps.setObject(1,sub);try(var rs=ps.executeQuery()){rs.next();before=rs.getString(1);}
                }
            }
            var upgraded=Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema).locations("classpath:db/migration").load();
            assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
            assertThat(upgraded.info().current().getVersion().getVersion()).isEqualTo("42");
            try(var connection=dataSource.getConnection()) {
                connection.setSchema(schema);
                try(var ps=connection.prepareStatement("select row_to_json(s)::text from restaurant_subscriptions s where id=?")) {
                    ps.setObject(1,sub);try(var rs=ps.executeQuery()){rs.next();assertThat(rs.getString(1)).isEqualTo(before);}
                }
                try(var ps=connection.prepareStatement("select count(*) from user_permissions where user_id=? and effect='DENY'")) {ps.setObject(1,owner);try(var rs=ps.executeQuery()){rs.next();assertThat(rs.getInt(1)).isEqualTo(1);}}
                try(var statement=connection.createStatement();var rs=statement.executeQuery("select count(*) from role_permissions rp join roles r on r.id=rp.role_id join permissions p on p.id=rp.permission_id where r.code='OWNER' and r.restaurant_id is null and p.code like 'SUBSCRIPTION_PURCHASE_%'")) {rs.next();assertThat(rs.getInt(1)).isEqualTo(3);}
            }
        } finally {
            assertThat(schema).matches("it_sp2_upgrade_[0-9a-f]{32}"); db.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
        }
    }

    private record Actor(CurrentUser user) {UUID tenant(){return user.restaurantId();}}
    private Actor owner() {
        UUID tenant=UUID.randomUUID(), user=UUID.randomUUID();String suffix=tenant.toString().replace("-","");
        db.update("insert into restaurants(id,code,name,status,timezone,currency_code,public_order_token) values(?,?,?,'ACTIVE','Asia/Ho_Chi_Minh','VND',?)",tenant,"SP_"+suffix,"Owner restaurant",suffix);
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Owner',?,'not-used-for-login' from roles where code='OWNER' and restaurant_id is null",user,tenant,suffix+"@example.com");
        return new Actor(new CurrentUser(user,tenant,suffix+"@example.com","OWNER",OWNER_PERMISSIONS));
    }
    private PackagePlan plan(String amount,String currency,Long staff) {
        PackagePlan p=new PackagePlan();p.setCode("SP_"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT));p.setName("Purchase plan");
        p.setPriceAmount(new BigDecimal(amount));p.setCurrencyCode(currency);p.setBillingCycleMonths((short)1);p.setMaxStaff(staff);p.setActive(true);return packages.saveAndFlush(p);
    }
    private UUID feature(PackagePlan p,String prefix) {
        UUID id=UUID.randomUUID();String code=prefix+"_"+id.toString().replace("-", "");
        db.update("insert into features(id,code,name,is_active) values(?,?,?,true)",id,code,code);
        db.update("insert into package_features(package_id,feature_id,limits) values(?,?,cast('{}' as jsonb))",p.getId(),id);return id;
    }
    private PurchaseResponses.Creation create(Actor a,PackagePlan p,String key) {signIn(a.user());return commands.create(new PurchaseRequests.Create(p.getCode()),key,null);}
    private static UsernamePasswordAuthenticationToken token(CurrentUser user) {
        List<SimpleGrantedAuthority> grants=new ArrayList<>();grants.add(new SimpleGrantedAuthority("ROLE_"+user.role()));user.permissions().forEach(p -> grants.add(new SimpleGrantedAuthority(p)));
        return new UsernamePasswordAuthenticationToken(user,null,grants);
    }
    private static void signIn(CurrentUser u){SecurityContextHolder.getContext().setAuthentication(token(u));}
    private static Timestamp ts(Instant time){return Timestamp.from(time);}
    private int count(String table,UUID tenant){return db.queryForObject("select count(*) from "+table+" where restaurant_id=?",Integer.class,tenant);}
    private int auditCount(UUID id,String action){return db.queryForObject("select count(*) from audit_logs where entity_id=? and action_code=?",Integer.class,id,action);}
    private String storedStatus(UUID id){return db.queryForObject("select status from subscription_purchases where id=?",String.class,id);}
    private UUID subscription(Actor a,PackagePlan p,String state,Instant start,Instant end){
        UUID id=UUID.randomUUID();db.update("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code) values(?,?,?,?,?,?,?,'VND')",id,a.tenant(),p.getId(),state,ts(start),ts(end),p.getPriceAmount());return id;
    }
    private CreateSubscriptionRequest adminRequest(PackagePlan p){return new CreateSubscriptionRequest(p.getCode(),NOW,NOW.plusSeconds(3600),false,p.getPriceAmount(),"VND");}
    private ChangePackageRequest changeRequest(PackagePlan p){return new ChangePackageRequest(p.getCode(),NOW.plusSeconds(3600),false,p.getPriceAmount(),"VND");}
    private void insertAttempt(UUID tenant,PurchaseResponses.View v,UUID id,String key,long amount){
        String reference="PS"+id.toString().replace("-", "").substring(0,20).toUpperCase(Locale.ROOT);
        db.update("""
            insert into subscription_payment_attempts(id,restaurant_id,purchase_id,status,provider_account_key,reference_code,amount,currency_code,expires_at,instructions,idempotency_key,request_hash,creation_response,created_at,updated_at)
            values(?,?,?,'PENDING','platform-demo',?,?,'VND',?,cast('{}' as jsonb),?,?,cast('{}' as jsonb),?,?)
            """,id,tenant,v.id(),reference,amount,ts(v.quote().expiresAt()),key,"0".repeat(64),ts(NOW),ts(NOW));
    }
    private UUID markPaid(Actor a,PurchaseResponses.View v,boolean activate){
        UUID event=UUID.randomUUID(),attempt=UUID.randomUUID();
        return new TransactionTemplate(transactions).execute(tx -> {
            insertAttempt(a.tenant(),v,attempt,"fixture-"+attempt,v.quote().priceAmount());
            db.update("""
                insert into subscription_payment_events(id,provider,provider_account_key,provider_transaction_id,source,payload_hash,payload,direction,amount,receiver_account,receiver_bank,occurred_at,received_at,restaurant_id,purchase_id,attempt_id,state,last_received_at,processed_at,created_at,updated_at)
                values(?,'SEPAY','platform-demo',?,'WEBHOOK',?,cast('{}' as jsonb),'in',?,'DEMO_ACCOUNT','DEMO_BANK',?,?,?,?,?,'PROCESSED',?,?,?,?)
                """,event,"WEBHOOK:"+event,"0".repeat(64),v.quote().priceAmount(),ts(NOW),ts(NOW),a.tenant(),v.id(),attempt,ts(NOW),ts(NOW),ts(NOW),ts(NOW));
            db.update("update subscription_payment_attempts set status='SUCCESS',successful_event_id=?,paid_at=?,version=version+1 where id=?",event,ts(NOW),attempt);
            db.update("update subscription_purchases set status='PAID',paid_event_id=?,paid_at=?,version=version+1 where id=?",event,ts(NOW),v.id());
            if(activate){
                UUID subscription=UUID.randomUUID();
                db.update("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code) values(?,?,?,'ACTIVE',?,?,?,'VND')",subscription,a.tenant(),v.quote().packageId(),ts(NOW),ts(NOW.plusSeconds(86400)),v.quote().priceAmount());
                db.update("update subscription_purchases set status='ACTIVATED',subscription_id=?,activated_at=?,version=version+1 where id=?",subscription,ts(NOW),v.id());
            }
            return event;
        });
    }
    private static void expectCode(Runnable action,String code){assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.getCode()).isEqualTo(code));}
    private static void await(CountDownLatch latch){try{if(!latch.await(10,TimeUnit.SECONDS))throw new AssertionError("Latch timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}}
    private void awaitDatabaseLock(String table){
        BooleanSupplier waiting=() -> db.queryForObject("select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and wait_event_type='Lock' and query ilike ?",Integer.class,"%"+table+"%")>0;
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!waiting.getAsBoolean()){if(System.nanoTime()>deadline)throw new AssertionError("Expected real PostgreSQL lock wait on "+table);Thread.yield();}
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time=new AtomicReference<>(NOW);
        void set(Instant value){time.set(value);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return Clock.fixed(time.get(),zone);}
        @Override public Instant instant(){return time.get();}
    }
    @TestConfiguration static class TimeConfiguration {@Bean @Primary MutableClock purchaseClock(){return new MutableClock();}}
}
