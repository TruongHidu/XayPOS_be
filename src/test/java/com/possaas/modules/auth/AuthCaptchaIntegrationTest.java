package com.possaas.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.possaas.common.captcha.CaptchaResult;
import com.possaas.common.captcha.CaptchaVerifier;
import com.possaas.infrastructure.security.CaptchaVerificationFilter;
import java.util.List;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The CAPTCHA filter end to end with a stubbed verifier: how each outcome is answered, which endpoints it guards,
 * and that the rate limit runs first so throttled requests never reach the provider.
 */
@SpringBootTest(properties = {
    "app.rate-limit.enabled=true",
    "app.auth.rate-limit.register.limit=2"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthCaptchaIntegrationTest {
    private static final String SCHEMA = "it_acp_" + UUID.randomUUID().toString().replace("-", "");
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
    @MockitoBean CaptchaVerifier captcha;

    @AfterAll void dropSchema() {
        assertThat(SCHEMA).matches("it_acp_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void aMissingTokenIsAskedForAndNothingIsCreated() throws Exception {
        when(captcha.verify(any(), any(), any())).thenReturn(CaptchaResult.missing());
        String email = uniqueEmail();

        register(nextAddress(), null, validBody(email))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CAPTCHA_REQUIRED"))
            .andExpect(header().string("Cache-Control", "no-store"));
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test void aRejectedTokenIsInvalidAndNothingIsCreated() throws Exception {
        when(captcha.verify(any(), any(), any())).thenReturn(CaptchaResult.rejected(List.of("timeout-or-duplicate")));
        String email = uniqueEmail();

        register(nextAddress(), "used-token", validBody(email))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("CAPTCHA_INVALID"));
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test void anUnreachableProviderFailsClosed() throws Exception {
        when(captcha.verify(any(), any(), any())).thenReturn(CaptchaResult.unavailable(List.of("siteverify-unreachable")));
        String email = uniqueEmail();

        register(nextAddress(), "tok", validBody(email))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.code").value("CAPTCHA_UNAVAILABLE"));
        assertThat(usersWithEmail(email)).isZero();
    }

    @Test void aPassedTokenRegistersAndIsCheckedForTheRegisterAction() throws Exception {
        when(captcha.verify(any(), any(), any())).thenReturn(CaptchaResult.passed());
        String address = nextAddress();
        String email = uniqueEmail();

        register(address, "tok", validBody(email)).andExpect(status().isCreated());

        verify(captcha).verify("tok", address, "register");
        assertThat(usersWithEmail(email)).isOne();
    }

    @Test void loginAndRefreshNeverAskForACaptcha() throws Exception {
        mvc.perform(post("/api/v1/auth/login").with(from(nextAddress())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + uniqueEmail() + "\",\"password\":\"whatever\"}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").with(from(nextAddress())).contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"not-a-real-token\"}"))
            .andExpect(status().isUnauthorized());

        verify(captcha, never()).verify(any(), any(), any());
    }

    @Test void throttledRegistrationsNeverReachTheProvider() throws Exception {
        when(captcha.verify(any(), any(), any())).thenReturn(CaptchaResult.passed());
        String address = nextAddress();
        register(address, "tok", "{}").andExpect(status().isBadRequest());
        register(address, "tok", "{}").andExpect(status().isBadRequest());
        clearInvocations(captcha);

        register(address, "tok", "{}")
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
        verify(captcha, never()).verify(any(), any(), any());
    }

    private ResultActions register(String address, String token, String body) throws Exception {
        var request = post("/api/v1/auth/register-restaurant").with(from(address))
            .contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) request.header(CaptchaVerificationFilter.TOKEN_HEADER, token);
        return mvc.perform(request);
    }

    private Integer usersWithEmail(String email) {
        return db.queryForObject("SELECT count(*) FROM users WHERE email = ?", Integer.class, email);
    }

    private static String validBody(String email) {
        String code = "CP" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        return """
            {"restaurantCode":"%s","restaurantName":"Captcha Restaurant","timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","ownerName":"Captcha Owner","ownerEmail":"%s","password":"StrongPassword123!"}
            """.formatted(code, email);
    }

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private static String nextAddress() {
        int n = ADDRESSES.incrementAndGet();
        return "192.0.2." + (n % 250 + 1);
    }

    private static String uniqueEmail() {
        return "captcha-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12) + "@example.com";
    }
}
