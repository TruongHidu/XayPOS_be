package com.possaas;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    private String code;
    private String email;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        code = "IT" + suffix;
        email = "integration-" + suffix.toLowerCase() + "@example.com";
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
        try {
            jdbcTemplate.update("DELETE FROM audit_logs WHERE restaurant_id IN (SELECT id FROM restaurants WHERE code = ?)", code);
            jdbcTemplate.update("DELETE FROM users WHERE email = ?", email);
            jdbcTemplate.update("DELETE FROM restaurants WHERE code = ?", code);
        } finally {
            jdbcTemplate.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
        }
    }

    @Test
    void authLifecycleWorksAndRefreshTokenRotates() throws Exception {
        String registerBody = """
            {"restaurantCode":"%s","restaurantName":"Integration Restaurant","timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","ownerName":"Integration Owner","ownerEmail":"%s","password":"StrongPassword123!"}
            """.formatted(code, email);

        String registerJson = mockMvc.perform(post("/api/v1/auth/register-restaurant")
                .contentType(MediaType.APPLICATION_JSON).content(registerBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.refreshToken").isNotEmpty())
            .andExpect(jsonPath("$.user.role").value("OWNER"))
            .andReturn().getResponse().getContentAsString();
        JsonNode registered = objectMapper.readTree(registerJson);
        String accessToken = registered.path("accessToken").asText();
        String refreshToken = registered.path("refreshToken").asText();

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.email").value(email))
            .andExpect(jsonPath("$.restaurantCode").value(code));

        String loginJson = mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"StrongPassword123!\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode loggedIn = objectMapper.readTree(loginJson);
        accessToken = loggedIn.path("accessToken").asText();
        refreshToken = loggedIn.path("refreshToken").asText();

        String refreshJson = mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode refreshed = objectMapper.readTree(refreshJson);
        String rotatedAccess = refreshed.path("accessToken").asText();
        String rotatedRefresh = refreshed.path("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/logout")
                .header("Authorization", "Bearer " + rotatedAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + rotatedRefresh + "\"}"))
            .andExpect(status().isNoContent());
    }
}
