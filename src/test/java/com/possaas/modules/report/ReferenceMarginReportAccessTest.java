package com.possaas.modules.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.*;
import com.possaas.modules.report.service.ReferenceMarginReportAccess;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;

class ReferenceMarginReportAccessTest {
    final UUID tenant = UUID.randomUUID();
    final CurrentTenantProvider tenants = mock(CurrentTenantProvider.class);
    final CurrentUserProvider users = mock(CurrentUserProvider.class);
    final CurrentEntitlementQuery entitlements = mock(CurrentEntitlementQuery.class);
    final CurrentEntitlement entitlement = mock(CurrentEntitlement.class);
    final RestaurantRepository restaurants = mock(RestaurantRepository.class);
    final ReferenceMarginReportAccess access = new ReferenceMarginReportAccess(tenants, users, entitlements, restaurants);

    @BeforeEach void setup() {
        when(tenants.getRequiredRestaurantId()).thenReturn(tenant);
        when(users.getRequired()).thenReturn(actor(Set.of("ORDER_VIEW", "REPORT_DETAIL_VIEW")));
    }

    @ParameterizedTest @ValueSource(strings = {"ORDER_VIEW", "REPORT_DETAIL_VIEW"})
    void missingPermissionStopsBeforeEntitlementAndData(String missing) {
        var permissions = new HashSet<>(Set.of("ORDER_VIEW", "REPORT_DETAIL_VIEW"));
        permissions.remove(missing);
        when(users.getRequired()).thenReturn(actor(permissions));
        assertThatThrownBy(access::requireRead).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(entitlements, restaurants);
    }

    @ParameterizedTest @ValueSource(strings = {"ORDER_MANAGEMENT", "DETAIL_REPORT"})
    void missingFeatureStopsBeforeRestaurantQuery(String missing) {
        when(entitlements.getForRestaurant(tenant)).thenReturn(entitlement);
        when(entitlement.hasFeature("ORDER_MANAGEMENT")).thenReturn(true);
        when(entitlement.hasFeature("DETAIL_REPORT")).thenReturn(true);
        when(entitlement.hasFeature(missing)).thenReturn(false);
        assertThatThrownBy(access::requireRead).isInstanceOfSatisfying(BusinessException.class,
            failure -> assertThat(failure.getCode()).isEqualTo("FEATURE_NOT_ENTITLED"));
        verify(entitlements, times(1)).getForRestaurant(tenant);
        verifyNoInteractions(restaurants);
    }

    @Test void allowedReadsOneEntitlementWithoutExtraFeatureChecks() {
        when(entitlements.getForRestaurant(tenant)).thenReturn(entitlement);
        when(entitlement.hasFeature("ORDER_MANAGEMENT")).thenReturn(true);
        when(entitlement.hasFeature("DETAIL_REPORT")).thenReturn(true);
        var restaurant = mock(Restaurant.class);
        when(restaurants.findByIdAndDeletedAtIsNull(tenant)).thenReturn(Optional.of(restaurant));
        assertThat(access.requireRead()).isSameAs(restaurant);
        verify(entitlements, times(1)).getForRestaurant(tenant);
        verify(entitlement, never()).hasFeature("INVENTORY_MANAGEMENT");
        verify(entitlement, never()).hasFeature("RECIPE_MANAGEMENT");
    }

    private CurrentUser actor(Set<String> permissions) {
        return new CurrentUser(UUID.randomUUID(), tenant, "staff@example.test", "WAITER", permissions);
    }
}
