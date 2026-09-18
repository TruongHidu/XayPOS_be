package com.possaas.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.entity.AuthRefreshToken;
import com.possaas.modules.auth.repository.AuthRefreshTokenRepository;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class AdminManagementIntegrationTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired RestaurantSubscriptionRepository subscriptionRepository;
    @Autowired PackagePlanRepository packageRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired UserRepository userRepository;
    @Autowired AuthRefreshTokenRepository refreshTokenRepository;
    @Autowired AuditService auditService;

    private final List<UUID> restaurantIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();
    private final List<String> systemAuditActions = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
        try {
            restaurantIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE restaurant_id = ? OR entity_id = ?",
                id,
                id
            ));
            systemAuditActions.forEach(action -> jdbcTemplate.update(
                "DELETE FROM audit_logs WHERE action_code = ?",
                action
            ));
        } finally {
            jdbcTemplate.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
        }
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM auth_refresh_tokens WHERE user_id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM user_permissions WHERE user_id = ?", id));
        userIds.forEach(id -> jdbcTemplate.update("DELETE FROM users WHERE id = ?", id));
        restaurantIds.forEach(id -> jdbcTemplate.update(
            "DELETE FROM restaurant_subscriptions WHERE restaurant_id = ?",
            id
        ));
        restaurantIds.forEach(id -> jdbcTemplate.update("DELETE FROM restaurants WHERE id = ?", id));
    }

    @Test
    void authenticationAndEveryAdminApiRequireBothSystemScopeAndPermission() throws Exception {
        Restaurant restaurant = createRestaurant("Authorization", RestaurantStatus.ACTIVE, false);

        mockMvc.perform(get("/api/v1/admin/packages"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        mockMvc.perform(get("/api/v1/admin/packages")
                .header("Authorization", "Bearer invalid.jwt.value"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(get("/api/v1/admin/packages")
                .with(authentication(auth(restaurant.getId(), "OWNER", "PACKAGE_VIEW"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/subscriptions")
                .with(authentication(auth(restaurant.getId(), "OWNER", "SUBSCRIPTION_VIEW"))))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/v1/admin/packages")
                .with(authentication(auth(restaurant.getId(), "SUPER_ADMIN", "PACKAGE_VIEW"))))
            .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/admin/packages")
                .with(authentication(auth(null, "SUPER_ADMIN"))))
            .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/admin/features/pos_quick_order")
                .with(authentication(auth(null, "SUPER_ADMIN", "PACKAGE_VIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("POS_QUICK_ORDER"));
        mockMvc.perform(get("/api/v1/admin/packages/pro")
                .with(authentication(auth(null, "SUPER_ADMIN", "PACKAGE_VIEW"))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("PRO"));
        mockMvc.perform(get("/api/v1/admin/subscriptions")
                .with(authentication(auth(null, "SUPER_ADMIN", "SUBSCRIPTION_VIEW"))))
            .andExpect(status().isOk());
    }

    @Test
    void restaurantQueriesAreStableFilteredPagedAndDoNotExposeSecrets() throws Exception {
        String marker = randomMarker();
        Restaurant alpha = createRestaurant("Admin Alpha " + marker, RestaurantStatus.ACTIVE, false);
        alpha.setLegalName("Legal " + marker);
        alpha.setPhone("090" + marker.substring(0, 6));
        alpha.setAddress("Private address");
        alpha.setPublicOrderToken("private-" + marker);
        alpha.setSettings(Map.of("secret", "private"));
        restaurantRepository.saveAndFlush(alpha);
        Restaurant beta = createRestaurant("Admin Beta " + marker, RestaurantStatus.ACTIVE, false);
        createRestaurant("Admin Suspended " + marker, RestaurantStatus.SUSPENDED, false);
        createRestaurant("Admin Deleted " + marker, RestaurantStatus.ACTIVE, true);
        User owner = createOwner(alpha);
        saveSubscription(
            alpha,
            "BASIC",
            SubscriptionStatus.ACTIVE,
            Instant.now().minus(1, ChronoUnit.DAYS),
            Instant.now().plus(10, ChronoUnit.DAYS),
            "POS_QUICK_ORDER"
        );

        Authentication admin = auth(null, "SUPER_ADMIN", "RESTAURANT_VIEW");
        MvcResult firstPage = mockMvc.perform(get("/api/v1/admin/restaurants")
                .param("q", marker.toLowerCase(Locale.ROOT))
                .param("status", "ACTIVE")
                .param("page", "0")
                .param("size", "1")
                .param("sortBy", "name")
                .param("direction", "asc")
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content.length()").value(1))
            .andExpect(jsonPath("$.content[0].id").value(alpha.getId().toString()))
            .andExpect(jsonPath("$.content[0].effectiveSubscription.packageCode").value("BASIC"))
            .andReturn();
        assertThat(firstPage.getResponse().getContentAsString())
            .doesNotContain("publicOrderToken", "settings", "Private address");

        mockMvc.perform(get("/api/v1/admin/restaurants")
                .param("q", marker.toLowerCase(Locale.ROOT))
                .param("status", "ACTIVE")
                .param("page", "1")
                .param("size", "1")
                .param("sortBy", "name")
                .param("direction", "asc")
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].id").value(beta.getId().toString()));

        MvcResult detail = mockMvc.perform(get("/api/v1/admin/restaurants/{id}", alpha.getId())
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.owners.length()").value(1))
            .andExpect(jsonPath("$.owners[0].id").value(owner.getId().toString()))
            .andExpect(jsonPath("$.userCounts.total").value(1))
            .andExpect(jsonPath("$.latestSubscription.packageCode").value("BASIC"))
            .andReturn();
        assertThat(detail.getResponse().getContentAsString())
            .doesNotContain("passwordHash", "publicOrderToken", "settings");

        mockMvc.perform(get("/api/v1/admin/restaurants")
                .param("sortBy", "publicOrderToken")
                .with(authentication(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/restaurants/not-a-uuid")
                .with(authentication(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(get("/api/v1/admin/restaurants/{id}", UUID.randomUUID())
                .with(authentication(admin)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("RESTAURANT_NOT_FOUND"));
    }

    @Test
    void suspendIsIdempotentRevokesRefreshTokensAndBlocksAnExistingTenantAccessToken() throws Exception {
        Restaurant restaurant = createRestaurant("Suspend", RestaurantStatus.ACTIVE, false);
        User owner = createOwner(restaurant);
        User systemAdmin = createSystemAdmin();
        AuthRefreshToken refreshToken = new AuthRefreshToken();
        refreshToken.setUserId(owner.getId());
        refreshToken.setTokenHash("it-" + UUID.randomUUID());
        refreshToken.setExpiresAt(Instant.now().plus(7, ChronoUnit.DAYS));
        refreshToken = refreshTokenRepository.saveAndFlush(refreshToken);

        Authentication admin = authForUser(
            systemAdmin.getId(),
            null,
            "SUPER_ADMIN",
            "RESTAURANT_MANAGE"
        );
        String suspendBody = """
            {"status":"SUSPENDED","reason":"  Payment verification required  "}
            """;
        mockMvc.perform(patch("/api/v1/admin/restaurants/{id}/status", restaurant.getId())
                .with(authentication(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(suspendBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("SUSPENDED"));

        assertThat(refreshTokenRepository.findById(refreshToken.getId()).orElseThrow().getRevokedAt())
            .isNotNull();
        assertThat(statusAuditCount(restaurant.getId())).isEqualTo(1);
        String auditReason = jdbcTemplate.queryForObject(
            "SELECT after_data ->> 'reason' FROM audit_logs WHERE restaurant_id = ? AND action_code = 'RESTAURANT_STATUS_CHANGED'",
            String.class,
            restaurant.getId()
        );
        assertThat(auditReason).isEqualTo("Payment verification required");

        Authentication oldOwnerAccessToken = auth(
            restaurant.getId(),
            "OWNER",
            "SUBSCRIPTION_VIEW"
        );
        mockMvc.perform(get("/api/v1/subscriptions/history")
                .with(authentication(oldOwnerAccessToken)))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("RESTAURANT_INACTIVE"));

        mockMvc.perform(patch("/api/v1/admin/restaurants/{id}/status", restaurant.getId())
                .with(authentication(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(suspendBody))
            .andExpect(status().isOk());
        assertThat(statusAuditCount(restaurant.getId())).isEqualTo(1);

        mockMvc.perform(patch("/api/v1/admin/restaurants/{id}/status", restaurant.getId())
                .with(authentication(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"ACTIVE\",\"reason\":\"Verified\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(statusAuditCount(restaurant.getId())).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
            "SELECT count(*) FROM auth_refresh_tokens WHERE user_id = ?",
            Long.class,
            owner.getId()
        )).isEqualTo(1L);
        assertThat(refreshTokenRepository.findById(refreshToken.getId()).orElseThrow().getRevokedAt())
            .isNotNull();
    }

    @Test
    void subscriptionQueriesRespectRestaurantScopeFiltersSoftDeleteAndSnapshotSemantics() throws Exception {
        Restaurant first = createRestaurant("Subscription One", RestaurantStatus.ACTIVE, false);
        Restaurant second = createRestaurant("Subscription Two", RestaurantStatus.ACTIVE, false);
        Restaurant deleted = createRestaurant("Subscription Deleted", RestaurantStatus.ACTIVE, true);
        Instant now = Instant.now();
        RestaurantSubscription active = saveSubscription(
            first,
            "BASIC",
            SubscriptionStatus.ACTIVE,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(5, ChronoUnit.DAYS),
            "SNAPSHOT_ONLY"
        );
        saveSubscription(
            first,
            "PRO",
            SubscriptionStatus.PENDING,
            now.plus(1, ChronoUnit.DAYS),
            now.plus(31, ChronoUnit.DAYS),
            "PENDING_ONLY"
        );
        saveSubscription(
            second,
            "PRO",
            SubscriptionStatus.ACTIVE,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(5, ChronoUnit.DAYS),
            "OTHER_TENANT"
        );
        saveSubscription(
            deleted,
            "BASIC",
            SubscriptionStatus.ACTIVE,
            now.minus(1, ChronoUnit.DAYS),
            now.plus(5, ChronoUnit.DAYS),
            "DELETED_TENANT"
        );

        Authentication admin = auth(null, "SUPER_ADMIN", "SUBSCRIPTION_VIEW");
        mockMvc.perform(get("/api/v1/admin/subscriptions")
                .param("q", first.getCode().toLowerCase(Locale.ROOT))
                .param("packageCode", " basic ")
                .param("status", "ACTIVE")
                .param("effective", "true")
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].id").value(active.getId().toString()))
            .andExpect(jsonPath("$.content[0].restaurant.id").value(first.getId().toString()))
            .andExpect(jsonPath("$.content[0].effective").value(true));

        mockMvc.perform(get("/api/v1/admin/restaurants/{id}/subscriptions", first.getId())
                .param("size", "100")
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(2))
            .andExpect(jsonPath("$.content[0].restaurant.id").value(first.getId().toString()))
            .andExpect(jsonPath("$.content[1].restaurant.id").value(first.getId().toString()));

        mockMvc.perform(get(
                "/api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}",
                first.getId(),
                active.getId()
            ).with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packageInfo.code").value("BASIC"))
            .andExpect(jsonPath("$.features.length()").value(1))
            .andExpect(jsonPath("$.features[0].code").value("SNAPSHOT_ONLY"));
        mockMvc.perform(get(
                "/api/v1/admin/restaurants/{restaurantId}/subscriptions/{subscriptionId}",
                second.getId(),
                active.getId()
            ).with(authentication(admin)))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("SUBSCRIPTION_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/admin/subscriptions")
                .param("q", deleted.getCode())
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get("/api/v1/admin/subscriptions")
                .param("sortBy", "restaurant.settings")
                .with(authentication(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void dashboardCountsMatchPostgresqlAggregatesAndContainNoInventedRevenueFields() throws Exception {
        Authentication admin = auth(null, "SUPER_ADMIN", "ADMIN_DASHBOARD_VIEW");
        MvcResult result = mockMvc.perform(get("/api/v1/admin/dashboard/summary")
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.generatedAt").isNotEmpty())
            .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
        Instant generatedAt = Instant.parse(response.path("generatedAt").asText());
        Timestamp now = Timestamp.from(generatedAt);

        Map<String, Object> restaurants = jdbcTemplate.queryForMap("""
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE status = 'ACTIVE') AS active,
                   count(*) FILTER (WHERE status = 'INACTIVE') AS inactive,
                   count(*) FILTER (WHERE status = 'SUSPENDED') AS suspended,
                   count(*) FILTER (WHERE created_at >= ?) AS new_last_30_days,
                   count(*) FILTER (
                       WHERE status = 'ACTIVE' AND NOT EXISTS (
                           SELECT 1 FROM restaurant_subscriptions s
                           WHERE s.restaurant_id = restaurants.id
                             AND s.status = 'ACTIVE'
                             AND s.start_at <= ? AND ? < s.end_at
                       )
                   ) AS active_without_effective_subscription
              FROM restaurants
             WHERE deleted_at IS NULL
            """, Timestamp.from(generatedAt.minus(30, ChronoUnit.DAYS)), now, now);
        assertJsonCount(response.path("restaurants"), "total", restaurants.get("total"));
        assertJsonCount(response.path("restaurants"), "active", restaurants.get("active"));
        assertJsonCount(response.path("restaurants"), "inactive", restaurants.get("inactive"));
        assertJsonCount(response.path("restaurants"), "suspended", restaurants.get("suspended"));
        assertJsonCount(
            response.path("restaurants"),
            "newLast30Days",
            restaurants.get("new_last_30_days")
        );
        assertJsonCount(
            response.path("restaurants"),
            "activeWithoutEffectiveSubscription",
            restaurants.get("active_without_effective_subscription")
        );

        Map<String, Object> subscriptions = jdbcTemplate.queryForMap("""
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE s.status = 'PENDING') AS pending,
                   count(*) FILTER (WHERE s.status = 'ACTIVE') AS active_status,
                   count(*) FILTER (
                       WHERE s.status = 'ACTIVE' AND s.start_at <= ? AND ? < s.end_at
                   ) AS effective_now,
                   count(*) FILTER (
                       WHERE s.status = 'ACTIVE' AND s.end_at <= ?
                   ) AS stale_active,
                   count(*) FILTER (WHERE s.status = 'EXPIRED') AS expired,
                   count(*) FILTER (WHERE s.status = 'CANCELLED') AS cancelled,
                   count(*) FILTER (
                       WHERE s.status = 'ACTIVE' AND s.start_at <= ? AND ? < s.end_at
                         AND s.end_at <= ?
                   ) AS expiring_within_7_days
              FROM restaurant_subscriptions s
              JOIN restaurants r ON r.id = s.restaurant_id
             WHERE r.deleted_at IS NULL
            """, now, now, now, now, now, Timestamp.from(generatedAt.plus(7, ChronoUnit.DAYS)));
        assertJsonCount(response.path("subscriptions"), "total", subscriptions.get("total"));
        assertJsonCount(response.path("subscriptions"), "activeStatus", subscriptions.get("active_status"));
        assertJsonCount(response.path("subscriptions"), "effectiveNow", subscriptions.get("effective_now"));
        assertJsonCount(response.path("subscriptions"), "staleActive", subscriptions.get("stale_active"));
        assertJsonCount(
            response.path("subscriptions"),
            "expiringWithin7Days",
            subscriptions.get("expiring_within_7_days")
        );
        assertThat(response.has("revenue")).isFalse();
        assertThat(response.has("mrr")).isFalse();
        assertThat(response.has("arr")).isFalse();
    }

    @Test
    void auditQueriesFilterTimeAndScopeRedactSecretsAndRemainReadOnly() throws Exception {
        Restaurant restaurant = createRestaurant("Audit", RestaurantStatus.ACTIVE, false);
        User actor = createOwner(restaurant);
        String tenantAction = "IT_AUDIT_" + randomMarker();
        String systemAction = tenantAction + "_SYSTEM";
        systemAuditActions.add(systemAction);
        Map<String, Object> afterData = new LinkedHashMap<>();
        afterData.put("password", "raw-password");
        afterData.put("nested", Map.of("refresh_token", "raw-refresh"));
        afterData.put("items", List.of(Map.of("accessToken", "raw-access")));
        auditService.record(
            restaurant.getId(),
            actor.getId(),
            tenantAction,
            "integration_entity",
            restaurant.getId(),
            Map.of("status", "BEFORE"),
            afterData,
            "127.0.0.1"
        );
        auditService.record(
            null,
            null,
            systemAction,
            "system_job",
            null,
            null,
            null,
            null
        );

        Authentication admin = auth(null, "SUPER_ADMIN", "AUDIT_VIEW");
        MvcResult tenantResult = mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("scope", "TENANT")
                .param("restaurantId", restaurant.getId().toString())
                .param("actorUserId", actor.getId().toString())
                .param("actionCode", tenantAction.toLowerCase(Locale.ROOT))
                .param("entityType", "integration_entity")
                .param("entityId", restaurant.getId().toString())
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].restaurantId").value(restaurant.getId().toString()))
            .andExpect(jsonPath("$.content[0].actorEmail").value(actor.getEmail()))
            .andExpect(jsonPath("$.content[0].afterData.password").value("[REDACTED]"))
            .andExpect(jsonPath("$.content[0].afterData.nested.refresh_token").value("[REDACTED]"))
            .andExpect(jsonPath("$.content[0].afterData.items[0].accessToken").value("[REDACTED]"))
            .andExpect(jsonPath("$.content[0].ipAddress").value("127.0.0.1"))
            .andReturn();
        JsonNode tenantJson = objectMapper.readTree(tenantResult.getResponse().getContentAsString());
        UUID auditId = UUID.fromString(tenantJson.path("content").get(0).path("id").asText());
        Instant createdAt = Instant.parse(tenantJson.path("content").get(0).path("createdAt").asText());

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("actionCode", tenantAction)
                .param("from", createdAt.toString())
                .param("to", createdAt.plusSeconds(1).toString())
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("actionCode", tenantAction)
                .param("to", createdAt.toString())
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("scope", "SYSTEM")
                .param("actionCode", systemAction)
                .with(authentication(admin)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.content[0].restaurantId").doesNotExist())
            .andExpect(jsonPath("$.content[0].actorUserId").doesNotExist());
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("from", createdAt.toString())
                .param("to", createdAt.toString())
                .with(authentication(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_AUDIT_PERIOD"));
        mockMvc.perform(get("/api/v1/admin/audit-logs")
                .param("scope", "SYSTEM")
                .param("restaurantId", restaurant.getId().toString())
                .with(authentication(admin)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_AUDIT_FILTER"));
        mockMvc.perform(post("/api/v1/admin/audit-logs")
                .with(authentication(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));

        assertThatThrownBy(() -> jdbcTemplate.update(
            "UPDATE audit_logs SET action_code = 'MUTATED' WHERE id = ?",
            auditId
        )).isInstanceOf(DataAccessException.class);
    }

    private Restaurant createRestaurant(
        String name,
        RestaurantStatus status,
        boolean deleted
    ) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("IT" + randomMarker());
        restaurant.setName(name);
        restaurant.setStatus(status);
        if (deleted) {
            restaurant.setDeletedAt(Instant.now());
        }
        restaurant = restaurantRepository.saveAndFlush(restaurant);
        restaurantIds.add(restaurant.getId());
        return restaurant;
    }

    private User createOwner(Restaurant restaurant) {
        Role ownerRole = roleRepository.findByCodeAndRestaurantIdIsNull("OWNER").orElseThrow();
        User owner = new User();
        owner.setRestaurantId(restaurant.getId());
        owner.setRoleId(ownerRole.getId());
        owner.setName("Integration Owner");
        owner.setEmail("admin-it-" + UUID.randomUUID() + "@example.com");
        owner.setPhone("0900000000");
        owner.setPasswordHash("must-never-be-returned");
        owner = userRepository.saveAndFlush(owner);
        userIds.add(owner.getId());
        return owner;
    }

    private User createSystemAdmin() {
        Role role = roleRepository.findByCodeAndRestaurantIdIsNull("SUPER_ADMIN").orElseThrow();
        User admin = new User();
        admin.setRoleId(role.getId());
        admin.setName("Integration System Administrator");
        admin.setEmail("system-admin-it-" + UUID.randomUUID() + "@example.com");
        admin.setPasswordHash("must-never-be-returned");
        admin = userRepository.saveAndFlush(admin);
        userIds.add(admin.getId());
        return admin;
    }

    private RestaurantSubscription saveSubscription(
        Restaurant restaurant,
        String packageCode,
        SubscriptionStatus status,
        Instant startAt,
        Instant endAt,
        String snapshotFeature
    ) {
        PackagePlan packagePlan = packageRepository.findByCode(packageCode).orElseThrow();
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurant.getId());
        subscription.setPackageId(packagePlan.getId());
        subscription.setStatus(status);
        subscription.setStartAt(startAt);
        subscription.setEndAt(endAt);
        subscription.setAutoRenew(false);
        subscription.setPriceAmount(new BigDecimal("399000.00"));
        subscription.setCurrencyCode("VND");
        if (status == SubscriptionStatus.ACTIVE) {
            subscription.setActivatedAt(startAt);
        }
        subscription.setFeatureSnapshot(new SubscriptionFeatureSnapshot(
            1,
            packageCode,
            List.of(new SubscriptionFeatureSnapshot.FeatureGrant(snapshotFeature, Map.of())),
            startAt
        ).toMap());
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private long statusAuditCount(UUID restaurantId) {
        return jdbcTemplate.queryForObject(
            "SELECT count(*) FROM audit_logs WHERE restaurant_id = ? AND action_code = 'RESTAURANT_STATUS_CHANGED'",
            Long.class,
            restaurantId
        );
    }

    private static Authentication auth(UUID restaurantId, String role, String... permissions) {
        return authForUser(UUID.randomUUID(), restaurantId, role, permissions);
    }

    private static Authentication authForUser(
        UUID userId,
        UUID restaurantId,
        String role,
        String... permissions
    ) {
        Set<String> permissionSet = Set.of(permissions);
        CurrentUser principal = new CurrentUser(
            userId,
            restaurantId,
            "admin-security-test@example.com",
            role,
            permissionSet
        );
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        permissionSet.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return new UsernamePasswordAuthenticationToken(principal, "N/A", authorities);
    }

    private static void assertJsonCount(JsonNode parent, String field, Object databaseValue) {
        assertThat(parent.path(field).asLong()).isEqualTo(((Number) databaseValue).longValue());
    }

    private static String randomMarker() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
    }
}
