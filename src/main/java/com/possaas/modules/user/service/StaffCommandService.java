package com.possaas.modules.user.service;

import com.possaas.common.exception.*;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.authorization.repository.UserPermissionRepository;
import com.possaas.modules.subscription.application.port.CurrentEntitlementQuery;
import com.possaas.modules.user.application.port.UserSessionRevoker;
import com.possaas.modules.user.dto.*;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.user.repository.UserRepository;
import java.util.*;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StaffCommandService {
    private final StaffAccessService access;
    private final StaffManagementPolicy policy;
    private final StaffCapacityPolicy capacity;
    private final CurrentEntitlementQuery entitlements;
    private final UserRepository users;
    private final UserPermissionRepository overrides;
    private final PasswordEncoder passwords;
    private final UserSessionRevoker sessions;
    private final AuditService audit;

    @Transactional
    public StaffResponse create(CreateStaffRequest request, String ip) {
        var actor = access.requireAccess(false, true);
        var role = access.assignableRole(actor, request.roleCode());
        capacity.requireCapacity(entitlements.getForRestaurant(actor.restaurantId()), users.countActiveStaff(actor.restaurantId()));
        if (users.existsByEmail(request.email())) throw new ConflictException("EMAIL_EXISTS", "Email already exists");
        User user = new User();
        user.setRestaurantId(actor.restaurantId()); user.setRoleId(role.getId()); user.setName(request.name());
        user.setEmail(request.email()); user.setPhone(request.phone()); user.setPasswordHash(passwords.encode(request.initialPassword()));
        user.setActive(true);
        users.saveAndFlush(user);
        Map<String,Object> data = new LinkedHashMap<>();
        data.put("name", user.getName()); data.put("email", user.getEmail()); data.put("phone", user.getPhone());
        data.put("roleCode", role.getCode()); data.put("active", true);
        audit.record(actor.restaurantId(), actor.userId(), "STAFF_CREATED", "users", user.getId(), null, data, ip);
        return StaffResponse.from(user, role);
    }

    @Transactional
    public StaffResponse update(UUID id, UpdateStaffRequest request, String ip) {
        var actor = access.requireAccess(false, true);
        User user = access.target(actor.restaurantId(), id, true);
        var oldRole = access.role(user);
        policy.requireTarget(actor, id, oldRole.getCode());
        if (request.name() == null && request.email() == null && request.phone() == null && request.roleCode() == null)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "EMPTY_UPDATE_REQUEST", "No fields provided");
        var role = request.roleCode() == null ? oldRole : access.assignableRole(actor, request.roleCode());
        if (request.email() != null && !request.email().equals(user.getEmail()) && users.existsByEmailAndIdNot(request.email(), id))
            throw new ConflictException("EMAIL_EXISTS", "Email already exists");
        Map<String,Object> before = new LinkedHashMap<>(), after = new LinkedHashMap<>();
        change("name", user.getName(), request.name(), user::setName, before, after);
        change("email", user.getEmail(), request.email(), user::setEmail, before, after);
        change("phone", user.getPhone(), request.phone(), user::setPhone, before, after);
        boolean changedRole = !oldRole.getId().equals(role.getId());
        if (changedRole) {
            user.setRoleId(role.getId());
            overrides.deleteAll(overrides.findByIdUserId(id));
            overrides.flush();
        }
        if (!after.isEmpty() || changedRole) {
            users.saveAndFlush(user);
            if (changedRole || after.containsKey("email")) sessions.revokeAllActiveForUser(id);
            if (!after.isEmpty()) audit.record(actor.restaurantId(), actor.userId(), "STAFF_UPDATED", "users", id, before, after, ip);
            if (changedRole) audit.record(actor.restaurantId(), actor.userId(), "STAFF_ROLE_CHANGED", "users", id,
                Map.of("roleCode", oldRole.getCode()), Map.of("roleCode", role.getCode(), "overridesCleared", true), ip);
        }
        return StaffResponse.from(user, role);
    }

    @Transactional
    public StaffResponse status(UUID id, UpdateStaffStatusRequest request, String ip) {
        var actor = access.requireAccess(false, true);
        User user = access.target(actor.restaurantId(), id, true);
        var role = access.role(user);
        policy.requireTarget(actor, id, role.getCode());
        if (!request.active() && (request.reason() == null || request.reason().isBlank()))
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "A reason is required to disable staff");
        if (user.isActive() == request.active()) return StaffResponse.from(user, role);
        if (request.active()) capacity.requireCapacity(entitlements.getForRestaurant(actor.restaurantId()), users.countActiveStaff(actor.restaurantId()));
        boolean before = user.isActive(); user.setActive(request.active()); users.saveAndFlush(user);
        if (!request.active()) sessions.revokeAllActiveForUser(id);
        Map<String,Object> after = new LinkedHashMap<>(); after.put("active", request.active());
        if (request.reason() != null && !request.reason().isBlank()) after.put("reason", request.reason().trim());
        audit.record(actor.restaurantId(), actor.userId(), request.active() ? "STAFF_ENABLED" : "STAFF_DISABLED",
            "users", id, Map.of("active", before), after, ip);
        return StaffResponse.from(user, role);
    }

    private static void change(String key, String old, String value, Consumer<String> setter, Map<String,Object> before, Map<String,Object> after) {
        if (value == null) return;
        String normalized = value.isBlank() ? null : value.trim();
        if (!Objects.equals(old, normalized)) { before.put(key, old); after.put(key, normalized); setter.accept(normalized); }
    }
}
