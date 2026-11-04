package com.possaas.modules.subscriptionpurchase;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptCommandService;
import com.possaas.modules.subscriptionpurchase.service.PaymentEventClaimService;
import com.possaas.modules.subscriptionpurchase.service.PaymentEventProcessor;
import com.possaas.modules.subscriptionpurchase.service.PurchaseActivationService;
import com.possaas.modules.subscriptionpurchase.service.PurchaseCommandService;
import com.possaas.modules.subscriptionpurchase.service.PurchaseExpirationService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
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
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = {"subscription-purchase.accept-new=true", "app.sepay.enabled=true",
    "app.sepay.provider-account-key=platform-demo", "app.sepay.bank-code=MBBank", "app.sepay.bank-bin=970422",
    "app.sepay.account-number=0123456789", "app.sepay.account-name=POS SAAS DEMO",
    "app.sepay.webhook.secret=" + SubscriptionPaymentSettlementIntegrationTest.SECRET})
@AutoConfigureMockMvc @Import(SubscriptionPaymentSettlementIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubscriptionPaymentSettlementIntegrationTest {
    static final String SECRET = "integration-webhook-secret";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final String WEBHOOK = "/api/v1/webhooks/subscriptions/sepay";
    private static final String SCHEMA = "it_sp4_" + UUID.randomUUID().toString().replace("-", "");
    private static final DateTimeFormatter BANK_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Ho_Chi_Minh"));
    private static final Set<String> OWNER_PERMISSIONS = Set.of("SUBSCRIPTION_PURCHASE_VIEW", "SUBSCRIPTION_PURCHASE_CREATE", "SUBSCRIPTION_PURCHASE_CANCEL");
    private static final AtomicLong WEBHOOK_IDS = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000, 9_000_000));

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema="+SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        p.add("spring.datasource.url", () -> url); p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.schema", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired PurchaseCommandService purchaseCommands;
    @Autowired PaymentAttemptCommandService attempts;
    @Autowired PaymentEventProcessor processor;
    @Autowired PaymentEventClaimService claims;
    @Autowired PurchaseActivationService activation;
    @Autowired PurchaseExpirationService expiration;
    @Autowired PackagePlanRepository packages;
    @Autowired MutableClock clock;
    @MockitoSpyBean AuditService audits;

    @BeforeEach void before() { clock.set(NOW); SecurityContextHolder.clearContext(); reset(audits); }
    @AfterEach void after() { SecurityContextHolder.clearContext(); }
    @AfterAll void removeOnlyOwnSchema() {
        assertThat(SCHEMA).matches("it_sp4_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void signedWebhookIsStoredOnceAndRedeliveriesOnlyCount() throws Exception {
        long id = WEBHOOK_IDS.incrementAndGet();
        String body = payload(id, "PS0000000000000000AAAA", 1000, NOW, "0123456789", "FT" + id);
        send(body).andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"))
            .andExpect(header().string("Cache-Control", "no-store"));
        send(body).andExpect(status().isOk()).andExpect(content().json("{\"success\":true}"));
        assertThat(db.queryForObject("select delivery_count from subscription_payment_events where provider_webhook_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(db.queryForObject("select state from subscription_payment_events where provider_webhook_id=?", String.class, id)).isEqualTo("RECEIVED");
        UUID eventId = db.queryForObject("select id from subscription_payment_events where provider_webhook_id=?", UUID.class, id);
        assertThat(auditCount(eventId, "SUBSCRIPTION_PAYMENT_EVENT_RECEIVED")).isEqualTo(1);
    }

    @Test void badSignatureStaleTimestampAndOversizedBodyStoreNothing() throws Exception {
        long id = WEBHOOK_IDS.incrementAndGet();
        String body = payload(id, "PS0000000000000000BBBB", 1000, NOW, "0123456789", "FT" + id);
        String ts = Long.toString(NOW.getEpochSecond());
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).header("X-SePay-Timestamp", ts)
                .header("X-SePay-Signature", sign(ts, body.replace("1000", "9000"))).content(body))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_AUTHENTICATION_FAILED"));
        String old = Long.toString(NOW.minusSeconds(301).getEpochSecond());
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).header("X-SePay-Timestamp", old)
                .header("X-SePay-Signature", sign(old, body)).content(body))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("WEBHOOK_TIMESTAMP_INVALID"));
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer not-a-sepay-credential").content(body))
            .andExpect(status().isUnauthorized());
        String huge = "{\"pad\":\"" + "x".repeat(70_000) + "\"}";
        send(huge).andExpect(status().isPayloadTooLarge()).andExpect(jsonPath("$.code").value("WEBHOOK_PAYLOAD_TOO_LARGE"));
        assertThat(db.queryForObject("select count(*) from subscription_payment_events where provider_webhook_id=?", Integer.class, id)).isZero();
    }

    @Test void persistenceFailureIsNotAcknowledged() throws Exception {
        long id = WEBHOOK_IDS.incrementAndGet();
        doThrow(new DataAccessResourceFailureException("db down")).when(audits)
            .record(any(), any(), eq("SUBSCRIPTION_PAYMENT_EVENT_RECEIVED"), anyString(), any(), any(), any(), any());
        send(payload(id, "PS0000000000000000CCCC", 1000, NOW, "0123456789", "FT" + id))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("WEBHOOK_PERSISTENCE_UNAVAILABLE"));
        assertThat(db.queryForObject("select count(*) from subscription_payment_events where provider_webhook_id=?", Integer.class, id)).isZero();
    }

    @Test void exactPaymentIsPaidThenActivatedFromFrozenQuote() throws Exception {
        Actor a = owner(); PackagePlan plan = plan("399000", 12L); var purchase = purchase(a, plan);
        String reference = attempt(a, purchase.id());
        plan.setPriceAmount(new BigDecimal("999000")); plan.setMaxStaff(1L); packages.saveAndFlush(plan);
        clock.set(NOW.plusSeconds(120));
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(60), "0123456789", "FT-OK-" + reference))
            .andExpect(status().isOk());
        assertThat(processor.processBatch()).isGreaterThanOrEqualTo(1);

        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(db.queryForObject("select paid_at from subscription_purchases where id=?", Timestamp.class, purchase.id()).toInstant())
            .isEqualTo(NOW.plusSeconds(60));
        assertThat(db.queryForObject("select status from subscription_payment_attempts where reference_code=?", String.class, reference)).isEqualTo("SUCCESS");
        assertThat(db.queryForObject("select state from subscription_payment_events where payment_reference=?", String.class, reference)).isEqualTo("PROCESSED");
        var sub = db.queryForMap("select s.status,s.start_at,s.end_at,s.price_amount,s.feature_snapshot->>'maxStaff' as max_staff from restaurant_subscriptions s "
            + "join subscription_purchases p on p.subscription_id=s.id where p.id=?", purchase.id());
        assertThat(sub.get("status")).isEqualTo("ACTIVE");
        assertThat(((BigDecimal) sub.get("price_amount")).compareTo(new BigDecimal("399000"))).isZero();
        assertThat(sub.get("max_staff")).isEqualTo("12");
        Instant start = ((Timestamp) sub.get("start_at")).toInstant();
        assertThat(start).isEqualTo(NOW.plusSeconds(120));
        assertThat(((Timestamp) sub.get("end_at")).toInstant()).isEqualTo(start.atZone(ZoneId.of("Asia/Ho_Chi_Minh")).plusMonths(1).toInstant());
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_PAID")).isEqualTo(1);
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_ACTIVATED")).isEqualTo(1);
        processor.processBatch();
        activation.activateDueBatch();
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_PAID")).isEqualTo(1);
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void wrongAmountReviewsEventAndPurchaseAndStopsTheQr() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 300000, NOW.plusSeconds(30), "0123456789", "FT-LOW-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        assertThat(eventState(reference)).isEqualTo("REVIEW_REQUIRED:AMOUNT_MISMATCH");
        assertThat(purchaseStatus(purchase.id())).isEqualTo("REVIEW_REQUIRED");
        assertThat(db.queryForObject("select status from subscription_payment_attempts where reference_code=?", String.class, reference)).isEqualTo("CANCELLED");
        assertThat(subscriptions(a)).isZero();
    }

    @Test void unknownReferenceIsReviewedWithoutLinks() throws Exception {
        long id = WEBHOOK_IDS.incrementAndGet();
        send(payload(id, "chuyen tien goi PRO", 399000, NOW, "0123456789", "FT-UNKNOWN-" + id)).andExpect(status().isOk());
        processor.processBatch();
        var row = db.queryForMap("select state,review_reason,purchase_id from subscription_payment_events where provider_webhook_id=?", id);
        assertThat(row.get("state")).isEqualTo("REVIEW_REQUIRED");
        assertThat(row.get("review_reason")).isEqualTo("REFERENCE_UNMATCHED");
        assertThat(row.get("purchase_id")).isNull();
    }

    @Test void moneyAfterCancellationIsReviewedAndPurchaseStaysCancelled() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        signIn(a.user()); purchaseCommands.cancel(purchase.id(), new PurchaseRequests.Cancel(0L, null), null);
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-CAN-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        assertThat(eventState(reference)).isEqualTo("REVIEW_REQUIRED:PURCHASE_CANCELLED");
        assertThat(purchaseStatus(purchase.id())).isEqualTo("CANCELLED");
    }

    @Test void lateWebhookForTimelyTransferActivatesExpiredPurchase() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        clock.set(purchase.quote().expiresAt().plusSeconds(600));
        expiration.expireDueBatch();
        assertThat(purchaseStatus(purchase.id())).isEqualTo("EXPIRED");
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(800), "0123456789", "FT-LATE-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void secondTransferIsDuplicateAndDoesNotExtendSubscription() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-1-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(40), "0123456789", "FT-2-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        assertThat(db.queryForList("select state||':'||coalesce(review_reason,'') from subscription_payment_events where payment_reference=? order by occurred_at",
            String.class, reference)).containsExactly("PROCESSED:", "REVIEW_REQUIRED:DUPLICATE_PAYMENT");
        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void existingSubscriptionBlocksActivationButKeepsPaidEvidence() throws Exception {
        Actor a = owner(); PackagePlan plan = plan("399000", 5L); var purchase = purchase(a, plan); String reference = attempt(a, purchase.id());
        db.update("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code) values(?,?,?,'ACTIVE',?,?,?,'VND')",
            UUID.randomUUID(), a.tenant(), plan.getId(), Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(86400)), plan.getPriceAmount());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-BLK-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        var row = db.queryForMap("select status,review_reason,paid_at from subscription_purchases where id=?", purchase.id());
        assertThat(row.get("status")).isEqualTo("REVIEW_REQUIRED");
        assertThat(row.get("review_reason")).isEqualTo("SUBSCRIPTION_CONFLICT");
        assertThat(row.get("paid_at")).isNotNull();
        assertThat(eventState(reference)).isEqualTo("REVIEW_REQUIRED:SUBSCRIPTION_CONFLICT");
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void technicalActivationFailureIsRetriedFromThePurchaseRow() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        doThrow(new DataAccessResourceFailureException("audit down")).doCallRealMethod().when(audits)
            .record(any(), any(), eq("SUBSCRIPTION_PURCHASE_ACTIVATED"), anyString(), any(), any(), any(), any());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-RETRY-" + reference)).andExpect(status().isOk());
        processor.processBatch();
        var row = db.queryForMap("select status,activation_retry_count,activation_error_code from subscription_purchases where id=?", purchase.id());
        assertThat(row.get("status")).isEqualTo("PAID");
        assertThat(row.get("activation_retry_count")).isEqualTo(1);
        assertThat(row.get("activation_error_code")).isEqualTo("DataAccessResourceFailureException");
        assertThat(subscriptions(a)).isZero();
        clock.set(NOW.plusSeconds(10));
        assertThat(activation.activateDueBatch()).isEqualTo(1);
        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void expiredLeaseAfterCrashIsRecoveredAndProcessedOnce() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-LEASE-" + reference)).andExpect(status().isOk());
        assertThat(claims.claim()).isNotEmpty();
        processor.processBatch();
        assertThat(purchaseStatus(purchase.id())).isEqualTo("PENDING_PAYMENT");
        clock.set(NOW.plusSeconds(61));
        assertThat(processor.processBatch()).isGreaterThanOrEqualTo(1);
        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_PAID")).isEqualTo(1);
    }

    @Test void concurrentWorkersSettleEachEventOnce() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000", 5L)); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "0123456789", "FT-PAR-" + reference)).andExpect(status().isOk());
        var start = new CountDownLatch(1); var pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) runs.add(pool.submit(() -> { start.await(); return processor.processBatch(); }));
            start.countDown();
            for (var run : runs) run.get(30, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertThat(purchaseStatus(purchase.id())).isEqualTo("ACTIVATED");
        assertThat(auditCount(purchase.id(), "SUBSCRIPTION_PURCHASE_PAID")).isEqualTo(1);
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    private ResultActions send(String body) throws Exception {
        SecurityContextHolder.clearContext();
        String ts = Long.toString(clock.instant().getEpochSecond());
        return mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON)
            .header("X-SePay-Timestamp", ts).header("X-SePay-Signature", sign(ts, body)).content(body));
    }
    private static String payload(long id, String content, long amount, Instant occurredAt, String account, String bankRef) {
        return """
            {"id":%d,"gateway":"MBBank","transactionDate":"%s","accountNumber":"%s","subAccount":"","code":null,
             "content":"%s","transferType":"in","description":"test","transferAmount":%d,"accumulated":0,"referenceCode":"%s"}
            """.formatted(id, BANK_TIME.format(occurredAt), account, content, amount, bankRef);
    }
    private static String sign(String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.US_ASCII));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record Actor(CurrentUser user) { UUID tenant() { return user.restaurantId(); } }
    private Actor owner() {
        UUID tenant = UUID.randomUUID(), user = UUID.randomUUID(); String suffix = tenant.toString().replace("-", "");
        db.update("insert into restaurants(id,code,name,status,timezone,currency_code,public_order_token) values(?,?,?,'ACTIVE','Asia/Ho_Chi_Minh','VND',?)", tenant, "SP_"+suffix, "Owner restaurant", suffix);
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Owner',?,'not-used-for-login' from roles where code='OWNER' and restaurant_id is null", user, tenant, suffix+"@example.com");
        return new Actor(new CurrentUser(user, tenant, suffix+"@example.com", "OWNER", OWNER_PERMISSIONS));
    }
    private PackagePlan plan(String amount, Long maxStaff) {
        PackagePlan p = new PackagePlan(); p.setCode("SP_"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)); p.setName("Purchase plan");
        p.setPriceAmount(new BigDecimal(amount)); p.setCurrencyCode("VND"); p.setBillingCycleMonths((short) 1); p.setMaxStaff(maxStaff); p.setActive(true);
        return packages.saveAndFlush(p);
    }
    private PurchaseResponses.View purchase(Actor a, PackagePlan p) {
        signIn(a.user()); return purchaseCommands.create(new PurchaseRequests.Create(p.getCode()), "purchase-" + UUID.randomUUID(), null).body();
    }
    private String attempt(Actor a, UUID purchaseId) {
        signIn(a.user()); return attempts.create(purchaseId, "attempt-" + UUID.randomUUID(), null).body().referenceCode();
    }
    private static void signIn(CurrentUser u) {
        List<SimpleGrantedAuthority> grants = new ArrayList<>(); grants.add(new SimpleGrantedAuthority("ROLE_"+u.role()));
        u.permissions().forEach(p -> grants.add(new SimpleGrantedAuthority(p)));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(u, null, grants));
    }
    private String purchaseStatus(UUID id) { return db.queryForObject("select status from subscription_purchases where id=?", String.class, id); }
    private String eventState(String reference) {
        return db.queryForObject("select state||':'||coalesce(review_reason,'') from subscription_payment_events where payment_reference=?", String.class, reference);
    }
    private int subscriptions(Actor a) { return db.queryForObject("select count(*) from restaurant_subscriptions where restaurant_id=?", Integer.class, a.tenant()); }
    private int auditCount(UUID id, String action) { return db.queryForObject("select count(*) from audit_logs where entity_id=? and action_code=?", Integer.class, id, action); }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        void set(Instant value) { time.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time.get(), zone); }
        @Override public Instant instant() { return time.get(); }
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary MutableClock settlementClock() { return new MutableClock(); } }
}
