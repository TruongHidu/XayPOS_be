package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.repository.*;
import com.possaas.modules.qrorder.service.*;
import com.possaas.modules.restaurant.entity.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.table.entity.*;
import com.possaas.modules.table.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QrScopePolicyTest {
    private final UUID tenant=UUID.randomUUID(),tableId=UUID.randomUUID();
    private final Instant now=Instant.parse("2026-10-14T00:00:00Z");
    private final RestaurantRepository restaurants=mock(RestaurantRepository.class);
    private final RestaurantTableRepository tables=mock(RestaurantTableRepository.class);
    private final TableAreaRepository areas=mock(TableAreaRepository.class);
    private final QrTableAccessRepository access=mock(QrTableAccessRepository.class);
    private final QrGuestAuthorizationRepository grants=mock(QrGuestAuthorizationRepository.class);
    private final FeatureAccessChecker features=mock(FeatureAccessChecker.class);
    private final QrScopePolicy policy=new QrScopePolicy(restaurants,tables,areas,access,features,grants,Clock.fixed(now,ZoneOffset.UTC));

    @Test void restaurantOrderingUsesQrStaticWithoutTouchingAnyTableOrQuickPosGate() {
        var grant=grant(QrEntryType.RESTAURANT); restaurant();
        var resolved=policy.validate(grant);
        assertThat(resolved.table()).isNull();
        verify(features).requireFeature(tenant,"QR_MENU_VIEW"); verify(features).requireFeature(tenant,"QR_STATIC_ORDER");
        verify(features).requireFeature(tenant,"ORDER_MANAGEMENT");
        verifyNoMoreInteractions(features); verifyNoInteractions(tables,areas,access);
    }
    @Test void tableOrderingRequiresTableAndQrCapabilitiesAndRejectsChangedEpoch() {
        var grant=grant(QrEntryType.TABLE); restaurant(); table();
        assertThat(policy.validate(grant).epoch()).isZero();
        verify(features).requireFeature(tenant,"QR_TABLE_ORDER"); verify(features).requireFeature(tenant,"TABLE_MANAGEMENT");
        var epoch=new QrTableAccess(); epoch.setEpoch(1);
        when(access.findByTableIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.of(epoch));
        assertUnavailable(()->policy.validate(grant));
    }
    @Test void rotatedRestaurantCredentialIsUnavailableWithoutCallingTable() {
        var grant=grant(QrEntryType.RESTAURANT); var restaurant=restaurant(); restaurant.setPublicOrderToken("new-credential");
        assertUnavailable(()->policy.validate(grant)); verifyNoInteractions(tables,areas,access);
    }
    @Test void unavailableOrderingFeatureReturnsStablePublicCodeNotSubscriptionInternals() {
        var grant=grant(QrEntryType.RESTAURANT); restaurant();
        doThrow(new ConflictException("SUBSCRIPTION_NOT_ACTIVE","private-subscription-detail")).when(features).requireFeature(tenant,"QR_STATIC_ORDER");
        assertThatThrownBy(()->policy.validate(grant)).isInstanceOf(BusinessException.class)
                .hasMessage("QR ordering is not available.").satisfies(f->{assertThat(((BusinessException)f).getCode()).isEqualTo("QR_ORDER_UNAVAILABLE");});
    }
    @Test void credentialReadCanRemainValidWhenOrderingEntitlementWasRemoved() {
        var grant=grant(QrEntryType.RESTAURANT); restaurant(); var request=request(grant);
        when(grants.findByIdAndRestaurantId(grant.getId(),tenant)).thenReturn(Optional.of(grant));
        doThrow(new ConflictException("FEATURE_NOT_ENTITLED","private")).when(features).requireFeature(tenant,"QR_STATIC_ORDER");
        assertThat(policy.isCredentialValid(request)).isTrue(); assertThat(policy.isValid(request)).isFalse();
    }
    @Test void approvalCredentialStateRejectsRevocationExpiryAndWrongGuestIdentity() {
        var grant=grant(QrEntryType.RESTAURANT); restaurant(); var request=request(grant);
        when(grants.findByIdAndRestaurantId(grant.getId(),tenant)).thenReturn(Optional.of(grant));
        grant.setRevokedAt(now); assertThat(policy.isCredentialValid(request)).isFalse();
        grant.setRevokedAt(null); grant.setExpiresAt(now); assertThat(policy.isCredentialValid(request)).isFalse();
        grant.setExpiresAt(now.plusSeconds(60)); grant.setGuestId(UUID.randomUUID()); assertThat(policy.isCredentialValid(request)).isFalse();
    }
    @Test void tableCredentialRequiresAvailableUndeletedTableAndActiveArea() {
        var grant=grant(QrEntryType.TABLE); restaurant(); var table=table(); table.setStatus(TableStatus.INACTIVE);
        assertUnavailable(()->policy.validate(grant)); table.setStatus(TableStatus.AVAILABLE);
        table.setAreaId(UUID.randomUUID()); var area=new TableArea(); area.setActive(false);
        when(areas.findByIdAndRestaurantId(table.getAreaId(),tenant)).thenReturn(Optional.of(area));
        assertUnavailable(()->policy.validate(grant));
    }
    private Restaurant restaurant() {
        var value=new Restaurant(); value.setId(tenant); value.setPublicOrderToken("credential"); value.setName("Restaurant");
        when(restaurants.findByIdAndDeletedAtIsNull(tenant)).thenReturn(Optional.of(value)); return value;
    }
    private RestaurantTable table() {
        var value=new RestaurantTable(); value.setId(tableId); value.setRestaurantId(tenant); value.setQrToken("credential");
        when(tables.findByIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.of(value));
        when(access.findByTableIdAndRestaurantId(tableId,tenant)).thenReturn(Optional.empty()); return value;
    }
    private QrGuestAuthorization grant(QrEntryType type) {
        var value=new QrGuestAuthorization(); value.setId(UUID.randomUUID()); value.setRestaurantId(tenant); value.setGuestId(UUID.randomUUID());
        value.setEntryType(type); value.setCredentialHash(QrHashes.digest("credential")); value.setExpiresAt(now.plusSeconds(3600));
        if(type==QrEntryType.TABLE) { value.setTableId(tableId); value.setTableAccessEpoch(0L); } return value;
    }
    private QrOrderRequest request(QrGuestAuthorization grant) {
        var value=new QrOrderRequest(); value.setRestaurantId(tenant); value.setGuestAuthorizationId(grant.getId()); value.setGuestId(grant.getGuestId());
        value.setEntryType(grant.getEntryType()); value.setCredentialHash(grant.getCredentialHash()); value.setTableId(grant.getTableId()); value.setTableAccessEpoch(grant.getTableAccessEpoch());
        return value;
    }
    private static void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class).satisfies(f->assertThat(((BusinessException)f).getCode()).isEqualTo("QR_ORDER_UNAVAILABLE"));
    }
}
