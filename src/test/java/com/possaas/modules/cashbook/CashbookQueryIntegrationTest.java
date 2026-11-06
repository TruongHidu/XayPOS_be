package com.possaas.modules.cashbook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.jpa.show-sql=false",
    "app.jobs.subscription-expiration.enabled=false",
    "app.bootstrap.super-admin.enabled=false",
    "logging.level.root=WARN",
    "app.cors.allowed-origins=http://localhost:3000"
})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import(CashbookQueryIntegrationTest.FixedTime.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CashbookQueryIntegrationTest {
    private static final String SCHEMA = "it_cash_query_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");
    private static final Set<String> BOTH = Set.of("REPORT_DAILY_VIEW", "REPORT_DETAIL_VIEW");

    @TestConfiguration
    static class FixedTime {
        @Bean @Primary Clock reportClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        String base = requireTestEnvironment("DB_URL");
        String username = requireTestEnvironment("DB_USERNAME");
        String password = requireTestEnvironment("DB_PASSWORD");
        String url = base.contains("currentSchema=")
            ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.datasource.username", () -> username);
        properties.add("spring.datasource.password", () -> password);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    private static String requireTestEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be explicitly set to a dedicated PostgreSQL test database");
        }
        return value;
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired RestaurantRepository restaurants;
    @Autowired PackagePlanRepository packages;
    @Autowired RestaurantSubscriptionRepository subscriptions;

    @AfterAll
    void dropOnlyOwnSchema() {
        if (!SCHEMA.matches("it_cash_query_[0-9a-f]{32}")) throw new IllegalStateException("Not a report test schema");
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }


    private static final String BASE = "/api/v1/cashbook/";
    private static final Set<String> PERMS = Set.of("CASHBOOK_VIEW","CASHBOOK_CREATE","CASHBOOK_VOID","INVENTORY_VIEW");
    private static final Set<String> FEATURES = Set.of("DETAIL_REPORT","INVENTORY_MANAGEMENT");

    @Test void successfulPaymentsUsePaidTimeWithoutCompletedOrderOrDuplicateAmounts() throws Exception {
        var r=fixture("VND",FEATURES);
        var old=order(r,"OLD","READY","PAID",NOW.minusSeconds(86400),null,null,"900.00","VND","CASHIER");
        payment(r,old,"100.00","CASH","SUCCESS",NOW,"VND");
        payment(r,old,"100.00","TRANSFER","SUCCESS",NOW,"VND");
        payment(r,old,"900.00","CASH","PENDING",null,"VND");
        payment(r,old,"900.00","CASH","FAILED",null,"VND");
        payment(r,old,"900.00","CASH","CANCELLED",null,"VND");
        payment(r,old,"800.00","CASH","SUCCESS",NOW.minusSeconds(86400),"VND");
        var s=getJson(range(BASE+"summary"),r,PERMS,200).path("data");
        assertMoney(s,"totalIncomeAmount","200.00"); assertMoney(s,"netCashFlowAmount","200.00");
        assertThat(s.path("incomeEntryCount").asLong()).isEqualTo(2);
        var rows=getJson(range(BASE+"entries"),r,PERMS,200).at("/data/content");
        assertThat(rows).hasSize(2); assertThat(rows.get(0).path("sourceType").asText()).isEqualTo("ORDER_PAYMENT");
        assertThat(rows.toString()).doesNotContain("providerTransactionId","customerPhone","customerName","password");
        assertMoney(getJson(range(BASE+"summary")+"&paymentMethod=TRANSFER",r,PERMS,200).path("data"),"totalIncomeAmount","100.00");
        assertMoney(getJson(range(BASE+"summary")+"&type=EXPENSE",r,PERMS,200).path("data"),"totalIncomeAmount","0.00");
    }

    @Test void summarySeriesAndPagedEntriesReconcileAcrossCategoriesCurrenciesAndActors() throws Exception {
        var r=fixture("VND",FEATURES);
        var o=order(r,"COLLECTION","COMPLETED","PAID",NOW,NOW,null,"1000.00","VND","WAITER");
        payment(r,o,"1000.00","CASH","SUCCESS",NOW,"VND");
        create(r,"RENT","300.00","VND",null,"TRANSFER"); create(r,"UTILITIES","200.00","VND",null,"CASH");
        create(r,"OTHER","30.00","USD",null,"CASH");
        var other=fixture("VND",FEATURES);create(other,"RENT","999.00","VND",null,"CASH");
        var s=getJson(range(BASE+"summary"),r,PERMS,200).path("data");
        assertMoney(s,"totalIncomeAmount","1000.00");assertMoney(s,"totalExpenseAmount","500.00");assertMoney(s,"netCashFlowAmount","500.00");
        var rows=getJson(range(BASE+"entries")+"&size=1&sortBy=amount&direction=asc",r,PERMS,200);
        assertThat(rows.at("/data/totalElements").asLong()).isEqualTo(3);
        java.math.BigDecimal income=BigDecimal.ZERO,expense=BigDecimal.ZERO;
        for(int page=0;page<3;page++) {
            var row=getJson(range(BASE+"entries")+"&size=1&page="+page+"&sortBy=amount&direction=asc",r,PERMS,200).at("/data/content/0");
            if(row.path("type").asText().equals("INCOME")) income=income.add(row.path("amount").decimalValue()); else expense=expense.add(row.path("amount").decimalValue());
        }
        assertThat(income).isEqualByComparingTo("1000.00");assertThat(expense).isEqualByComparingTo("500.00");
        var series=getJson(BASE+"series?fromDate=2026-10-28&toDate=2026-10-30",r,PERMS,200).path("data");
        assertThat(series).hasSize(3);assertMoney(series.get(0),"totalIncomeAmount","0.00");assertMoney(series.get(1),"netCashFlowAmount","500.00");
        assertMoney(getJson(range(BASE+"summary")+"&categoryCode=RENT",r,PERMS,200).path("data"),"totalExpenseAmount","300.00");
        assertMoney(getJson(range(BASE+"summary")+"&currencyCode=USD",r,PERMS,200).path("data"),"totalExpenseAmount","30.00");
        assertMoney(getJson(range(BASE+"summary")+"&sourceType=EXPENSE_VOUCHER",r,PERMS,200).path("data"),"netCashFlowAmount","-500.00");
        var id=actor(r,PERMS).userId();assertMoney(getJson(range(BASE+"summary")+"&createdBy="+id,r,PERMS,200).path("data"),"totalExpenseAmount","500.00");
        assertMoney(getJson(range(BASE+"summary")+"&q=no-such-code",r,PERMS,200).path("data"),"totalIncomeAmount","0.00");
    }

    @Test void confirmedReceiptsAreNotExpensesAndReceiptTotalsNeverMultiplyLinkedPayments() throws Exception {
        var r=fixture("VND",FEATURES);var receipt=receipt(r,"CONFIRMED","RECEIPT","VND");
        assertMoney(getJson(range(BASE+"summary"),r,PERMS,200).path("data"),"totalExpenseAmount","0.00");
        create(r,"INGREDIENT_PURCHASE","100.00","VND",receipt,"CASH");create(r,"INGREDIENT_PURCHASE","200.00","VND",receipt,"CASH");
        var s=getJson(range(BASE+"summary"),r,PERMS,200).path("data");
        assertMoney(s,"totalExpenseAmount","300.00");assertMoney(s,"receiptLinkedExpenseAmount","300.00");assertMoney(s,"otherExpenseAmount","0.00");
        var row=getJson(range(BASE+"stock-receipts"),r,PERMS,200).at("/data/content/0");
        assertThat(row.path("linkedPostedExpenseCount").asLong()).isEqualTo(2);
        assertMoney(row,"totalCost","600.00");assertMoney(row,"recordedExpenseAmountAllTime","300.00");
        long ledger=count("inventory_transactions",r);assertThat(ledger).isZero();
    }

    @Test void legacyReceiptCurrencyStaysUnknownAndIsNotReinterpretedAfterRestaurantCurrencyChanges() throws Exception {
        var r=fixture("VND",FEATURES);var receipt=receipt(r,"CONFIRMED","RECEIPT",null);
        create(r,"INGREDIENT_PURCHASE","100.00","VND",receipt,"CASH");
        var row=getJson(range(BASE+"stock-receipts")+"&includeUnknownCurrency=true",r,PERMS,200).at("/data/content/0");
        assertThat(row.path("currencyStatus").asText()).isEqualTo("UNKNOWN");assertThat(row.path("currencyCode").isNull()).isTrue();
        assertThat(row.path("recordedExpenseAmountAllTime").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select currency_code from stock_documents where id=?",String.class,receipt)).isNull();
        jdbc.update("update restaurants set currency_code='USD' where id=?",r.getId());
        assertMoney(getJson(range(BASE+"summary")+"&currencyCode=VND",r,PERMS,200).path("data"),"totalExpenseAmount","100.00");
        assertThat(jdbc.queryForObject("select currency_code from stock_documents where id=?",String.class,receipt)).isNull();
    }

    @Test void localMidnightBoundsUseBusinessTimeAndMonthSeriesClipsRequestedRange() throws Exception {
        var r=fixture("VND",FEATURES);jdbc.update("update restaurants set timezone='Asia/Ho_Chi_Minh' where id=?",r.getId());
        var o=order(r,"BOUNDARY","OPEN","UNPAID",NOW,null,null,"500.00","VND","CASHIER");
        payment(r,o,"10.00","CASH","SUCCESS",Instant.parse("2026-10-28T16:59:59Z"),"VND");
        payment(r,o,"20.00","CASH","SUCCESS",Instant.parse("2026-10-28T17:00:00Z"),"VND");
        payment(r,o,"30.00","CASH","SUCCESS",Instant.parse("2026-10-29T16:59:59Z"),"VND");
        payment(r,o,"40.00","CASH","SUCCESS",Instant.parse("2026-10-29T17:00:00Z"),"VND");
        assertMoney(getJson(range(BASE+"summary"),r,PERMS,200).path("data"),"totalIncomeAmount","50.00");
        var series=getJson(range(BASE+"series")+"&granularity=MONTH",r,PERMS,200).path("data");
        assertThat(series).hasSize(1);assertMoney(series.get(0),"totalIncomeAmount","50.00");
        assertThat(series.get(0).path("bucketStartDate").asText()).isEqualTo("2026-10-01");
    }

    @Test void allReadGuardsFiltersAndTenantIsolationRunBeforeSourceAccess() throws Exception {
        var r=fixture("VND",FEATURES);var noFeature=fixture("VND",Set.of());
        for(String endpoint:List.of("entries","summary","series","expenses","stock-receipts")) {
            var path=range(BASE+endpoint);getJson(path,r,Set.of(),403);getJson(path,noFeature,PERMS,403);
            assertThat(mvc.perform(get(path)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        getJson(BASE+"categories",r,Set.of(),403);getJson(BASE+"categories",r,Set.of("CASHBOOK_VIEW"),200);
        getJson(range(BASE+"summary"),r,Set.of("CASHBOOK_VIEW"),200);
        getJson(range(BASE+"stock-receipts"),r,Set.of("CASHBOOK_VIEW"),403);
        getJson(range(BASE+"entries")+"&sortBy=amount;DROP_TABLE",r,PERMS,400);
        getJson(range(BASE+"entries")+"&type=BAD",r,PERMS,400);
        getJson(BASE+"summary?fromDate=2026-10-30&toDate=2026-10-29",r,PERMS,400);
        getJson(BASE+"summary?fromDate=2020-01-01&toDate=2026-10-29",r,PERMS,400);
        getJson(range(BASE+"summary")+"&currencyCode=BAD",r,PERMS,400);
        var before=counts(r);getJson(range(BASE+"summary"),r,PERMS,200);getJson(range(BASE+"entries"),r,PERMS,200);assertThat(counts(r)).isEqualTo(before);
    }

    private void assertMoney(JsonNode node,String field,String expected) {assertThat(node.path(field).decimalValue()).as(field).isEqualByComparingTo(expected);}
    private CurrentUser actor(Restaurant r,Set<String> permissions) {
        var found=jdbc.queryForList("select id from users where restaurant_id=? order by email limit 1",UUID.class,r.getId());UUID id;
        if(found.isEmpty()) {id=UUID.randomUUID();jdbc.update("insert into users(id,restaurant_id,role_id,name,email,password_hash,is_active) select ?,?,id,'Cashbook Tester',?,'test-hash',true from roles where restaurant_id is null and code='OWNER'",id,r.getId(),id+"@cashbook.test");}
        else id=found.getFirst();return new CurrentUser(id,r.getId(),id+"@cashbook.test","OWNER",permissions);
    }
    private JsonNode create(Restaurant r,String category,String amount,String currency,UUID receipt,String method) throws Exception {
        var body=new java.util.HashMap<String,Object>();body.put("categoryCode",category);body.put("amount",new BigDecimal(amount));body.put("currencyCode",currency);body.put("paymentMethod",method);body.put("expenseAt",NOW.minusSeconds(60).toString());if(receipt!=null)body.put("stockDocumentId",receipt);
        var user=actor(r,PERMS);var auth=new UsernamePasswordAuthenticationToken(user,null,PERMS.stream().map(SimpleGrantedAuthority::new).toList());
        var response=mvc.perform(post(BASE+"expenses").with(authentication(auth)).header("Idempotency-Key",UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(201);return json.readTree(response.getContentAsString());
    }
    private UUID receipt(Restaurant r,String status,String type,String currency) {
        var user=actor(r,PERMS);UUID w=UUID.randomUUID(),id=UUID.randomUUID();
        jdbc.update("insert into warehouses(id,restaurant_id,code,name,created_at,updated_at) values(?,?,?,'Test warehouse',?,?)",w,r.getId(),w.toString(),timestamp(NOW),timestamp(NOW));
        jdbc.update("insert into stock_documents(id,restaurant_id,warehouse_id,document_code,document_type,status,idempotency_key,creation_request_hash,total_cost,created_by,confirmed_by,confirmed_at,created_at,updated_at,currency_code) values(?,?,?,?,?,?,?, ?,600,?,?,?,?,?,?)",id,r.getId(),w,id.toString(),type,status,id.toString(),"a".repeat(64),user.userId(),"CONFIRMED".equals(status)?user.userId():null,"CONFIRMED".equals(status)?timestamp(NOW):null,timestamp(NOW),timestamp(NOW),currency);return id;
    }
    private Restaurant fixture(String currency, Set<String> features) {
        var restaurant = new Restaurant();
        restaurant.setCode("REPORT_" + UUID.randomUUID().toString().replace("-", ""));
        restaurant.setName("Report fixture");
        restaurant.setTimezone("UTC");
        restaurant.setCurrencyCode(currency);
        restaurant = restaurants.saveAndFlush(restaurant);

        var subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packages.findByCode("BASIC").orElseThrow().getId());
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setStartAt(NOW.minusSeconds(86400));
        subscription.setEndAt(NOW.plusSeconds(86400));
        subscription.setActivatedAt(NOW.minusSeconds(86400));
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(
            1,
            "TEST",
            features.stream().sorted().map(code -> new SubscriptionFeatureSnapshot.FeatureGrant(code, Map.of())).toList(),
            NOW
        ).toMap());
        subscriptions.saveAndFlush(subscription);
        return restaurant;
    }

    private UUID order(
        Restaurant restaurant,
        String code,
        String status,
        String paymentStatus,
        Instant createdAt,
        Instant completedAt,
        Instant cancelledAt,
        String total,
        String currency,
        String sourceChannel
    ) {
        UUID id = UUID.randomUUID();
        BigDecimal amount = new BigDecimal(total);
        jdbc.update("""
            INSERT INTO orders (
                id, restaurant_id, order_code, table_session_id, service_type, source_channel,
                status, payment_status, guest_count, currency_code, subtotal_amount,
                discount_amount, tax_amount, service_charge_amount, total_amount, paid_amount,
                cancel_reason, completed_at, cancelled_at, created_at, updated_at,
                mutation_sequence, idempotency_key, request_hash
            ) VALUES (?, ?, ?, NULL, 'TAKEAWAY', ?, ?, ?, 1, ?, ?, 0, 0, 0, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)
            """,
            id,
            restaurant.getId(),
            code + "-" + id.toString().substring(0, 8),
            sourceChannel,
            status,
            paymentStatus,
            currency,
            amount,
            amount,
            "PAID".equals(paymentStatus) ? amount : BigDecimal.ZERO,
            "CANCELLED".equals(status) ? "Fixture cancelled" : null,
            timestamp(completedAt),
            timestamp(cancelledAt),
            timestamp(createdAt),
            timestamp(createdAt),
            UUID.randomUUID().toString(),
            "a".repeat(64)
        );
        return id;
    }

    private UUID item(
        Restaurant restaurant,
        UUID orderId,
        UUID itemId,
        String name,
        String unit,
        String quantity,
        String lineTotal,
        String status,
        int lineNumber
    ) {
        UUID line = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO order_items (
                id, restaurant_id, order_id, item_id, item_name, unit, quantity,
                unit_price, discount_amount, line_total, status, cancel_reason,
                created_at, updated_at, line_number
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, ?, ?, ?)
            """,
            line,
            restaurant.getId(),
            orderId,
            itemId,
            name,
            unit,
            new BigDecimal(quantity),
            new BigDecimal(lineTotal).divide(new BigDecimal(quantity)),
            new BigDecimal(lineTotal),
            status,
            "CANCELLED".equals(status) ? "Fixture cancelled" : null,
            timestamp(NOW),
            timestamp(NOW),
            lineNumber
        );
        return line;
    }

    private void payment(
        Restaurant restaurant,
        UUID orderId,
        String amount,
        String method,
        String status,
        Instant paidAt,
        String currency
    ) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO payments (
                id, restaurant_id, order_id, payment_code, amount, currency_code,
                payment_method, status, confirmation_source, requested_at, paid_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PROVIDER_WEBHOOK', ?, ?, ?, ?)
            """,
            id,
            restaurant.getId(),
            orderId,
            "PAY-" + id.toString().substring(0, 12),
            new BigDecimal(amount),
            currency,
            method,
            status,
            timestamp(paidAt == null ? NOW : paidAt),
            timestamp(paidAt),
            timestamp(NOW),
            timestamp(NOW)
        );
    }

    private JsonNode getJson(String path, Restaurant restaurant, Set<String> permissions, int expected) throws Exception {
        var user = actor(restaurant, permissions);
        var authorities = permissions.stream().map(SimpleGrantedAuthority::new).toList();
        var auth = new UsernamePasswordAuthenticationToken(user, null, authorities);
        var response = mvc.perform(get(path).with(authentication(auth))).andReturn().getResponse();
        assertThat(response.getStatus()).as(response.getContentAsString()).isEqualTo(expected);
        if (expected == 200) assertThat(response.getHeader("Cache-Control")).contains("no-store");
        return json.readTree(response.getContentAsString());
    }

    private Map<String, Long> counts(Restaurant restaurant) {
        return Map.of(
            "orders", count("orders", restaurant),
            "payments", count("payments", restaurant),
            "subscriptions", count("restaurant_subscriptions", restaurant),
            "audit", count("audit_logs", restaurant)
        );
    }

    private long count(String table, Restaurant restaurant) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE restaurant_id=?", Long.class, restaurant.getId());
    }

    private static String range(String path) {
        return path + "?fromDate=2026-10-29&toDate=2026-10-29";
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }
}
