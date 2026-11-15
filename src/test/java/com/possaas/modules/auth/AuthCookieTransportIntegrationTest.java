package com.possaas.modules.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthCookieTransportIntegrationTest {
    private static final String SCHEMA = "it_ack_" + UUID.randomUUID().toString().replace("-", "");
    private static final String COOKIE = "xaypos_rt";
    private static final String TRANSPORT = "X-Auth-Transport";
    private static final Pattern COOKIE_VALUE = Pattern.compile(COOKIE + "=([^;]*)");

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
    @Autowired ObjectMapper json;

    @AfterAll void dropSchema() {
        assertThat(SCHEMA).matches("it_ack_[0-9a-f]{32}");
        db.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test void browserGetsHttpOnlyCookieAndNoTokenInBody() throws Exception {
        MvcResult registered = register(true);
        assertThat(json.readTree(registered.getResponse().getContentAsString()).get("refreshToken").isNull()).isTrue();
        String header = registered.getResponse().getHeader("Set-Cookie");
        assertThat(header).contains("HttpOnly").contains("SameSite=Strict").contains("Path=/api/v1/auth")
            .contains("Max-Age=2592000");
        assertThat(cookieValue(registered)).hasSizeGreaterThan(20);
    }

    @Test void refreshRotatesTheCookieAndReuseClearsIt() throws Exception {
        String first = cookieValue(register(true));
        MvcResult refreshed = mvc.perform(post("/api/v1/auth/refresh").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, first)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").doesNotExist())
            .andReturn();
        String second = cookieValue(refreshed);
        assertThat(second).isNotEqualTo(first);

        MvcResult reused = mvc.perform(post("/api/v1/auth/refresh").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, first)))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"))
            .andReturn();
        assertThat(reused.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
    }

    @Test void cookieModeWithoutCookieIsUnauthorized() throws Exception {
        mvc.perform(post("/api/v1/auth/refresh").header(TRANSPORT, "cookie"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    @Test void logoutWithCookieWorksWithoutAccessTokenAndRevokes() throws Exception {
        String token = cookieValue(register(true));
        MvcResult loggedOut = mvc.perform(post("/api/v1/auth/logout").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, token)))
            .andExpect(status().isNoContent()).andReturn();
        assertThat(loggedOut.getResponse().getHeader("Set-Cookie")).contains("Max-Age=0");
        mvc.perform(post("/api/v1/auth/refresh").header(TRANSPORT, "cookie").cookie(new Cookie(COOKIE, token)))
            .andExpect(status().isUnauthorized());
    }

    @Test void mobileBodyTransportIsUnchanged() throws Exception {
        MvcResult registered = register(false);
        assertThat(registered.getResponse().getHeader("Set-Cookie")).isNull();
        String token = json.readTree(registered.getResponse().getContentAsString()).get("refreshToken").asText();
        assertThat(token).isNotBlank();
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{\"refreshToken\":\"" + token + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/logout").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private MvcResult register(boolean cookieMode) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String body = """
            {"restaurantCode":"CK%s","restaurantName":"Cookie Restaurant","timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","ownerName":"Owner","ownerEmail":"ck-%s@example.com","password":"StrongPassword123!"}
            """.formatted(suffix, suffix.toLowerCase());
        var request = post("/api/v1/auth/register-restaurant").contentType(MediaType.APPLICATION_JSON).content(body);
        if (cookieMode) request = request.header(TRANSPORT, "cookie");
        return mvc.perform(request).andExpect(status().isCreated()).andReturn();
    }

    private static String cookieValue(MvcResult result) {
        Matcher matcher = COOKIE_VALUE.matcher(String.valueOf(result.getResponse().getHeader("Set-Cookie")));
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
