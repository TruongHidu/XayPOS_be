package com.possaas.infrastructure.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.application.port.RestaurantAccessChecker;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import java.util.List;
import com.possaas.modules.user.application.port.UserAccessChecker;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class RealtimeAccessTest {
    final Instant now = Instant.parse("2026-10-24T08:00:00Z");
    final UUID userId = UUID.randomUUID(), tenant = UUID.randomUUID(), roleId = UUID.randomUUID();
    final JwtDecoder decoder = mock(JwtDecoder.class);
    final UserRepository users = mock(UserRepository.class);
    final RoleRepository roles = mock(RoleRepository.class);
    final UserAccessChecker accounts = mock(UserAccessChecker.class);
    final RestaurantAccessChecker restaurants = mock(RestaurantAccessChecker.class);
    final PermissionService permissions = mock(PermissionService.class);
    final CurrentEntitlementQuery features = mock(CurrentEntitlementQuery.class);
    final CurrentEntitlement entitlement = mock(CurrentEntitlement.class);
    final RealtimeAccess access = new RealtimeAccess(decoder, users, roles, accounts,
            restaurants, permissions, features, Clock.fixed(now, ZoneOffset.UTC));
    User user;
    Role role;

    @BeforeEach void allowed() {
        user = new User(); user.setId(userId); user.setRestaurantId(tenant); user.setRoleId(roleId); user.setActive(true);
        role = new Role(); role.setId(roleId); role.setCode("OWNER"); role.setActive(true);
        when(decoder.decode("access")).thenReturn(token("OWNER", now.plusSeconds(60)));
        when(accounts.isActive(userId, tenant)).thenReturn(true);
        when(restaurants.isActive(tenant)).thenReturn(true);
        when(users.findByIdAndRestaurantIdAndDeletedAtIsNull(userId, tenant)).thenReturn(Optional.of(user));
        when(roles.findById(roleId)).thenReturn(Optional.of(role));
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of("INVENTORY_VIEW"));
        when(features.getForRestaurant(tenant)).thenReturn(entitlement);
        when(entitlement.hasFeature(anyString())).thenReturn(true);
    }
    Jwt token(String code, Instant expiry) {
        return Jwt.withTokenValue("access").header("alg", "HS256").subject(userId.toString())
                .claim("restaurant_id", tenant.toString()).claim("role", code)
                .claim("permissions", Set.of("INVENTORY_VIEW"))
                .issuedAt(now.minusSeconds(10)).expiresAt(expiry).build();
    }
    void rejected(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOf(RealtimeRejected.class).extracting("code").isEqualTo(code);
    }

    @Test void authenticatesEachSocketAndChecksCurrentEffectiveGrants() {
        var principal = access.authenticate("Bearer access", "s1");
        assertThat(principal.getName()).isEqualTo("s1");
        assertThat(principal.userId()).isEqualTo(userId);
        assertThat(principal.restaurantId()).isEqualTo(tenant);
        access.requireChannel(principal,RealtimeChannel.INVENTORY_ALERTS);
        verify(entitlement).hasFeature("INVENTORY_MANAGEMENT");
        verify(entitlement).hasFeature("LOW_STOCK_ALERT");
    }
    @Test void missingMalformedOrInvalidBearerIsUnauthorized() {
        rejected(() -> access.authenticate(null, "s1"), "UNAUTHORIZED");
        rejected(() -> access.authenticate("Basic access", "s1"), "UNAUTHORIZED");
        when(decoder.decode("bad")).thenThrow(new JwtException("Sensitive decoder detail"));
        rejected(() -> access.authenticate("Bearer bad", "s1"), "UNAUTHORIZED");
    }
    @Test void explicitlyRejectsExpiredTokensEvenInsideDecoderClockSkew() {
        when(decoder.decode("access")).thenReturn(token("OWNER", now));
        rejected(() -> access.authenticate("Bearer access", "s1"), "TOKEN_EXPIRED");
    }
    @Test void tenantlessAndAdministrativeTokensCannotListenToTenantAlerts() {
        when(decoder.decode("access")).thenReturn(Jwt.withTokenValue("access").header("alg", "HS256")
                .subject(userId.toString()).claim("role", "SUPER_ADMIN").expiresAt(now.plusSeconds(60)).build());
        rejected(() -> access.authenticate("Bearer access", "s1"), "UNAUTHORIZED");
        when(decoder.decode("access")).thenReturn(token("SUPER_ADMIN", now.plusSeconds(60)));
        rejected(() -> access.authenticate("Bearer access", "s1"), "FORBIDDEN");
    }
    @Test void staleJwtPermissionCannotOverrideRevokedEffectivePermission() {
        var principal = access.authenticate("Bearer access", "s1");
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of());
        rejected(() -> access.requireChannel(principal,RealtimeChannel.INVENTORY_ALERTS), "FORBIDDEN");
    }
    @Test void unavailableAccountsAndRestaurantsAreRejectedAtDelivery() {
        var principal = access.authenticate("Bearer access", "s1");
        when(accounts.isActive(userId, tenant)).thenReturn(false);
        rejected(() -> access.requireCurrent(principal), "ACCOUNT_INACTIVE");
        when(accounts.isActive(userId, tenant)).thenReturn(true);
        when(restaurants.isActive(tenant)).thenReturn(false);
        rejected(() -> access.requireCurrent(principal), "RESTAURANT_INACTIVE");
    }
    @Test void changedOrDisabledRoleAndForeignTenantRoleAreRejected() {
        var principal = access.authenticate("Bearer access", "s1");
        role.setCode("WAITER"); rejected(() -> access.requireCurrent(principal), "FORBIDDEN");
        role.setCode("OWNER"); role.setActive(false); rejected(() -> access.requireCurrent(principal), "FORBIDDEN");
        role.setActive(true); role.setRestaurantId(UUID.randomUUID());
        rejected(() -> access.requireCurrent(principal), "FORBIDDEN");
    }
    @Test void missingFeatureAndInactiveSubscriptionRetainStableCodes() {
        var principal = access.authenticate("Bearer access", "s1");
        when(entitlement.hasFeature("LOW_STOCK_ALERT")).thenReturn(false);
        rejected(() -> access.requireChannel(principal,RealtimeChannel.INVENTORY_ALERTS), "FEATURE_NOT_ENTITLED");
        when(features.getForRestaurant(tenant)).thenThrow(new BusinessException(HttpStatus.FORBIDDEN, "SUBSCRIPTION_NOT_ACTIVE", "Internal detail"));
        rejected(() -> access.requireCurrent(principal), "SUBSCRIPTION_NOT_ACTIVE");
    }
    @Test void cashierOrderOnlyAndKitchenOnlyCanConnectAndSubscribeWithoutInventory() {
        role.setCode("CASHIER"); when(decoder.decode("access")).thenReturn(token("CASHIER",now.plusSeconds(60)));
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of("ORDER_VIEW"));
        when(entitlement.hasFeature(anyString())).thenAnswer(c -> "ORDER_MANAGEMENT".equals(c.getArgument(0)));
        var cashier=access.authenticate("Bearer access","cashier");
        access.requireChannel(cashier,RealtimeChannel.ORDERS); access.requireChannel(cashier,RealtimeChannel.QR_REQUESTS);
        rejected(() -> access.requireChannel(cashier,RealtimeChannel.KITCHEN),"FORBIDDEN");
        rejected(() -> access.requireChannel(cashier,RealtimeChannel.INVENTORY_ALERTS),"FORBIDDEN");
        role.setCode("KITCHEN"); when(decoder.decode("access")).thenReturn(token("KITCHEN",now.plusSeconds(60)));
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of("KITCHEN_VIEW"));
        when(entitlement.hasFeature(anyString())).thenAnswer(c -> "KITCHEN_DISPLAY".equals(c.getArgument(0)));
        var kitchen=access.authenticate("Bearer access","kitchen");
        access.requireChannel(kitchen,RealtimeChannel.KITCHEN);
        rejected(() -> access.requireChannel(kitchen,RealtimeChannel.ORDERS),"FORBIDDEN");
    }
    @Test void dispatchBatchesCurrentPermissionsByUserNotBySocketOrChannel() {
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of("ORDER_VIEW","KITCHEN_VIEW"));
        var first=access.authenticate("Bearer access","one"); var second=access.authenticate("Bearer access","two");
        clearInvocations(permissions,features);
        var decisions=access.authorizeBatch(tenant,List.of(first,second),Set.of(RealtimeChannel.ORDERS,RealtimeChannel.KITCHEN));
        assertThat(decisions).hasSize(2);
        assertThat(decisions.get("one").channels()).containsExactlyInAnyOrder(RealtimeChannel.ORDERS,RealtimeChannel.KITCHEN);
        verify(permissions,times(1)).getEffectivePermissionCodes(user);
        verify(features,times(1)).getForRestaurant(tenant);
        when(permissions.getEffectivePermissionCodes(user)).thenReturn(Set.of("ORDER_VIEW"));
        var revoked=access.authorizeBatch(tenant,List.of(first,second),Set.of(RealtimeChannel.ORDERS,RealtimeChannel.KITCHEN));
        assertThat(revoked.get("one").channels()).containsExactly(RealtimeChannel.ORDERS);
        assertThat(revoked.get("one").rejection()).isNull();
    }
}
