package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.PublicLinkTokenGenerator;
import com.possaas.infrastructure.security.PublicMenuRequests;
import com.possaas.modules.audit.service.AuditDataRedactor;
import com.possaas.modules.menu.service.PublicMenuAccessPolicy;
import com.possaas.modules.restaurant.dto.*;
import com.possaas.modules.restaurant.entity.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.restaurant.service.PublicRestaurantMenuResolver;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PublicRestaurantMenuPolicyTest {
    @Test void tokenGeneratorAndValidationRetainExistingFormat() {
        var generator = new PublicLinkTokenGenerator(); String token = generator.generate();
        assertThat(token).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(generator.generate()).isNotEqualTo(token);
        for (String invalid : Arrays.asList(null, "", " ", "a".repeat(42), "a".repeat(44), "/".repeat(43)))
            assertThat(PublicLinkTokenGenerator.isValid(invalid)).isFalse();
    }

    @Test void resolverRejectsInvalidTokenWithoutRepositoryAccess() {
        var repository = mock(RestaurantRepository.class); var resolver = new PublicRestaurantMenuResolver(repository);
        assertThatThrownBy(() -> resolver.resolve("invalid")).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("PUBLIC_MENU_NOT_FOUND"));
        verifyNoInteractions(repository);
    }

    @Test void resolverHasOnlyRestaurantEligibilityRules() {
        var repository = mock(RestaurantRepository.class); var resolver = new PublicRestaurantMenuResolver(repository);
        var restaurant = new Restaurant(); restaurant.setId(UUID.randomUUID()); restaurant.setName("Restaurant");
        String token = new PublicLinkTokenGenerator().generate();
        when(repository.findByPublicOrderToken(token)).thenReturn(Optional.of(restaurant));
        assertThat(resolver.resolve(token).restaurantId()).isEqualTo(restaurant.getId());
        for (RestaurantStatus status : List.of(RestaurantStatus.INACTIVE, RestaurantStatus.SUSPENDED)) {
            restaurant.setStatus(status);
            assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo("PUBLIC_MENU_NOT_FOUND"));
        }
        restaurant.setStatus(RestaurantStatus.ACTIVE); restaurant.setDeletedAt(Instant.EPOCH);
        assertThatThrownBy(() -> resolver.resolve(token)).isInstanceOf(BusinessException.class);
    }

    @Test void accessMapsOnlyKnownFeatureFailures() {
        var features = mock(FeatureAccessChecker.class); var policy = new PublicMenuAccessPolicy(features);
        var tenant = UUID.randomUUID();
        for (String code : List.of("FEATURE_NOT_ENTITLED", "SUBSCRIPTION_NOT_ACTIVE")) {
            doThrow(new BusinessException(HttpStatus.FORBIDDEN, code, "private")).when(features).requireFeature(tenant, "QR_MENU_VIEW");
            assertThatThrownBy(() -> policy.requireAccess(tenant)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.getCode()).isEqualTo("PUBLIC_MENU_UNAVAILABLE"));
        }
        var failure = new IllegalStateException("database");
        doThrow(failure).when(features).requireFeature(tenant, "QR_MENU_VIEW");
        assertThatThrownBy(() -> policy.requireAccess(tenant)).isSameAs(failure);
        var invalid = new BusinessException(HttpStatus.CONFLICT, "INVALID_STAFF_LIMIT_CONFIG", "private");
        doThrow(invalid).when(features).requireFeature(tenant, "QR_MENU_VIEW");
        assertThatThrownBy(() -> policy.requireAccess(tenant)).isSameAs(invalid);
    }

    @Test void securityBoundaryAllowsOnlyFourPublicGetRoutes() {
        for (String family : List.of("/api/v1/public/menu/restaurants", "/api/v1/public/menu/tables")) {
            for (String suffix : List.of("/token", "/token/items")) {
                assertThat(PublicMenuRequests.matches(new MockHttpServletRequest("GET", family + suffix))).isTrue();
                for (String method : List.of("POST", "PUT", "PATCH", "DELETE", "HEAD"))
                    assertThat(PublicMenuRequests.matches(new MockHttpServletRequest(method, family + suffix))).isFalse();
            }
            for (String suffix : List.of("", "/token/extra", "/token/items/extra"))
                assertThat(PublicMenuRequests.matches(new MockHttpServletRequest("GET", family + suffix))).isFalse();
        }
        assertThat(PublicMenuRequests.matches(new MockHttpServletRequest("GET", "/api/v1/restaurants/me/menu-link"))).isFalse();
        for (String legacy : List.of("/api/v1/public/qr-menu/token", "/api/v1/public/qr-menu/token/items",
                "/api/v1/public/menu/token", "/api/v1/public/menu/token/items"))
            assertThat(PublicMenuRequests.matches(new MockHttpServletRequest("GET", legacy))).isFalse();
    }

    @Test void sensitiveDtosAndNestedAuditKeysAreRedacted() {
        String token = new PublicLinkTokenGenerator().generate();
        assertThat(RestaurantMenuLinkResponse.fromToken(token).toString()).doesNotContain(token);
        assertThat(new RotateRestaurantMenuLinkRequest(token).toString()).doesNotContain(token);
        var redactor = new AuditDataRedactor();
        for (String key : List.of("menuToken", "restaurant_menu_token", "expected-token", "menuPath")) {
            var source = Map.<String, Object>of("nested", List.of(Map.of(key, token)), "safe", "visible");
            assertThat(redactor.redact(source).toString()).doesNotContain(token).contains("visible", "[REDACTED]");
            assertThat(source.toString()).contains(token);
        }
    }
}
