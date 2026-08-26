package com.possaas.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.dto.ChangePackageRequest;
import com.possaas.modules.subscription.dto.CreateFeatureRequest;
import com.possaas.modules.subscription.dto.CreatePackageRequest;
import com.possaas.modules.subscription.dto.CreateSubscriptionRequest;
import com.possaas.modules.subscription.dto.PackageFeatureRequest;
import com.possaas.modules.subscription.dto.PackageResponse;
import com.possaas.modules.subscription.dto.SubscriptionResponse;
import com.possaas.modules.subscription.dto.UpdateFeatureRequest;
import com.possaas.modules.subscription.dto.UpdatePackageRequest;
import com.possaas.modules.subscription.entity.PackageFeature;
import com.possaas.modules.subscription.entity.PackageFeatureId;
import com.possaas.modules.subscription.entity.PackagePlan;
import com.possaas.modules.subscription.entity.RestaurantSubscription;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import com.possaas.modules.subscription.repository.FeatureRepository;
import com.possaas.modules.subscription.repository.PackageFeatureRepository;
import com.possaas.modules.subscription.repository.PackagePlanRepository;
import com.possaas.modules.subscription.repository.RestaurantSubscriptionRepository;
import com.possaas.modules.subscription.service.EntitlementService;
import com.possaas.modules.subscription.service.FeatureSnapshotFactory;
import com.possaas.modules.subscription.service.FeatureAdminService;
import com.possaas.modules.subscription.service.PackageAdminService;
import com.possaas.modules.subscription.service.PackageQueryService;
import com.possaas.modules.subscription.service.SubscriptionCommandService;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(SubscriptionModuleIntegrationTest.GuardedUseCaseConfig.class)
class SubscriptionModuleIntegrationTest {
    @Autowired RestaurantRepository restaurantRepository;
    @Autowired RestaurantSubscriptionRepository subscriptionRepository;
    @Autowired PackagePlanRepository packageRepository;
    @Autowired PackageFeatureRepository packageFeatureRepository;
    @Autowired FeatureRepository featureRepository;
    @Autowired SubscriptionCommandService subscriptionCommandService;
    @Autowired EntitlementService entitlementService;
    @Autowired FeatureSnapshotFactory snapshotFactory;
    @Autowired FeatureAdminService featureAdminService;
    @Autowired PackageAdminService packageAdminService;
    @Autowired PackageQueryService packageQueryService;
    @Autowired GuardedFeatureUseCase guardedFeatureUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired MockMvc mockMvc;

