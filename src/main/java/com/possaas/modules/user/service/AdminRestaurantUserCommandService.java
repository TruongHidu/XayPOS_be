package com.possaas.modules.user.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.user.application.port.TemporaryPasswordGenerator;
import com.possaas.modules.user.application.port.UserSessionRevoker;
import com.possaas.modules.user.dto.AdminRestaurantUserResponse;
import com.possaas.modules.user.dto.AdminUserSupportDtos.TemporaryPasswordRequest;
import com.possaas.modules.user.dto.AdminUserSupportDtos.TemporaryPasswordResponse;
import com.possaas.modules.user.dto.AdminUserSupportDtos.UpdateUserStatusRequest;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Support desk actions on one restaurant user account: lock/unlock and one-time password reset. */
@Service
@RequiredArgsConstructor
public class AdminRestaurantUserCommandService {
    private final RestaurantRepository restaurants;
    private final UserRepository users;
    private final AdminRestaurantUserQueryService queries;
    private final UserSessionRevoker sessions;
    private final TemporaryPasswordGenerator passwords;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;

    @Transactional
    public AdminRestaurantUserResponse updateStatus(UUID restaurantId, UUID userId, UpdateUserStatusRequest request,
            UUID actorId, String ip) {
        User user = lockUser(restaurantId, userId);
        boolean before = user.isActive();
        if (before != request.active()) {
            user.setActive(request.active());
            users.saveAndFlush(user);
            if (!request.active()) sessions.revokeAllActiveForUser(userId);
            audit.record(restaurantId, actorId, request.active() ? "ADMIN_USER_ENABLED" : "ADMIN_USER_DISABLED", "users", userId,
                Map.of("active", before), Map.of("active", request.active(), "reason", request.reason().trim()), ip);
        }
        return queries.findDetail(restaurantId, userId);
    }

    /** The plain password is returned once and never stored or audited. */
    @Transactional
    public TemporaryPasswordResponse issueTemporaryPassword(UUID restaurantId, UUID userId, TemporaryPasswordRequest request,
            UUID actorId, String ip) {
        User user = lockUser(restaurantId, userId);
        String password = passwords.generate();
        user.setPasswordHash(passwordEncoder.encode(password));
        users.saveAndFlush(user);
        int revoked = sessions.revokeAllActiveForUser(userId);
        audit.record(restaurantId, actorId, "ADMIN_USER_PASSWORD_RESET", "users", userId, null,
            Map.of("reason", request.reason().trim(), "revokedSessions", revoked), ip);
        return new TemporaryPasswordResponse(password, revoked);
    }

    private User lockUser(UUID restaurantId, UUID userId) {
        restaurants.findByIdAndDeletedAtIsNull(restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("RESTAURANT_NOT_FOUND", "Restaurant not found"));
        return users.findTenantUserForUpdate(userId, restaurantId)
            .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
    }
}
