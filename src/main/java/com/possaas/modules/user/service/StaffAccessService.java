package com.possaas.modules.user.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.common.security.*;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.subscription.application.port.FeatureAccessChecker;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Shared tenant access and lookup boundary; business decisions remain in policies. */
@Service
@RequiredArgsConstructor
public class StaffAccessService {
    private final CurrentTenantProvider tenant;
    private final CurrentUserProvider currentUser;
    private final FeatureAccessChecker features;
    private final StaffManagementPolicy policy;
    private final RestaurantRepository restaurants;
    private final UserRepository users;
    private final RoleRepository roles;

    public CurrentUser requireAccess(boolean permissionManagement, boolean lock) {
        UUID restaurantId = tenant.getRequiredRestaurantId();
        CurrentUser actor = currentUser.getRequired();
        policy.assignableRoles(actor);
        if (lock) restaurants.findByIdForUpdate(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        features.requireFeature(restaurantId, "STAFF_MANAGEMENT");
        if (permissionManagement) features.requireFeature(restaurantId, "STAFF_PERMISSION");
        return actor;
    }

    public User target(UUID tenantId, UUID userId, boolean lock) {
        return (lock ? users.findTenantUserForUpdate(userId, tenantId) : users.findByIdAndRestaurantIdAndDeletedAtIsNull(userId, tenantId))
            .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
    }

    public Role role(User user) {
        return roles.findById(user.getRoleId())
            .filter(r -> r.getRestaurantId() == null || r.getRestaurantId().equals(user.getRestaurantId()))
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Role not found"));
    }

    public Role assignableRole(CurrentUser actor, String code) {
        policy.requireAssignable(actor, code);
        return roles.findByCodeAndRestaurantIdIsNull(code).filter(r -> r.isSystem() && r.isActive())
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "Active system role not found"));
    }
}
