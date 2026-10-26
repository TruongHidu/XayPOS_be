package com.possaas.infrastructure.realtime;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.application.port.RestaurantAccessChecker;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.subscription.domain.CurrentEntitlement;
import com.possaas.modules.user.application.port.UserAccessChecker;
import com.possaas.modules.user.repository.UserRepository;
import java.time.Clock;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Explicit identity only: delivery workers never depend on an HTTP security context. */
@Service @RequiredArgsConstructor
@Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
public class RealtimeAccess {
    private final JwtDecoder decoder;
    private final UserRepository users;
    private final RoleRepository roles;
    private final UserAccessChecker accounts;
    private final RestaurantAccessChecker restaurants;
    private final PermissionService permissions;
    private final CurrentEntitlementQuery entitlements;
    private final Clock clock;

    public RealtimePrincipal authenticate(String authorization, String sessionId) {
        if (authorization == null || authorization.length() > 8192 || !authorization.startsWith("Bearer ")
                || authorization.substring(7).isBlank()) throw new RealtimeRejected("UNAUTHORIZED");
        try {
            var jwt = decoder.decode(authorization.substring(7));
            var principal = new RealtimePrincipal(sessionId, UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("restaurant_id")), jwt.getClaimAsString("role"), jwt.getExpiresAt());
            requireCurrent(principal);
            return principal;
        } catch (JwtException | IllegalArgumentException | NullPointerException invalid) {
            throw new RealtimeRejected("UNAUTHORIZED");
        }
    }
    public void requireCurrent(RealtimePrincipal principal) {
        requireTime(principal);
        entitlement(principal.restaurantId());
        identity(principal);
    }
    public void requireChannel(RealtimePrincipal principal, RealtimeChannel channel) {
        requireTime(principal);
        var entitlement = entitlement(principal.restaurantId());
        channel.require(principal.role(), identity(principal), entitlement);
    }

    /** One tenant entitlement lookup and one effective-permission lookup per distinct user/role. */
    public Map<String, Decision> authorizeBatch(UUID tenant, List<RealtimePrincipal> candidates,
            Set<RealtimeChannel> channels) {
        Map<String, Decision> result = new HashMap<>();
        CurrentEntitlement entitlement;
        try { entitlement = entitlement(tenant); }
        catch (RealtimeRejected denied) {
            candidates.forEach(p -> result.put(p.sessionId(), new Decision(Set.of(), denied.code())));
            return Map.copyOf(result);
        }
        Map<Identity, Decision> decisions = new HashMap<>();
        for (var principal : candidates) {
            try {
                requireTime(principal);
                if (!tenant.equals(principal.restaurantId())) throw new RealtimeRejected("FORBIDDEN");
                var decision = decisions.computeIfAbsent(new Identity(principal.userId(), principal.role()), key -> {
                    try {
                        var effective = identity(principal);
                        Set<RealtimeChannel> allowed = EnumSet.noneOf(RealtimeChannel.class);
                        for (var channel : channels) {
                            try { channel.require(principal.role(), effective, entitlement); allowed.add(channel); }
                            catch (RealtimeRejected denied) { /* Fence only this channel. */ }
                        }
                        return new Decision(allowed, null);
                    } catch (RealtimeRejected denied) { return new Decision(Set.of(), denied.code()); }
                    catch (RuntimeException unavailable) { return new Decision(Set.of(), "FORBIDDEN"); }
                });
                result.put(principal.sessionId(), decision);
            } catch (RealtimeRejected denied) { result.put(principal.sessionId(), new Decision(Set.of(), denied.code())); }
        }
        return Map.copyOf(result);
    }
    private CurrentEntitlement entitlement(UUID tenant) {
        if (tenant == null) throw new RealtimeRejected("UNAUTHORIZED");
        if (!restaurants.isActive(tenant)) throw new RealtimeRejected("RESTAURANT_INACTIVE");
        try { return entitlements.getForRestaurant(tenant); }
        catch (BusinessException denied) {
            throw new RealtimeRejected("SUBSCRIPTION_NOT_ACTIVE".equals(denied.getCode()) ? denied.getCode() : "FORBIDDEN");
        }
    }
    private Set<String> identity(RealtimePrincipal principal) {
        if (principal.role() == null || "SUPER_ADMIN".equals(principal.role())) throw new RealtimeRejected("FORBIDDEN");
        if (!accounts.isActive(principal.userId(), principal.restaurantId())) throw new RealtimeRejected("ACCOUNT_INACTIVE");
        var user = users.findByIdAndRestaurantIdAndDeletedAtIsNull(principal.userId(), principal.restaurantId())
                .filter(u -> u.isActive()).orElseThrow(() -> new RealtimeRejected("ACCOUNT_INACTIVE"));
        var role = roles.findById(user.getRoleId()).orElseThrow(() -> new RealtimeRejected("FORBIDDEN"));
        if (!role.isActive() || !principal.role().equals(role.getCode())
                || role.getRestaurantId() != null && !principal.restaurantId().equals(role.getRestaurantId()))
            throw new RealtimeRejected("FORBIDDEN");
        return permissions.getEffectivePermissionCodes(user);
    }
    private void requireTime(RealtimePrincipal p) {
        if (p == null || p.expiresAt() == null || !p.expiresAt().isAfter(clock.instant()))
            throw new RealtimeRejected("TOKEN_EXPIRED");
    }
    private record Identity(UUID userId, String role) {}
    public record Decision(Set<RealtimeChannel> channels, String rejection) {
        public Decision { channels = Set.copyOf(channels); }
    }
}
