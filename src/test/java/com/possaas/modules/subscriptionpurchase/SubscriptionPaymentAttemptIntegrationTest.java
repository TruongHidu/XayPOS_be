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
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentInstructionProvider;
import com.possaas.modules.subscriptionpurchase.application.port.PaymentProviderUnavailableException;
import com.possaas.modules.subscriptionpurchase.config.PurchaseProperties;
import com.possaas.modules.subscriptionpurchase.dto.PaymentAttemptResponses;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptCommandService;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptQueryService;
import com.possaas.modules.subscriptionpurchase.service.PaymentReferenceGenerator;
import com.possaas.modules.subscriptionpurchase.service.PurchaseCommandService;
import com.possaas.modules.subscriptionpurchase.service.PurchaseExpirationService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(properties = {"subscription-purchase.accept-new=true", "app.sepay.enabled=true",
    "app.sepay.provider-account-key=platform-demo", "app.sepay.bank-code=MBBank", "app.sepay.bank-bin=970422",
    "app.sepay.account-number=0123456789", "app.sepay.account-name=POS SAAS DEMO"})
@AutoConfigureMockMvc @Import(SubscriptionPaymentAttemptIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionPaymentAttemptIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final String ROOT = "/api/v1/subscription-purchases";
    private static final String SCHEMA = "it_sp3_" + UUID.randomUUID().toString().replace("-", "");
    private static final Set<String> OWNER_PERMISSIONS = Set.of("SUBSCRIPTION_PURCHASE_VIEW", "SUBSCRIPTION_PURCHASE_CREATE", "SUBSCRIPTION_PURCHASE_CANCEL");

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
    @Autowired PurchaseCommandService purchaseCommands;
    @Autowired PaymentAttemptCommandService commands;
    @Autowired PaymentAttemptQueryService queries;
    @Autowired PurchaseExpirationService expiration;
    @Autowired PurchaseProperties settings;
    @Autowired PackagePlanRepository packages;
    @Autowired MutableClock clock;
    @MockitoSpyBean PaymentInstructionProvider provider;
    @MockitoSpyBean PaymentReferenceGenerator references;

    @BeforeEach void before() { clock.set(NOW); settings.setAcceptNew(true); SecurityContextHolder.clearContext(); reset(provider, references); }
    @AfterEach void after() { SecurityContextHolder.clearContext(); }
    @AfterAll void removeOnlyOwnSchema() {
        assertThat(SCHEMA).matches("it_sp3_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void createsPendingAttemptWithFrozenSepayInstructions() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000"), "purchase-key-0001");
        assertThat(purchase.allowedActions()).containsExactly("VIEW_PURCHASE", "CREATE_PAYMENT_ATTEMPT", "CANCEL_PURCHASE");
        String response = mvc.perform(post(ROOT + "/" + purchase.id() + "/payment-attempts").with(authentication(token(a.user())))
                .header("Idempotency-Key", "attempt-key-0001").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isCreated()).andExpect(header().string("Idempotency-Replayed", "false"))
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        JsonNode body = json.readTree(response);
        String reference = body.path("referenceCode").asText();
        assertThat(reference).matches("PS[0-9A-F]{20}");
        assertThat(body.path("status").asText()).isEqualTo("PENDING");
        assertThat(body.path("provider").asText()).isEqualTo("SEPAY");
        assertThat(body.path("amount").asLong()).isEqualTo(399000);
        assertThat(Instant.parse(body.path("expiresAt").asText())).isEqualTo(purchase.quote().expiresAt());
        JsonNode instructions = body.path("instructions");
        assertThat(instructions.path("transferContent").asText()).isEqualTo(reference);
        assertThat(instructions.path("accountNumber").asText()).isEqualTo("0123456789");
        assertThat(instructions.path("qrPayload").asText()).contains("5406399000").contains(reference);
        assertThat(instructions.path("qrImageUrl").asText()).contains("des=" + reference).contains("amount=399000");
        UUID id = UUID.fromString(body.path("id").asText());
        assertThat(db.queryForObject("select provider_account_key from subscription_payment_attempts where id=?", String.class, id)).isEqualTo("platform-demo");
        assertThat(db.queryForObject("select expires_at from subscription_payment_attempts where id=?", Timestamp.class, id).toInstant())
            .isEqualTo(purchase.quote().expiresAt());
        assertThat(auditCount(id, "SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED")).isEqualTo(1);
    }

    @Test void sameKeyReplaysOriginalBodyEvenAfterExpiry() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0002");
        var first = attempt(a, purchase.id(), "attempt-key-0002");
        assertThat(first.replayed()).isFalse();
        clock.set(purchase.quote().expiresAt().plusSeconds(60));
        var again = attempt(a, purchase.id(), "attempt-key-0002");
        assertThat(again.replayed()).isTrue();
        assertThat(again.body()).isEqualTo(first.body());
        assertThat(count("subscription_payment_attempts", a.tenant())).isEqualTo(1);
    }

    @Test void newKeyWhilePendingConflictsAndKeyIsScopedToPurchase() {
        Actor a = owner(); var plan = plan("100"); var first = purchase(a, plan, "purchase-key-0003");
        attempt(a, first.id(), "attempt-key-0003");
        expectCode(() -> attempt(a, first.id(), "attempt-key-0004"), "PAYMENT_ATTEMPT_PENDING");
        purchaseCommands.cancel(first.id(), new PurchaseRequests.Cancel(0L, null), null);
        assertThat(db.queryForObject("select status from subscription_payment_attempts where purchase_id=?", String.class, first.id())).isEqualTo("CANCELLED");
        expectCode(() -> attempt(a, first.id(), "attempt-key-0005"), "PAYMENT_ATTEMPT_NOT_ALLOWED");
        var second = purchase(a, plan, "purchase-key-0004");
        expectCode(() -> attempt(a, second.id(), "attempt-key-0003"), "IDEMPOTENCY_KEY_REUSED");
        assertThat(count("subscription_payment_attempts", a.tenant())).isEqualTo(1);
    }

    @Test void concurrentDifferentKeysProduceOnePendingAttempt() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0005");
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String key : List.of("parallel-key-0001", "parallel-key-0002")) results.add(pool.submit(() -> {
                start.await(); signIn(a.user());
                try { attempt(a, purchase.id(), key); return "CREATED"; }
                catch (BusinessException conflict) { return conflict.getCode(); }
                finally { SecurityContextHolder.clearContext(); }
            }));
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (var result : results) outcomes.add(result.get(20, TimeUnit.SECONDS));
            assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "PAYMENT_ATTEMPT_PENDING");
        } finally { pool.shutdownNow(); }
        assertThat(count("subscription_payment_attempts", a.tenant())).isEqualTo(1);
    }

    @Test void expiryIsProjectedOnReadAndCommittedByCommand() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0006");
        var created = attempt(a, purchase.id(), "attempt-key-0006").body();
        clock.set(purchase.quote().expiresAt());
        signIn(a.user());
        var current = queries.current(purchase.id());
        assertThat(current.paymentAttempt().id()).isEqualTo(created.id());
        assertThat(current.paymentAttempt().displayStatus().name()).isEqualTo("EXPIRED");
        assertThat(storedAttemptStatus(created.id())).isEqualTo("PENDING");
        expectCode(() -> attempt(a, purchase.id(), "attempt-key-0007"), "PURCHASE_PERIOD_EXPIRED");
        assertThat(storedAttemptStatus(created.id())).isEqualTo("EXPIRED");
        assertThat(db.queryForObject("select status from subscription_purchases where id=?", String.class, purchase.id())).isEqualTo("EXPIRED");
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_EXPIRED")).isEqualTo(1);
        expectCode(() -> attempt(a, purchase.id(), "attempt-key-0008"), "PURCHASE_PERIOD_EXPIRED");
    }

    @Test void currentIsNullBeforeFirstAttemptAndOtherTenantsGet404() throws Exception {
        Actor a = owner(), other = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0007");
        signIn(a.user());
        assertThat(queries.current(purchase.id()).paymentAttempt()).isNull();
        mvc.perform(get(ROOT + "/" + purchase.id() + "/payment-attempts/current").with(authentication(token(other.user()))))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_FOUND"));
        mvc.perform(post(ROOT + "/" + purchase.id() + "/payment-attempts").with(authentication(token(other.user())))
                .header("Idempotency-Key", "attempt-key-0009").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        assertThat(count("subscription_payment_attempts", a.tenant())).isZero();
    }

    @Test void providerFailureWritesNothingAndSameKeyLaterSucceeds() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0008");
        doThrow(new PaymentProviderUnavailableException("down")).when(provider).prepare(any());
        expectCode(() -> attempt(a, purchase.id(), "attempt-key-0010"), "PAYMENT_PROVIDER_UNAVAILABLE");
        assertThat(count("subscription_payment_attempts", a.tenant())).isZero();
        reset(provider);
        assertThat(attempt(a, purchase.id(), "attempt-key-0010").replayed()).isFalse();
    }

    @Test void providerIsCalledOutsideAnyTransaction() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0009");
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        doAnswer(call -> { inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive()); return call.callRealMethod(); })
            .when(provider).prepare(any());
        attempt(a, purchase.id(), "attempt-key-0011");
        assertThat(inTransaction).isFalse();
    }

    @Test void referenceCollisionRetriesWithFreshReference() {
        Actor a = owner(), b = owner();
        var taken = attempt(a, purchase(a, plan("100"), "purchase-key-0010").id(), "attempt-key-0012").body().referenceCode();
        var purchase = purchase(b, plan("100"), "purchase-key-0011");
        doReturn(taken).doCallRealMethod().when(references).next();
        var created = attempt(b, purchase.id(), "attempt-key-0013").body();
        assertThat(created.referenceCode()).isNotEqualTo(taken).matches("PS[0-9A-F]{20}");
        assertThat(created.instructions().transferContent()).isEqualTo(created.referenceCode());
    }

    @Test void flagOffBlocksNewAttemptsButKeepsReplayAndReads() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0012");
        var first = attempt(a, purchase.id(), "attempt-key-0014");
        settings.setAcceptNew(false);
        assertThat(attempt(a, purchase.id(), "attempt-key-0014").body()).isEqualTo(first.body());
        expectCode(() -> attempt(a, purchase.id(), "attempt-key-0015"), "PURCHASE_UNAVAILABLE");
        signIn(a.user());
        assertThat(queries.current(purchase.id()).paymentAttempt().id()).isEqualTo(first.body().id());
    }

    @Test void requestGuardsRejectStaffUnknownFieldsAndMissingKey() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0013");
        String path = ROOT + "/" + purchase.id() + "/payment-attempts";
        mvc.perform(post(path).with(authentication(token(a.user()))).header("Idempotency-Key", "attempt-key-0016")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
        mvc.perform(post(path).with(authentication(token(a.user()))).contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        var staff = new CurrentUser(a.user().userId(), a.tenant(), a.user().email(), "STAFF", OWNER_PERMISSIONS);
        mvc.perform(post(path).with(authentication(token(staff))).header("Idempotency-Key", "attempt-key-0017")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isForbidden());
        assertThat(count("subscription_payment_attempts", a.tenant())).isZero();
    }

    @Test void expirationBatchExpiresSuspendedRestaurantsOnce() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0014");
        var created = attempt(a, purchase.id(), "attempt-key-0018").body();
        db.update("update restaurants set status='SUSPENDED' where id=?", a.tenant());
        clock.set(purchase.quote().expiresAt().plusSeconds(1));
        expiration.expireDueBatch();
        expiration.expireDueBatch();
        assertThat(db.queryForObject("select status from subscription_purchases where id=?", String.class, purchase.id())).isEqualTo("EXPIRED");
        assertThat(storedAttemptStatus(created.id())).isEqualTo("EXPIRED");
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_EXPIRED")).isEqualTo(1);
    }

    @Test void databaseRejectsLegacyReferenceFormat() {
        Actor a = owner(); var purchase = purchase(a, plan("100"), "purchase-key-0015");
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> db.update("""
            insert into subscription_payment_attempts(id,restaurant_id,purchase_id,status,provider_account_key,reference_code,amount,currency_code,expires_at,instructions,idempotency_key,request_hash,creation_response,created_at,updated_at)
            values(?,?,?,'PENDING','platform-demo','PS0123456789ABCDEF01234567',100,'VND',?,cast('{}' as jsonb),'legacy-key-0001',?,cast('{}' as jsonb),?,?)
            """, id, a.tenant(), purchase.id(), Timestamp.from(purchase.quote().expiresAt()), "0".repeat(64), Timestamp.from(NOW), Timestamp.from(NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private record Actor(CurrentUser user) { UUID tenant() { return user.restaurantId(); } }
    private Actor owner() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(); String suffix = tenant.toString().replace("-", "");
        db.update("insert into restaurants(id,code,name,status,timezone,currency_code,public_order_token) values(?,?,?,'ACTIVE','Asia/Ho_Chi_Minh','VND',?)", tenant, "SP_"+suffix, "Owner restaurant", suffix);
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Owner',?,'not-used-for-login' from roles where code='OWNER' and restaurant_id is null", user, tenant, suffix+"@example.com");
        return new Actor(new CurrentUser(user, tenant, suffix+"@example.com", "OWNER", OWNER_PERMISSIONS));
    }
    private PackagePlan plan(String amount) {
        PackagePlan p = new PackagePlan(); p.setCode("SP_"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)); p.setName("Purchase plan");
        p.setPriceAmount(new BigDecimal(amount)); p.setCurrencyCode("VND"); p.setBillingCycleMonths((short) 1); p.setMaxStaff(5L); p.setActive(true);
        return packages.saveAndFlush(p);
    }
    private PurchaseResponses.View purchase(Actor a, PackagePlan p, String key) {
        signIn(a.user()); return purchaseCommands.create(new PurchaseRequests.Create(p.getCode()), key, null).body();
    }
    private PaymentAttemptResponses.Creation attempt(Actor a, UUID purchaseId, String key) {
        signIn(a.user()); return commands.create(purchaseId, key, null);
    }
    private static UsernamePasswordAuthenticationToken token(CurrentUser user) {
        List<SimpleGrantedAuthority> grants = new ArrayList<>(); grants.add(new SimpleGrantedAuthority("ROLE_"+user.role()));
        user.permissions().forEach(p -> grants.add(new SimpleGrantedAuthority(p)));
        return new UsernamePasswordAuthenticationToken(user, null, grants);
    }
    private static void signIn(CurrentUser u) { SecurityContextHolder.getContext().setAuthentication(token(u)); }
    private int count(String table, UUID tenant) { return db.queryForObject("select count(*) from "+table+" where restaurant_id=?", Integer.class, tenant); }
    private int auditCount(UUID id, String action) { return db.queryForObject("select count(*) from audit_logs where entity_id=? and action_code=?", Integer.class, id, action); }
    private String storedAttemptStatus(UUID id) { return db.queryForObject("select status from subscription_payment_attempts where id=?", String.class, id); }
    private static void expectCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(code));
    }
    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        void set(Instant value) { time.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time.get(), zone); }
        @Override public Instant instant() { return time.get(); }
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary MutableClock attemptClock() { return new MutableClock(); } }
}
