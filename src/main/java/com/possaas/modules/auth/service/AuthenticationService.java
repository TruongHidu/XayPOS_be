package com.possaas.modules.auth.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.dto.AuthResponse;
import com.possaas.modules.auth.dto.LoginRequest;
import com.possaas.modules.auth.dto.MeResponse;
import com.possaas.modules.auth.dto.RefreshRequest;
import com.possaas.modules.auth.entity.AuthRefreshToken;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthenticationService {
    private final UserRepository userRepository;
    private final RestaurantRepository restaurantRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final RefreshTokenService refreshTokenService;
    private final AuthTokenService authTokenService;
    private final AuditService auditService;

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse login(LoginRequest request, String ipAddress) {
        String normalizedEmail = request.email().trim().toLowerCase();
        User user = userRepository.findByEmailAndDeletedAtIsNull(normalizedEmail)
            .orElseThrow(() -> {
                recordLoginFailure(null, ipAddress);
                return invalidCredentials();
            });

        if (!user.isActive() || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            recordLoginFailure(user, ipAddress);
            throw invalidCredentials();
        }

        Restaurant restaurant = findActiveRestaurantForLogin(user, ipAddress);
        Role role = roleRepository.findById(user.getRoleId())
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "User role not found"));

        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
        Set<String> permissions = permissionService.getEffectivePermissionCodes(user);
        AuthResponse response = authTokenService.issueTokenPair(
            user,
            role,
            restaurant,
            permissions,
            request.deviceInfo(),
            ipAddress
        );
        auditService.record(
            user.getRestaurantId(),
            user.getId(),
            "USER_LOGGED_IN",
            "users",
            user.getId(),
            null,
            null,
            ipAddress
        );
        return response;
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthResponse refresh(RefreshRequest request, String ipAddress) {
        AuthRefreshToken oldToken = refreshTokenService.requireUsableForRotation(
            request.refreshToken(),
            ipAddress
        );
        User user = userRepository.findByIdAndDeletedAtIsNull(oldToken.getUserId())
            .orElseThrow(AuthenticationService::invalidRefreshToken);
        if (!user.isActive()) {
            throw invalidRefreshToken();
        }

        Role role = roleRepository.findById(user.getRoleId())
            .orElseThrow(AuthenticationService::invalidRefreshToken);
        Restaurant restaurant = findActiveRestaurantForRefresh(user);
        Set<String> permissions = permissionService.getEffectivePermissionCodes(user);

        refreshTokenService.revoke(oldToken);
        AuthResponse response = authTokenService.issueTokenPair(
            user,
            role,
            restaurant,
            permissions,
            oldToken.getDeviceInfo(),
            ipAddress
        );
        auditService.record(
            user.getRestaurantId(),
            user.getId(),
            "REFRESH_TOKEN_ROTATED",
            "auth_refresh_tokens",
            oldToken.getId(),
            null,
            null,
            ipAddress
        );
        return response;
    }

    @Transactional
    public void logout(UUID userId, RefreshRequest request, String ipAddress) {
        refreshTokenService.revokeOwnedToken(userId, request.refreshToken());
        User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
        auditService.record(
            user == null ? null : user.getRestaurantId(),
            user == null ? null : userId,
            "USER_LOGGED_OUT",
            "users",
            userId,
            null,
            null,
            ipAddress
        );
    }

    @Transactional(readOnly = true)
    public MeResponse me(UUID userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
            .orElseThrow(() -> new ResourceNotFoundException("USER_NOT_FOUND", "User not found"));
        Role role = roleRepository.findById(user.getRoleId())
            .orElseThrow(() -> new ResourceNotFoundException("ROLE_NOT_FOUND", "User role not found"));
        Restaurant restaurant = user.getRestaurantId() == null
            ? null
            : restaurantRepository.findByIdAndDeletedAtIsNull(user.getRestaurantId()).orElse(null);

        return new MeResponse(
            user.getId(),
            user.getRestaurantId(),
            restaurant == null ? null : restaurant.getCode(),
            restaurant == null ? null : restaurant.getName(),
            user.getName(),
            user.getEmail(),
            user.getPhone(),
            role.getCode(),
            permissionService.getEffectivePermissionCodes(user),
            user.getLastLoginAt()
        );
    }

    private Restaurant findActiveRestaurantForLogin(User user, String ipAddress) {
        if (user.getRestaurantId() == null) {
            return null;
        }
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(user.getRestaurantId())
            .orElseThrow(AuthenticationService::invalidCredentials);
        if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
            recordLoginFailure(user, ipAddress);
            throw new BusinessException(
                HttpStatus.UNAUTHORIZED,
                "RESTAURANT_INACTIVE",
                "Restaurant is not active"
            );
        }
        return restaurant;
    }

    private Restaurant findActiveRestaurantForRefresh(User user) {
        if (user.getRestaurantId() == null) {
            return null;
        }
        Restaurant restaurant = restaurantRepository.findByIdAndDeletedAtIsNull(user.getRestaurantId())
            .orElseThrow(AuthenticationService::invalidRefreshToken);
        if (restaurant.getStatus() != RestaurantStatus.ACTIVE) {
            throw invalidRefreshToken();
        }
        return restaurant;
    }

    private void recordLoginFailure(User user, String ipAddress) {
        auditService.record(
            user == null ? null : user.getRestaurantId(),
            user == null ? null : user.getId(),
            "USER_LOGIN_FAILED",
            "users",
            user == null ? null : user.getId(),
            null,
            null,
            ipAddress
        );
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_CREDENTIALS",
            "Invalid email or password"
        );
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(
            HttpStatus.UNAUTHORIZED,
            "INVALID_REFRESH_TOKEN",
            "Invalid refresh token"
        );
    }
}
