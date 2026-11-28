package com.possaas.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Both layers end to end: per-address limits in the request filter and the per-e-mail lock in the login service.
 * The limiter is shared by the whole context, so every request uses its own address unless a test means otherwise.
 */
@SpringBootTest(properties = {
    "app.rate-limit.enabled=true",
    "app.auth.rate-limit.login.limit=3",
    "app.auth.rate-limit.login-failures.limit=2",
    "app.auth.rate-limit.register.limit=2",
    "app.auth.rate-limit.refresh.limit=2"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRateLimitIntegrationTest {
    private static final String SCHEMA = "it_arl_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "StrongPassword123!";
    private static final AtomicInteger ADDRESSES = new AtomicInteger();

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

    @AfterAll void dropSchema() {
        assertThat(SCHEMA).matches("it_arl_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void oneAddressFloodingLoginGets429WhileOtherAddressesStillWork() throws Exception {
        String flooding = nextAddress();
        for (int i = 0; i < 3; i++) login(flooding, uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());

        login(flooding, uniqueEmail(), PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"))
            .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
            .andExpect(header().string("Cache-Control", "no-store"));
        login(nextAddress(), uniqueEmail(), PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test void registrationIsThrottledBeforeTheBodyIsValidated() throws Exception {
        String address = nextAddress();
        registerRaw(address, "{}").andExpect(status().isBadRequest());
        registerRaw(address, "{}").andExpect(status().isBadRequest());

        registerRaw(address, "{}")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    @Test void repeatedWrongPasswordsLockTheAccountEvenAcrossAddressesAndForTheRightPassword() throws Exception {
        String email = registerOwner();
        login(nextAddress(), email, "wrong-password-1").andExpect(status().isUnauthorized());
        login(nextAddress(), email, "wrong-password-2").andExpect(status().isUnauthorized());

        login(nextAddress(), email, PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("LOGIN_TEMPORARILY_LOCKED"))
            .andExpect(header().exists("Retry-After"));
    }

    @Test void anUnknownEmailIsLockedExactlyLikeAnExistingOne() throws Exception {
        String unknown = uniqueEmail();
        login(nextAddress(), unknown, "wrong-password-1").andExpect(status().isUnauthorized());
        login(nextAddress(), unknown, "wrong-password-2").andExpect(status().isUnauthorized());

        login(nextAddress(), unknown, PASSWORD)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("LOGIN_TEMPORARILY_LOCKED"));
    }

    @Test void aSuccessfulLoginClearsEarlierFailures() throws Exception {
        String email = registerOwner();
        login(nextAddress(), email, "wrong-password").andExpect(status().isUnauthorized());
        login(nextAddress(), email, PASSWORD).andExpect(status().isOk());
        login(nextAddress(), email, "wrong-password").andExpect(status().isUnauthorized());

        login(nextAddress(), email, PASSWORD).andExpect(status().isOk());
    }

    @Test void refreshIsThrottledPerAddress() throws Exception {
        String address = nextAddress();
        refresh(address).andExpect(status().isUnauthorized());
        refresh(address).andExpect(status().isUnauthorized());

        refresh(address)
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
    }

    private String registerOwner() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String email = "rate-limit-" + suffix + "@example.com";
        String body = """
            {"restaurantCode":"RL%s","restaurantName":"Rate Limit Restaurant","timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","ownerName":"Rate Limit Owner","ownerEmail":"%s","password":"%s"}
            """.formatted(suffix.toUpperCase(), email, PASSWORD);
        registerRaw(nextAddress(), body).andExpect(status().isCreated());
        return email;
    }

    private ResultActions login(String address, String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(from(address))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions registerRaw(String address, String body) throws Exception {
        return mvc.perform(post("/api/v1/auth/register-restaurant").with(from(address))
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions refresh(String address) throws Exception {
        return mvc.perform(post("/api/v1/auth/refresh").with(from(address))
            .contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"not-a-real-token\"}"));
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String nextAddress() {
        int n = ADDRESSES.incrementAndGet();
        return "198.51." + (n / 250) + "." + (n % 250 + 1);
    }

    private static String uniqueEmail() {
        return "unknown-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.com";
    }
}
