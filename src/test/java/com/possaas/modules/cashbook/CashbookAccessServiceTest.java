package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.security.*;
import com.possaas.modules.cashbook.service.CashbookAccessService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot.FeatureGrant;
import com.possaas.modules.subscription.entity.SubscriptionStatus;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
class CashbookAccessServiceTest {
    private final CurrentTenantProvider tenants=mock(CurrentTenantProvider.class);
    private final CurrentUserProvider users=mock(CurrentUserProvider.class);
    private final CurrentEntitlementQuery entitlements=mock(CurrentEntitlementQuery.class);
    private final RestaurantRepository restaurants=mock(RestaurantRepository.class);
    private final CashbookAccessService access=new CashbookAccessService(tenants,users,entitlements,restaurants);
    private final UUID tenant=UUID.randomUUID();
    private void fixture(Set<String> permissions,Set<String> features) {
        when(tenants.getRequiredRestaurantId()).thenReturn(tenant);
        when(users.getRequired()).thenReturn(new CurrentUser(UUID.randomUUID(),tenant,"x@test","STAFF",permissions));
        when(entitlements.getForRestaurant(tenant)).thenReturn(new CurrentEntitlement(UUID.randomUUID(),tenant,"TEST",SubscriptionStatus.ACTIVE,
            Instant.EPOCH,Instant.MAX,features.stream().map(code->new FeatureGrant(code,Map.of())).toList()));
    }
    @Test void deniedPermissionDoesNotReadEntitlementOrDomain() {
        fixture(Set.of(),Set.of("DETAIL_REPORT"));
        assertThatThrownBy(()->access.read(false)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(entitlements,restaurants);
    }
    @Test void missingFeatureDoesNotLockRestaurant() {
        fixture(Set.of("CASHBOOK_CREATE"),Set.of());
        assertThatThrownBy(()->access.create(false)).hasMessageContaining("package");verifyNoInteractions(restaurants);
        verify(entitlements,times(1)).getForRestaurant(tenant);
    }
    @Test void linkedCreateUsesSingleEntitlementAndRestaurantMutationLock() {
        fixture(Set.of("CASHBOOK_CREATE","INVENTORY_VIEW"),Set.of("DETAIL_REPORT","INVENTORY_MANAGEMENT"));
        var restaurant=new Restaurant();when(restaurants.findByIdForUpdate(tenant)).thenReturn(Optional.of(restaurant));
        assertThat(access.create(true).restaurant()).isSameAs(restaurant);verify(entitlements,times(1)).getForRestaurant(tenant);
    }
    @Test void voidOnlyRequiresCashbookAndDetailFeatureEvenForHistoricalLinkedExpense() {
        fixture(Set.of("CASHBOOK_VOID"),Set.of("DETAIL_REPORT"));
        when(restaurants.findByIdForUpdate(tenant)).thenReturn(Optional.of(new Restaurant()));
        access.voidExpense();verify(entitlements,times(1)).getForRestaurant(tenant);
    }
    @Test void receiptReadRequiresInventoryPermissionBeforeQueries() {
        fixture(Set.of("CASHBOOK_VIEW"),Set.of("DETAIL_REPORT","INVENTORY_MANAGEMENT"));
        assertThatThrownBy(()->access.read(true)).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        verifyNoInteractions(entitlements,restaurants);
    }
}
