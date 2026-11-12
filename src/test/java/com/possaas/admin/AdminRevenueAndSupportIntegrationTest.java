package com.possaas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminRevenueAndSupportIntegrationTest.FixedClock.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminRevenueAndSupportIntegrationTest {
    /** 2026-11-15 10:00 in Asia/Ho_Chi_Minh. */
    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");
    private static final String SCHEMA = "it_ars_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource static void isolated(DynamicPropertyRegistry p) {
        String base = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/kiot_tay_db");
        String url = base.contains("currentSchema=") ? base.replaceAll("currentSchema=[^&]*", "currentSchema=" + SCHEMA)
            : base + (base.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
        p.add("spring.datasource.url", () -> url); p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.datasource.hikari.schema", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA); p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired PasswordEncoder passwords;
    @Autowired ObjectMapper json;
    private UUID adminId;

    @BeforeAll void createAdmin() {
        adminId = UUID.randomUUID();
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,null,id,'Support Admin',?,'x' from roles where code='SUPER_ADMIN' and restaurant_id is null",
            adminId, "support-" + adminId + "@example.com");
    }
    @AfterAll void dropSchema() {
        assertThat(SCHEMA).matches("it_ars_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void revenueGroupsActivatedSubscriptionsByMonthAndNormalisesMrr() throws Exception {
        UUID pkg = pkg("REV_" + shortId(), 300000, 3);
        UUID a = restaurant("Nha hang A"), b = restaurant("Nha hang B"), c = restaurant("Nha hang C");
        // A: quarterly plan activated this month, effective -> MRR 100,000; expires in 5 days.
        subscription(a, pkg, "ACTIVE", Instant.parse("2026-08-20T03:00:00Z"), Instant.parse("2026-11-20T03:00:00Z"), 300000,
            Instant.parse("2026-11-02T03:00:00Z"));
        // B: activated last month, expired 10 days ago and never renewed -> churned.
        subscription(b, pkg, "EXPIRED", Instant.parse("2026-08-05T03:00:00Z"), Instant.parse("2026-11-05T03:00:00Z"), 150000,
            Instant.parse("2026-10-10T03:00:00Z"));
        // C: pending (never activated) -> no revenue.
        subscription(c, pkg, "PENDING", NOW.plusSeconds(86400), NOW.plusSeconds(86400 * 90), 999000, null);

        String body = mvc.perform(get("/api/v1/admin/dashboard/revenue").param("months", "3").with(authentication(admin("ADMIN_DASHBOARD_VIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.series.length()").value(3))
            .andExpect(jsonPath("$.series[2].month").value("2026-11"))
            .andReturn().getResponse().getContentAsString();
        JsonNode revenue = json.readTree(body);
        assertThat(revenue.at("/series/2/revenue").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("300000"));
        assertThat(revenue.at("/series/1/revenue").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("150000"));
        assertThat(revenue.at("/series/2/manualRevenue").decimalValue()).isEqualByComparingTo(revenue.at("/series/2/revenue").decimalValue()
            .subtract(revenue.at("/series/2/onlineRevenue").decimalValue()));
        assertThat(revenue.at("/mrr").decimalValue()).isGreaterThanOrEqualTo(new java.math.BigDecimal("100000"));
        assertThat(revenue.at("/churnedLast30Days").asLong()).isGreaterThanOrEqualTo(1);
        List<String> expiring = new ArrayList<>();
        revenue.at("/expiringSoon").forEach(n -> expiring.add(n.get("restaurantId").asText()));
        assertThat(expiring).contains(a.toString()).doesNotContain(b.toString());
        List<String> packages = new ArrayList<>();
        revenue.at("/packageMix").forEach(n -> packages.add(n.get("packageCode").asText()));
        assertThat(packages).isNotEmpty();

        mvc.perform(get("/api/v1/admin/dashboard/revenue").param("months", "99").with(authentication(admin("ADMIN_DASHBOARD_VIEW"))))
            .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/dashboard/revenue").with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isForbidden());
    }

    @Test void supportCanLockUserAndIssueTemporaryPassword() throws Exception {
        UUID tenant = restaurant("Support restaurant");
        UUID user = UUID.randomUUID();
        db.update("insert into users(id,restaurant_id,role_id,name,email,password_hash) select ?,?,id,'Cashier',?,'old' from roles where code='CASHIER' and restaurant_id is null",
            user, tenant, "cashier-" + user + "@example.com");
        String path = "/api/v1/admin/restaurants/" + tenant + "/users/" + user;

        mvc.perform(patch(path + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"reason\":\"Nghi viec\"}")
                .with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isForbidden());
        mvc.perform(patch(path + "/status").contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"reason\":\"Nghi viec\"}")
                .with(authentication(admin("RESTAURANT_VIEW", "RESTAURANT_MANAGE"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        assertThat(db.queryForObject("select is_active from users where id=?", Boolean.class, user)).isFalse();

        String body = mvc.perform(post(path + "/temporary-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Chu quan quen mat khau\"}").with(authentication(admin("RESTAURANT_MANAGE"))))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse().getContentAsString();
        String temporary = json.readTree(body).get("temporaryPassword").asText();
        assertThat(temporary).hasSize(12);
        assertThat(passwords.matches(temporary, db.queryForObject("select password_hash from users where id=?", String.class, user))).isTrue();
        String audit = db.queryForObject("select after_data::text from audit_logs where entity_id=? and action_code='ADMIN_USER_PASSWORD_RESET'",
            String.class, user);
        assertThat(audit).doesNotContain(temporary);
    }

    @Test void notesAreInternalAndUsageSummarisesTheRestaurant() throws Exception {
        UUID tenant = restaurant("Usage restaurant");
        String base = "/api/v1/admin/restaurants/" + tenant;
        mvc.perform(post(base + "/notes").contentType(MediaType.APPLICATION_JSON).content("{\"content\":\"  \"}")
                .with(authentication(admin("RESTAURANT_MANAGE"))))
            .andExpect(status().isBadRequest());
        String created = mvc.perform(post(base + "/notes").contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Da goi dien tu van goi PRO\"}").with(authentication(admin("RESTAURANT_MANAGE"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authorName").value("Support Admin"))
            .andReturn().getResponse().getContentAsString();
        String noteId = json.readTree(created).get("id").asText();
        mvc.perform(get(base + "/notes").with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(delete(base + "/notes/" + noteId).with(authentication(admin("RESTAURANT_MANAGE"))))
            .andExpect(status().isNoContent());
        mvc.perform(get(base + "/notes").with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        mvc.perform(get(base + "/usage").with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packageCode").doesNotExist())
            .andExpect(jsonPath("$.activeTables").value(0))
            .andExpect(jsonPath("$.ordersLast30Days").value(0))
            .andExpect(jsonPath("$.features.length()").value(0));
        mvc.perform(get("/api/v1/admin/restaurants/" + UUID.randomUUID() + "/usage").with(authentication(admin("RESTAURANT_VIEW"))))
            .andExpect(status().isNotFound());
    }

    private UUID restaurant(String name) {
        UUID id = UUID.randomUUID(); String suffix = id.toString().replace("-", "");
        db.update("insert into restaurants(id,code,name,status,timezone,currency_code,public_order_token,created_at,updated_at) values(?,?,?,'ACTIVE','Asia/Ho_Chi_Minh','VND',?,?,?)",
            id, "AR_" + suffix, name, suffix, Timestamp.from(NOW.minusSeconds(3600)), Timestamp.from(NOW.minusSeconds(3600)));
        return id;
    }
    private UUID pkg(String code, long price, int months) {
        UUID id = UUID.randomUUID();
        db.update("insert into packages(id,code,name,price_amount,currency_code,billing_cycle_months,is_active) values(?,?,?,?,'VND',?,true)",
            id, code, "Plan " + code, price, months);
        return id;
    }
    private void subscription(UUID restaurant, UUID pkg, String status, Instant start, Instant end, long price, Instant activatedAt) {
        db.update("insert into restaurant_subscriptions(id,restaurant_id,package_id,status,start_at,end_at,price_amount,currency_code,activated_at) values(?,?,?,?,?,?,?,'VND',?)",
            UUID.randomUUID(), restaurant, pkg, status, Timestamp.from(start), Timestamp.from(end), price,
            activatedAt == null ? null : Timestamp.from(activatedAt));
    }
    private Authentication admin(String... permissions) {
        CurrentUser u = new CurrentUser(adminId, null, "support@example.com", "SUPER_ADMIN", Set.of(permissions));
        List<SimpleGrantedAuthority> grants = new ArrayList<>(); grants.add(new SimpleGrantedAuthority("ROLE_SUPER_ADMIN"));
        u.permissions().forEach(p -> grants.add(new SimpleGrantedAuthority(p)));
        return new UsernamePasswordAuthenticationToken(u, null, grants);
    }
    private static String shortId() { return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(); }

    @TestConfiguration static class FixedClock {
        @Bean @Primary Clock testClock() { return Clock.fixed(NOW, ZoneOffset.UTC); }
    }
}
