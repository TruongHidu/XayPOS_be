package com.possaas.modules.restaurant.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.dto.AuthResponse;
import com.possaas.modules.auth.dto.RegisterRestaurantRequest;
import com.possaas.modules.auth.service.AuthTokenService;
import com.possaas.modules.authorization.entity.Role;
import com.possaas.modules.authorization.repository.RoleRepository;
import com.possaas.modules.authorization.service.PermissionService;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.restaurant.entity.RestaurantStatus;
import com.possaas.modules.restaurant.repository.RestaurantRepository;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RestaurantRegistrationService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final RestaurantRepository restaurantRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final PermissionService permissionService;
    private final AuthTokenService authTokenService;
    private final AuditService auditService;

    @Transactional
    public AuthResponse register(RegisterRestaurantRequest request, String ipAddress) {
        RegistrationData registrationData = normalizeAndValidate(request);
        ensureUniqueRegistration(registrationData.restaurantCode(), registrationData.ownerEmail());

        Role ownerRole = roleRepository.findByCodeAndRestaurantIdIsNull("OWNER")
            .orElseThrow(() -> new ResourceNotFoundException(
                "OWNER_ROLE_NOT_FOUND",
                "System OWNER role is missing"
            ));

        Restaurant restaurant = createRestaurant(request, registrationData);
        User owner = createOwner(request, registrationData.ownerEmail(), restaurant, ownerRole);
        Set<String> permissions = permissionService.getEffectivePermissionCodes(owner);
        AuthResponse response = authTokenService.issueTokenPair(
            owner,
            ownerRole,
            restaurant,
            permissions,
            null,
            ipAddress
        );

        auditService.record(
            restaurant.getId(),
            owner.getId(),
            "RESTAURANT_REGISTERED",
            "restaurants",
            restaurant.getId(),
            null,
            Map.of("code", restaurant.getCode()),
            ipAddress
        );
        return response;
    }

    private RegistrationData normalizeAndValidate(RegisterRestaurantRequest request) {
        String timezone = request.timezone().trim();
        try {
            ZoneId.of(timezone);
        } catch (Exception exception) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TIMEZONE", "Invalid timezone");
        }
        return new RegistrationData(
            request.restaurantCode().trim().toUpperCase(),
            request.ownerEmail().trim().toLowerCase(),
            timezone,
            request.currencyCode().trim().toUpperCase()
        );
    }

    private void ensureUniqueRegistration(String restaurantCode, String ownerEmail) {
        if (restaurantRepository.existsByCode(restaurantCode)) {
            throw new ConflictException("RESTAURANT_CODE_EXISTS", "Restaurant code already exists");
        }
        if (userRepository.existsByEmail(ownerEmail)) {
            throw new ConflictException("EMAIL_EXISTS", "Email already exists");
        }
    }

    private Restaurant createRestaurant(RegisterRestaurantRequest request, RegistrationData data) {
        Restaurant restaurant = new Restaurant();
        restaurant.setCode(data.restaurantCode());
        restaurant.setName(request.restaurantName().trim());
        restaurant.setLegalName(request.legalName());
        restaurant.setPhone(request.phone());
        restaurant.setAddress(request.address());
        restaurant.setTimezone(data.timezone());
        restaurant.setCurrencyCode(data.currencyCode());
        restaurant.setPublicOrderToken(generatePublicOrderToken());
        restaurant.setStatus(RestaurantStatus.ACTIVE);
        return restaurantRepository.saveAndFlush(restaurant);
    }

    private User createOwner(
        RegisterRestaurantRequest request,
        String normalizedEmail,
        Restaurant restaurant,
        Role ownerRole
    ) {
        User owner = new User();
        owner.setRestaurantId(restaurant.getId());
        owner.setRoleId(ownerRole.getId());
        owner.setName(request.ownerName().trim());
        owner.setEmail(normalizedEmail);
        owner.setPhone(request.ownerPhone());
        owner.setPasswordHash(passwordEncoder.encode(request.password()));
        return userRepository.saveAndFlush(owner);
    }

    private static String generatePublicOrderToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record RegistrationData(
        String restaurantCode,
        String ownerEmail,
        String timezone,
        String currencyCode
    ) {}
}
