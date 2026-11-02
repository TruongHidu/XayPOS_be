package com.possaas.modules.subscriptionpurchase.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.security.CurrentUser;
import com.possaas.common.security.CurrentUserProvider;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashSet;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class PurchaseAccessPolicy {
    public static final String VIEW = "SUBSCRIPTION_PURCHASE_VIEW";
    public static final String CREATE = "SUBSCRIPTION_PURCHASE_CREATE";
    public static final String CANCEL = "SUBSCRIPTION_PURCHASE_CANCEL";
    private final CurrentUserProvider currentUsers;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PermissionService permissions;
    private final RestaurantRepository restaurants;
    private final EntityManager entityManager;

    /** Re-check persisted access before any replay lookup; stale JWT grants cannot keep access. */
    @Transactional(readOnly = true)
    public CurrentUser require(String permission) {
        CurrentUser principal;
        try { principal = currentUsers.getRequired(); }
        catch (IllegalStateException missing) { throw error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"); }
        if (principal.restaurantId() == null || !"OWNER".equals(principal.role())) throw forbidden();
        var user = users.findByIdAndRestaurantIdAndDeletedAtIsNull(principal.userId(), principal.restaurantId())
            .filter(u -> u.isActive()).orElseThrow(() -> error(HttpStatus.UNAUTHORIZED, "ACCOUNT_INACTIVE"));
        var role = roles.findById(user.getRoleId()).filter(r -> r.isActive() && "OWNER".equals(r.getCode())
            && (r.getRestaurantId() == null || r.getRestaurantId().equals(principal.restaurantId())))
            .orElseThrow(PurchaseAccessPolicy::forbidden);
        var effective = new HashSet<>(permissions.getEffectivePermissionCodes(user));
        effective.retainAll(principal.permissions() == null ? java.util.Set.of() : principal.permissions());
        if (!effective.contains(permission)) throw forbidden();
        requireActive(restaurants.findByIdAndDeletedAtIsNull(principal.restaurantId()).orElse(null));
        return new CurrentUser(user.getId(), user.getRestaurantId(), user.getEmail(), role.getCode(), java.util.Set.copyOf(effective));
    }

    public void lockRestaurant(UUID tenant) {
        var restaurant = restaurants.findByIdForUpdate(tenant).orElse(null);
        // OSIV may have cached the entity during the earlier security/read transaction.
        if (restaurant != null) entityManager.refresh(restaurant, LockModeType.PESSIMISTIC_WRITE);
        requireActive(restaurant);
    }

    private static void requireActive(Restaurant restaurant) {
        if (restaurant == null || restaurant.getStatus() != RestaurantStatus.ACTIVE)
            throw error(HttpStatus.FORBIDDEN, "RESTAURANT_INACTIVE");
    }
    private static BusinessException forbidden() { return error(HttpStatus.FORBIDDEN, "FORBIDDEN"); }
    private static BusinessException error(HttpStatus status, String code) { return new BusinessException(status, code, "Purchase access denied"); }
}
