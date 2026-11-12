package com.possaas.modules.subscriptionpurchase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseResponses;
import com.possaas.modules.subscriptionpurchase.service.PaymentAttemptCommandService;
import com.possaas.modules.subscriptionpurchase.service.PaymentEventProcessor;
import com.possaas.modules.subscriptionpurchase.service.PurchaseCommandService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {"subscription-purchase.accept-new=true", "app.sepay.enabled=true",
    "app.sepay.provider-account-key=platform-demo", "app.sepay.bank-code=MBBank", "app.sepay.bank-bin=970422",
    "app.sepay.account-number=0123456789", "app.sepay.account-name=XAY POS DEMO",
    "app.sepay.webhook.secret=" + AdminPaymentReviewIntegrationTest.SECRET})
@AutoConfigureMockMvc @Import(AdminPaymentReviewIntegrationTest.TimeConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminPaymentReviewIntegrationTest {
    static final String SECRET = "integration-webhook-secret";
    private static final Instant NOW = Instant.parse("2026-11-02T03:00:00Z");
    private static final String WEBHOOK = "/api/v1/webhooks/subscriptions/sepay";
    private static final String SCHEMA = "it_apr_" + UUID.randomUUID().toString().replace("-", "");
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
    @Autowired PackagePlanRepository packages;
    @Autowired MutableClock clock;
    private UUID adminId;

    @BeforeAll void createAdmin() {
        adminId = UUID.randomUUID();
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,null,id,'Ops Admin',?,'not-used' from roles where code='SUPER_ADMIN' and restaurant_id is null",
            adminId, "ops-" + adminId + "@example.com");
    }
    @BeforeEach void before() { clock.set(NOW); SecurityContextHolder.clearContext(); }
    @AfterEach void after() { SecurityContextHolder.clearContext(); }
    @AfterAll void removeOnlyOwnSchema() {
        assertThat(SCHEMA).matches("it_apr_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void ownerCannotReadTheReviewQueue() throws Exception {
        Actor a = owner();
        mvc.perform(get("/api/v1/admin/payment-events").with(authentication(token(a.user()))))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/payment-events").with(authentication(admin("SUBSCRIPTION_VIEW"))))
            .andExpect(status().isForbidden());
    }

    @Test void wrongAmountCanBeAcceptedManuallyAndActivates() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000")); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 398000, NOW.plusSeconds(30), "FT-LOW-" + reference));
        processor.processBatch();
        UUID eventId = eventId(reference);

        mvc.perform(get("/api/v1/admin/payment-events").param("state", "REVIEW_REQUIRED").param("q", reference)
                .with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].reviewReason").value("AMOUNT_MISMATCH"))
            .andExpect(jsonPath("$.content[0].purchaseStatus").value("REVIEW_REQUIRED"))
            .andExpect(jsonPath("$.content[0].restaurant.id").value(a.tenant().toString()));
        mvc.perform(get("/api/v1/admin/payment-reviews/counts").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.paymentEvents").isNumber());

        mvc.perform(post("/api/v1/admin/payment-events/{id}/accept", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"short\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/admin/payment-events/{id}/accept", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Khach chuyen thieu 1.000d, chap nhan\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CLOSED"))
            .andExpect(jsonPath("$.reviewerName").value("Ops Admin"))
            .andExpect(jsonPath("$.purchaseStatus").value("ACTIVATED"));
        assertThat(subscriptions(a)).isEqualTo(1);

        mvc.perform(post("/api/v1/admin/payment-events/{id}/accept", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Bam lai lan thu hai\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PAYMENT_EVENT_NOT_IN_REVIEW"));
    }

    @Test void unmatchedTransferIsLinkedToTheChosenPurchase() throws Exception {
        Actor a = owner(); var purchase = purchase(a, plan("399000")); attempt(a, purchase.id());
        long id = WEBHOOK_IDS.incrementAndGet();
        send(payload(id, "chuyen tien goi PRO", 399000, NOW.plusSeconds(30), "FT-UNK-" + id));
        processor.processBatch();
        UUID eventId = db.queryForObject("select id from subscription_payment_events where provider_webhook_id=?", UUID.class, id);

        mvc.perform(post("/api/v1/admin/payment-events/{id}/accept", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Khach ghi sai noi dung chuyen khoan\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_REQUIRED"));
        mvc.perform(post("/api/v1/admin/payment-events/{id}/accept", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"purchaseId\":\"%s\",\"note\":\"Khach ghi sai noi dung chuyen khoan\"}".formatted(purchase.id()))
                .with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.purchaseId").value(purchase.id().toString()))
            .andExpect(jsonPath("$.purchaseStatus").value("ACTIVATED"));
        assertThat(subscriptions(a)).isEqualTo(1);
    }

    @Test void refundedTransferIsClosedWithNoteAndReference() throws Exception {
        long id = WEBHOOK_IDS.incrementAndGet();
        send(payload(id, "nham tai khoan", 50000, NOW, "FT-REF-" + id));
        processor.processBatch();
        UUID eventId = db.queryForObject("select id from subscription_payment_events where provider_webhook_id=?", UUID.class, id);

        mvc.perform(post("/api/v1/admin/payment-events/{id}/close", eventId).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Da hoan tien cho khach\",\"resolutionReference\":\"FT-HOAN-001\"}")
                .with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("CLOSED"))
            .andExpect(jsonPath("$.resolutionReference").value("FT-HOAN-001"));
        assertThat(db.queryForObject("select count(*) from audit_logs where entity_id=? and action_code='SUBSCRIPTION_PAYMENT_REVIEW_CLOSED'",
            Integer.class, eventId)).isEqualTo(1);
    }

    @Test void blockedPaidPurchaseActivatesAfterConflictIsCleared() throws Exception {
        Actor a = owner(); PackagePlan plan = plan("399000"); var purchase = purchase(a, plan); String reference = attempt(a, purchase.id());
        UUID blocking = UUID.randomUUID();
        db.update("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code) values(?,?,?,'ACTIVE',?,?,?,'VND')",
            blocking, a.tenant(), plan.getId(), Timestamp.from(NOW.minusSeconds(86400)), Timestamp.from(NOW.plusSeconds(86400)), plan.getPriceAmount());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 399000, NOW.plusSeconds(30), "FT-BLK-" + reference));
        processor.processBatch();
        assertThat(purchaseStatus(purchase.id())).isEqualTo("REVIEW_REQUIRED");

        mvc.perform(post("/api/v1/admin/subscription-purchases/{id}/close", purchase.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isBadRequest());

        db.update("update restaurant_subscriptions set status='CANCELLED', cancelled_at=? where id=?", Timestamp.from(NOW), blocking);
        mvc.perform(post("/api/v1/admin/subscription-purchases/{id}/resume-activation", purchase.id())
                .with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.purchase.status").value("ACTIVATED"))
            .andExpect(jsonPath("$.attempts.length()").value(1))
            .andExpect(jsonPath("$.events[0].state").value("RETRY_PENDING"));
        processor.processBatch();
        assertThat(db.queryForObject("select state from subscription_payment_events where payment_reference=?", String.class, reference))
            .isEqualTo("PROCESSED");
    }

    @Test void reviewedUnpaidPurchaseCanBeClosedSoTheOwnerCanBuyAgain() throws Exception {
        Actor a = owner(); PackagePlan plan = plan("399000"); var purchase = purchase(a, plan); String reference = attempt(a, purchase.id());
        send(payload(WEBHOOK_IDS.incrementAndGet(), reference, 100000, NOW.plusSeconds(30), "FT-PART-" + reference));
        processor.processBatch();
        assertThat(purchaseStatus(purchase.id())).isEqualTo("REVIEW_REQUIRED");

        mvc.perform(post("/api/v1/admin/subscription-purchases/{id}/resume-activation", purchase.id())
                .with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PURCHASE_NOT_RESUMABLE"));
        mvc.perform(post("/api/v1/admin/subscription-purchases/{id}/close", purchase.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"Khach chuyen thieu, da hoan 100.000d\"}").with(authentication(admin("SUBSCRIPTION_PAYMENT_REVIEW"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.purchase.status").value("CLOSED"));

        mvc.perform(get("/api/v1/admin/subscription-purchases").param("restaurantId", a.tenant().toString())
                .with(authentication(admin("SUBSCRIPTION_VIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].status").value("CLOSED"))
            .andExpect(jsonPath("$.content[0].packageCode").value(plan.getCode()))
            .andExpect(jsonPath("$.content[0].restaurant.code").isString());
        assertThat(purchase(a, plan).status().name()).isEqualTo("PENDING_PAYMENT");
    }

    private void send(String body) throws Exception {
        SecurityContextHolder.clearContext();
        String ts = Long.toString(clock.instant().getEpochSecond());
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON)
            .header("X-SePay-Timestamp", ts).header("X-SePay-Signature", sign(ts, body)).content(body))
            .andExpect(status().isOk());
    }
    private static String payload(long id, String content, long amount, Instant occurredAt, String bankRef) {
        return """
            {"id":%d,"gateway":"MBBank","transactionDate":"%s","accountNumber":"0123456789","subAccount":"","code":null,
             "content":"%s","transferType":"in","description":"test","transferAmount":%d,"accumulated":0,"referenceCode":"%s"}
            """.formatted(id, BANK_TIME.format(occurredAt), content, amount, bankRef);
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
        db.update("insert into restaurants(id,code,name,status,timezone,currency_code,public_order_token) values(?,?,?,'ACTIVE','Asia/Ho_Chi_Minh','VND',?)", tenant, "AP_"+suffix, "Review restaurant", suffix);
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Owner',?,'not-used-for-login' from roles where code='OWNER' and restaurant_id is null", user, tenant, suffix+"@example.com");
        return new Actor(new CurrentUser(user, tenant, suffix+"@example.com", "OWNER", OWNER_PERMISSIONS));
    }
    private PackagePlan plan(String amount) {
        PackagePlan p = new PackagePlan(); p.setCode("AP_"+UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT)); p.setName("Review plan");
        p.setPriceAmount(new BigDecimal(amount)); p.setCurrencyCode("VND"); p.setBillingCycleMonths((short) 1); p.setMaxStaff(5L); p.setActive(true);
        return packages.saveAndFlush(p);
    }
    private PurchaseResponses.View purchase(Actor a, PackagePlan p) {
        signIn(a.user()); return purchaseCommands.create(new PurchaseRequests.Create(p.getCode()), "purchase-" + UUID.randomUUID(), null).body();
    }
    private String attempt(Actor a, UUID purchaseId) {
        signIn(a.user()); return attempts.create(purchaseId, "attempt-" + UUID.randomUUID(), null).body().referenceCode();
    }
    private Authentication admin(String... permissions) {
        return token(new CurrentUser(adminId, null, "ops@example.com", "SUPER_ADMIN", Set.of(permissions)));
    }
    private static Authentication token(CurrentUser u) {
        List<SimpleGrantedAuthority> grants = new ArrayList<>(); grants.add(new SimpleGrantedAuthority("ROLE_"+u.role()));
        u.permissions().forEach(p -> grants.add(new SimpleGrantedAuthority(p)));
        return new UsernamePasswordAuthenticationToken(u, null, grants);
    }
    private static void signIn(CurrentUser u) { SecurityContextHolder.getContext().setAuthentication(token(u)); }
    private UUID eventId(String reference) {
        return db.queryForObject("select id from subscription_payment_events where payment_reference=?", UUID.class, reference);
    }
    private String purchaseStatus(UUID id) { return db.queryForObject("select status from subscription_purchases where id=?", String.class, id); }
    private int subscriptions(Actor a) { return db.queryForObject("select count(*) from restaurant_subscriptions where restaurant_id=?", Integer.class, a.tenant()); }

    static class MutableClock extends Clock {
        private final AtomicReference<Instant> time = new AtomicReference<>(NOW);
        void set(Instant value) { time.set(value); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(time.get(), zone); }
        @Override public Instant instant() { return time.get(); }
    }
    @TestConfiguration static class TimeConfiguration { @Bean @Primary MutableClock reviewClock() { return new MutableClock(); } }
}