    private final List<UUID> restaurantIds = new ArrayList<>();
    private final List<UUID> packageIdsToDelete = new ArrayList<>();
    private final List<UUID> featureIdsToDelete = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        jdbcTemplate.execute("ALTER TABLE audit_logs DISABLE TRIGGER audit_logs_no_update");
        try {
            for (UUID restaurantId : restaurantIds) {
                jdbcTemplate.update("DELETE FROM audit_logs WHERE restaurant_id = ?", restaurantId);
            }
            for (UUID entityId : packageIdsToDelete) {
                jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", entityId);
            }
            for (UUID entityId : featureIdsToDelete) {
                jdbcTemplate.update("DELETE FROM audit_logs WHERE entity_id = ?", entityId);
            }
        } finally {
            jdbcTemplate.execute("ALTER TABLE audit_logs ENABLE TRIGGER audit_logs_no_update");
        }
        for (UUID restaurantId : restaurantIds) {
            jdbcTemplate.update("DELETE FROM restaurant_subscriptions WHERE restaurant_id = ?", restaurantId);
            jdbcTemplate.update("DELETE FROM restaurants WHERE id = ?", restaurantId);
        }
        for (UUID packageId : packageIdsToDelete) {
            jdbcTemplate.update("DELETE FROM package_features WHERE package_id = ?", packageId);
            jdbcTemplate.update("DELETE FROM packages WHERE id = ?", packageId);
        }
        for (UUID featureId : featureIdsToDelete) {
            jdbcTemplate.update("DELETE FROM package_features WHERE feature_id = ?", featureId);
            jdbcTemplate.update("DELETE FROM features WHERE id = ?", featureId);
        }
    }

    @Test
    void seededPackagesExposeTheExpectedFeatureMatrix() {
        PackageResponse basic = packageQueryService.findActivePackage("basic");
        PackageResponse pro = packageQueryService.findActivePackage("PRO");
        PackageResponse premium = packageQueryService.findActivePackage("premium");

        assertThat(featureCodes(basic)).contains("MENU_MANAGEMENT").doesNotContain("TABLE_MANAGEMENT");
        assertThat(featureCodes(pro)).contains("TABLE_MANAGEMENT").doesNotContain("INVENTORY_MANAGEMENT");
        assertThat(featureCodes(premium)).contains("INVENTORY_MANAGEMENT", "AI_DEMAND_FORECAST");
        assertThat(packageQueryService.findActivePackages()).extracting(PackageResponse::code)
            .contains("BASIC", "PRO", "PREMIUM");
    }

    @Test
    void lifecycleSupportsUpgradeDowngradeIdempotentCancelAndKeepsHistory() {
        Restaurant restaurant = createRestaurant();
        RestaurantSubscription basic = activate(restaurant.getId(), "BASIC");

        assertThat(entitlementService.hasFeature(restaurant.getId(), "MENU_MANAGEMENT")).isTrue();
        assertThat(entitlementService.hasFeature(restaurant.getId(), "TABLE_MANAGEMENT")).isFalse();
        assertThatThrownBy(() -> entitlementService.requireFeature(restaurant.getId(), "TABLE_MANAGEMENT"))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("FEATURE_NOT_ENTITLED")
            );

        assertThatThrownBy(() -> subscriptionCommandService.activate(
            restaurant.getId(), basic.getId(), null, "127.0.0.1"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("SUBSCRIPTION_ALREADY_ACTIVE")
        );

        SubscriptionResponse pro = subscriptionCommandService.changePackage(
            restaurant.getId(),
            basic.getId(),
            changeRequest("PRO"),
            null,
            "127.0.0.1"
        );
        RestaurantSubscription storedBasic = subscriptionRepository.findById(basic.getId()).orElseThrow();
        assertThat(storedBasic.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(SubscriptionFeatureSnapshot.fromMap(storedBasic.getFeatureSnapshot()).packageCode())
            .isEqualTo("BASIC");
        assertThat(entitlementService.hasFeature(restaurant.getId(), "TABLE_MANAGEMENT")).isTrue();
        assertThat(entitlementService.hasFeature(restaurant.getId(), "INVENTORY_MANAGEMENT")).isFalse();

        SubscriptionResponse downgraded = subscriptionCommandService.changePackage(
            restaurant.getId(),
            pro.id(),
            changeRequest("BASIC"),
            null,
            "127.0.0.1"
        );
        RestaurantSubscription storedPro = subscriptionRepository.findById(pro.id()).orElseThrow();
        assertThat(storedPro.getStatus()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(SubscriptionFeatureSnapshot.fromMap(storedPro.getFeatureSnapshot()).packageCode())
            .isEqualTo("PRO");
        assertThat(entitlementService.hasFeature(restaurant.getId(), "TABLE_MANAGEMENT")).isFalse();

        SubscriptionResponse firstCancel = subscriptionCommandService.cancel(
            restaurant.getId(), downgraded.id(), null, "127.0.0.1"
        );
        SubscriptionResponse secondCancel = subscriptionCommandService.cancel(
            restaurant.getId(), downgraded.id(), null, "127.0.0.1"
        );
        assertThat(firstCancel.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(secondCancel.status()).isEqualTo(SubscriptionStatus.CANCELLED);
        assertThat(subscriptionRepository.findAllByRestaurantIdOrderByCreatedAtDesc(
            restaurant.getId(), org.springframework.data.domain.Pageable.unpaged()
        ).getTotalElements()).isEqualTo(3);
        assertThatThrownBy(() -> entitlementService.requireFeature(restaurant.getId(), "MENU_MANAGEMENT"))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getCode()).isEqualTo("SUBSCRIPTION_NOT_ACTIVE")
            );
        assertThat(jdbcTemplate.queryForList(
            "SELECT action_code FROM audit_logs WHERE restaurant_id = ? ORDER BY created_at",
            String.class,
            restaurant.getId()
        )).containsExactlyInAnyOrder(
            "SUBSCRIPTION_CREATED",
            "SUBSCRIPTION_ACTIVATED",
            "SUBSCRIPTION_PACKAGE_CHANGED",
            "SUBSCRIPTION_PACKAGE_CHANGED",
            "SUBSCRIPTION_CANCELLED"
        );
    }

    @Test
    void activeSubscriptionUniqueIndexAndOverlapPolicyPreventTwoActiveRows() {
        Restaurant restaurant = createRestaurant();
        RestaurantSubscription first = activate(restaurant.getId(), "PRO");
        SubscriptionResponse second = subscriptionCommandService.create(
            restaurant.getId(), createRequest("BASIC"), null, "127.0.0.1"
        );

        assertThatThrownBy(() -> subscriptionCommandService.activate(
            restaurant.getId(), second.id(), null, "127.0.0.1"
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getCode()).isEqualTo("SUBSCRIPTION_OVERLAP")
        );

        assertThatThrownBy(() -> jdbcTemplate.update(
            """
            INSERT INTO restaurant_subscriptions
                (id, restaurant_id, package_id, status, start_at, end_at, auto_renew,
                 price_amount, currency_code, feature_snapshot)
            VALUES (?, ?, ?, 'ACTIVE', ?, ?, false, ?, 'VND', CAST(? AS jsonb))
            """,
            UUID.randomUUID(),
            restaurant.getId(),
            first.getPackageId(),
            Timestamp.from(Instant.now().minusSeconds(60)),
            Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
            BigDecimal.ZERO,
            "{}"
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void databaseCheckAndForeignKeyConstraintsAreEnforcedByPostgreSql() {
        Restaurant restaurant = createRestaurant();
        PackagePlan basic = packageRepository.findByCode("BASIC").orElseThrow();
        Instant now = Instant.now();

        assertThatThrownBy(() -> insertSubscription(
            restaurant.getId(), basic.getId(), now, now
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertSubscription(
            UUID.randomUUID(), basic.getId(), now, now.plus(1, ChronoUnit.DAYS)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void concurrentActivationLeavesExactlyOneActiveSubscription() throws Exception {
        Restaurant restaurant = createRestaurant();
        SubscriptionResponse first = subscriptionCommandService.create(
            restaurant.getId(), createRequest("BASIC"), null, "127.0.0.1"
        );
        SubscriptionResponse second = subscriptionCommandService.create(
            restaurant.getId(), createRequest("PRO"), null, "127.0.0.1"
        );

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            List<Callable<String>> activations = List.of(
                () -> activationResult(restaurant.getId(), first.id()),
                () -> activationResult(restaurant.getId(), second.id())
            );
            List<Future<String>> futures = executor.invokeAll(activations);
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) {
                results.add(getFuture(future));
            }

            assertThat(results).containsExactlyInAnyOrder("ACTIVE", "SUBSCRIPTION_OVERLAP");
            assertThat(subscriptionRepository.findByRestaurantIdAndStatus(
                restaurant.getId(), SubscriptionStatus.ACTIVE
            )).isPresent();
            assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM restaurant_subscriptions WHERE restaurant_id = ? AND status = 'ACTIVE'",
                Long.class,
                restaurant.getId()
            )).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void activatedSnapshotDoesNotChangeWhenPackageFeatureMappingChanges() {
        Restaurant restaurant = createRestaurant();
        RestaurantSubscription subscription = activate(restaurant.getId(), "BASIC");
        PackagePlan basic = packageRepository.findByCode("BASIC").orElseThrow();
        UUID tableFeatureId = featureRepository.findByCodeAndActiveTrue("TABLE_MANAGEMENT").orElseThrow().getId();
        PackageFeatureId mappingId = new PackageFeatureId(basic.getId(), tableFeatureId);

        try {
            PackageFeature mapping = new PackageFeature();
            mapping.setId(mappingId);
            mapping.setLimits(Map.of("maxTables", 20));
            packageFeatureRepository.saveAndFlush(mapping);

            assertThat(featureCodes(packageQueryService.findActivePackage("BASIC")))
                .contains("TABLE_MANAGEMENT");
            assertThat(entitlementService.hasFeature(restaurant.getId(), "TABLE_MANAGEMENT")).isFalse();
            RestaurantSubscription reloaded = subscriptionRepository.findById(subscription.getId()).orElseThrow();
            assertThat(SubscriptionFeatureSnapshot.fromMap(reloaded.getFeatureSnapshot())
                .contains("TABLE_MANAGEMENT")).isFalse();
        } finally {
            packageFeatureRepository.deleteById(mappingId);
            packageFeatureRepository.flush();
        }
    }

    @Test
    void superAdminCanManageCatalogAndExistingSnapshotRemainsAuthoritative() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
        String featureCode = "CUSTOM_FEATURE_" + suffix;
        String packageCode = "CUSTOM_PACKAGE_" + suffix;
        var feature = featureAdminService.create(
            new CreateFeatureRequest(featureCode, "Custom feature", "Integration test feature"),
            null,
            "127.0.0.1"
        );
        featureIdsToDelete.add(feature.id());
        var packagePlan = packageAdminService.create(
            new CreatePackageRequest(
                packageCode,
                "Custom package",
                "Integration test package",
                new BigDecimal("99000.00"),
                "vnd",
                (short) 1
            ),
            null,
            "127.0.0.1"
        );
        packageIdsToDelete.add(packagePlan.id());

        var mappedPackage = packageAdminService.addFeature(
            packageCode,
            featureCode,
            new PackageFeatureRequest(Map.of("monthlyLimit", 10)),
            null,
            "127.0.0.1"
        );
        assertThat(mappedPackage.features()).singleElement().satisfies(grant -> {
            assertThat(grant.code()).isEqualTo(featureCode);
            assertThat(grant.limits()).containsEntry("monthlyLimit", 10);
        });

        Restaurant restaurant = createRestaurant();
        activate(restaurant.getId(), packageCode);
        assertThat(entitlementService.hasFeature(restaurant.getId(), featureCode)).isTrue();

        featureAdminService.update(
            featureCode,
            new UpdateFeatureRequest("Custom feature", "Disabled after activation", false),
            null,
            "127.0.0.1"
        );
        packageAdminService.removeFeature(packageCode, featureCode, null, "127.0.0.1");

        assertThat(entitlementService.hasFeature(restaurant.getId(), featureCode)).isTrue();
        assertThat(packageAdminService.findAll(true).stream()
            .filter(value -> value.code().equals(packageCode))
            .findFirst().orElseThrow().features()).isEmpty();

        packageAdminService.update(
            packageCode,
            new UpdatePackageRequest(
                "Custom package",
                "Disabled package",
                new BigDecimal("99000.00"),
                "VND",
                (short) 1,
                false
            ),
            null,
            "127.0.0.1"
        );
        assertThat(packageQueryService.findActivePackages()).extracting(PackageResponse::code)
            .doesNotContain(packageCode);
        assertThat(jdbcTemplate.queryForList(
            "SELECT action_code FROM audit_logs WHERE entity_id = ?",
            String.class,
            packagePlan.id()
        )).contains("PACKAGE_FEATURE_ADDED", "PACKAGE_FEATURE_REMOVED");
    }

    @Test
    void methodSecurityRequiresBothFeatureAndPermissionAndNeverCrossesTenant() {
        Restaurant proRestaurant = createRestaurant();
        activate(proRestaurant.getId(), "PRO");
        Restaurant basicRestaurant = createRestaurant();
        activate(basicRestaurant.getId(), "BASIC");
        Restaurant expiredRestaurant = createRestaurant();
        saveDirectSubscription(expiredRestaurant.getId(), "PRO", SubscriptionStatus.ACTIVE,
            Instant.now().minus(2, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        Restaurant cancelledRestaurant = createRestaurant();
        RestaurantSubscription cancelled = activate(cancelledRestaurant.getId(), "PRO");
        subscriptionCommandService.cancel(cancelledRestaurant.getId(), cancelled.getId(), null, "127.0.0.1");

        authenticate(proRestaurant.getId(), "OWNER", "TABLE_CREATE");
        assertThat(guardedFeatureUseCase.createTable()).isEqualTo("created");

        authenticate(basicRestaurant.getId(), "OWNER", "TABLE_CREATE");
        assertDenied();

        authenticate(proRestaurant.getId(), "OWNER");
        assertDenied();

        authenticate(basicRestaurant.getId(), "OWNER");
        assertDenied();

        authenticate(expiredRestaurant.getId(), "OWNER", "TABLE_CREATE");
        assertDenied();

        authenticate(cancelledRestaurant.getId(), "OWNER", "TABLE_CREATE");
        assertDenied();

        authenticate(null, "SUPER_ADMIN", "TABLE_CREATE", "SUBSCRIPTION_MANAGE");
        assertDenied();
    }

    @Test
    void packageAndTenantApisReturnDtosPaginationAndValidationErrors() throws Exception {
        Restaurant restaurant = createRestaurant();
        activate(restaurant.getId(), "PRO");

        mockMvc.perform(get("/api/v1/packages"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code == 'BASIC')]").exists())
            .andExpect(jsonPath("$[0].active").doesNotExist());

        Authentication owner = authenticationFor(restaurant.getId(), "OWNER", "SUBSCRIPTION_VIEW");
        mockMvc.perform(get("/api/v1/subscriptions/current").with(authentication(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.packageCode").value("PRO"))
            .andExpect(jsonPath("$.featureSnapshot").doesNotExist())
            .andExpect(jsonPath("$.version").doesNotExist());
        mockMvc.perform(get("/api/v1/subscriptions/history?page=0&size=1").with(authentication(owner)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.page").value(0))
            .andExpect(jsonPath("$.size").value(1))
            .andExpect(jsonPath("$.totalElements").value(1));

        String validAdminBody = """
            {
              "packageCode": "PRO",
              "startAt": "2026-08-24T00:00:00Z",
              "endAt": "2026-09-24T00:00:00Z",
              "autoRenew": false,
              "priceAmount": 399000,
              "currencyCode": "VND"
            }
            """;
        mockMvc.perform(post("/api/v1/admin/restaurants/{restaurantId}/subscriptions", restaurant.getId())
                .with(authentication(authenticationFor(null, "SUPER_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAdminBody))
            .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/restaurants/{restaurantId}/subscriptions", restaurant.getId())
                .with(authentication(authenticationFor(restaurant.getId(), "OWNER", "SUBSCRIPTION_MANAGE")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validAdminBody))
            .andExpect(status().isForbidden());

        Authentication superAdmin = authenticationFor(null, "SUPER_ADMIN", "SUBSCRIPTION_MANAGE");
        String invalidBody = """
            {
              "packageCode": "PRO",
              "startAt": "2026-08-24T00:00:00Z",
              "endAt": "2026-09-24T00:00:00Z",
              "autoRenew": false,
              "priceAmount": -1,
              "currencyCode": "VN"
            }
            """;
        mockMvc.perform(post("/api/v1/admin/restaurants/{restaurantId}/subscriptions", restaurant.getId())
                .with(authentication(superAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
            .andExpect(jsonPath("$.fieldErrors.priceAmount").exists())
            .andExpect(jsonPath("$.fieldErrors.currencyCode").exists());

        String injectedSnapshotBody = """
            {
              "packageCode": "PRO",
              "startAt": "2026-08-24T00:00:00Z",
              "endAt": "2026-09-24T00:00:00Z",
              "autoRenew": false,
              "priceAmount": 399000,
              "currencyCode": "VND",
              "featureSnapshot": {"features": ["INJECTED"]}
            }
            """;
        mockMvc.perform(post("/api/v1/admin/restaurants/{restaurantId}/subscriptions", restaurant.getId())
                .with(authentication(superAdmin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(injectedSnapshotBody))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST_BODY"));
    }

    private Restaurant createRestaurant() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
        Restaurant restaurant = new Restaurant();
        restaurant.setCode("SUB" + suffix);
        restaurant.setName("Subscription Integration " + suffix);
        restaurant = restaurantRepository.saveAndFlush(restaurant);
        restaurantIds.add(restaurant.getId());
        return restaurant;
    }

    private RestaurantSubscription activate(UUID restaurantId, String packageCode) {
        SubscriptionResponse created = subscriptionCommandService.create(
            restaurantId, createRequest(packageCode), null, "127.0.0.1"
        );
        subscriptionCommandService.activate(restaurantId, created.id(), null, "127.0.0.1");
        return subscriptionRepository.findById(created.id()).orElseThrow();
    }

    private CreateSubscriptionRequest createRequest(String packageCode) {
        Instant now = Instant.now();
        return new CreateSubscriptionRequest(
            packageCode,
            now.minusSeconds(60),
            now.plus(30, ChronoUnit.DAYS),
            false,
            new BigDecimal("399000.00"),
            "vnd"
        );
    }

    private ChangePackageRequest changeRequest(String packageCode) {
        return new ChangePackageRequest(
            packageCode,
            Instant.now().plus(30, ChronoUnit.DAYS),
            false,
            new BigDecimal("399000.00"),
            "vnd"
        );
    }

    private RestaurantSubscription saveDirectSubscription(
        UUID restaurantId,
        String packageCode,
        SubscriptionStatus status,
        Instant startAt,
        Instant endAt
    ) {
        PackagePlan packagePlan = packageRepository.findByCode(packageCode).orElseThrow();
        RestaurantSubscription subscription = new RestaurantSubscription();
        subscription.setRestaurantId(restaurantId);
        subscription.setPackageId(packagePlan.getId());
        subscription.setStatus(status);
        subscription.setStartAt(startAt);
        subscription.setEndAt(endAt);
        subscription.setPriceAmount(BigDecimal.ZERO);
        subscription.setCurrencyCode("VND");
        subscription.setActivatedAt(startAt);
        subscription.setFeatureSnapshot(snapshotFactory.capture(packagePlan).toMap());
        return subscriptionRepository.saveAndFlush(subscription);
    }

    private int insertSubscription(UUID restaurantId, UUID packageId, Instant startAt, Instant endAt) {
        return jdbcTemplate.update(
            """
            INSERT INTO restaurant_subscriptions
                (id, restaurant_id, package_id, status, start_at, end_at, auto_renew,
                 price_amount, currency_code, feature_snapshot)
            VALUES (?, ?, ?, 'PENDING', ?, ?, false, 0, 'VND', '{}'::jsonb)
            """,
            UUID.randomUUID(),
            restaurantId,
            packageId,
            Timestamp.from(startAt),
            Timestamp.from(endAt)
        );
    }

    private String activationResult(UUID restaurantId, UUID subscriptionId) {
        try {
            return subscriptionCommandService.activate(
                restaurantId, subscriptionId, null, "127.0.0.1"
            ).status().name();
        } catch (BusinessException exception) {
            return exception.getCode();
        }
    }

    private static String getFuture(Future<String> future) throws Exception {
        try {
            return future.get();
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw exception;
        }
    }

    private void authenticate(UUID restaurantId, String role, String... permissions) {
        SecurityContextHolder.getContext().setAuthentication(authenticationFor(restaurantId, role, permissions));
    }

    private Authentication authenticationFor(UUID restaurantId, String role, String... permissions) {
        Set<String> permissionSet = Set.of(permissions);
        CurrentUser principal = new CurrentUser(
            UUID.randomUUID(),
            restaurantId,
            "security-test@example.com",
            role,
            permissionSet
        );
        List<SimpleGrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
        permissionSet.stream().map(SimpleGrantedAuthority::new).forEach(authorities::add);
        return new UsernamePasswordAuthenticationToken(principal, "N/A", authorities);
    }

    private void assertDenied() {
        assertThatThrownBy(guardedFeatureUseCase::createTable).isInstanceOf(AccessDeniedException.class);
    }

    private static List<String> featureCodes(PackageResponse response) {
        return response.features().stream().map(feature -> feature.code()).toList();
    }

    static class GuardedFeatureUseCase {
        @PreAuthorize("@featureSecurity.hasCurrentTenantFeature('TABLE_MANAGEMENT') and hasAuthority('TABLE_CREATE')")
        public String createTable() {
            return "created";
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GuardedUseCaseConfig {
        @Bean
        GuardedFeatureUseCase guardedFeatureUseCase() {
            return new GuardedFeatureUseCase();
        }
    }
}
