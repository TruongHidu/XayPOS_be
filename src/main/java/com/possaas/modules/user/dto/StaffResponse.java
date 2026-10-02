package com.possaas.modules.user.dto;
import com.possaas.modules.user.entity.User;
import com.possaas.modules.authorization.entity.Role;
import java.time.Instant;
import java.util.UUID;
public record StaffResponse(UUID id, String name, String email, String phone, boolean active,
    StaffRoleResponse role, Instant lastLoginAt, Instant createdAt, Instant updatedAt) {
    public static StaffResponse from(User user, Role role) {
        return new StaffResponse(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.isActive(),
            StaffRoleResponse.from(role), user.getLastLoginAt(), user.getCreatedAt(), user.getUpdatedAt());
    }
    public static StaffResponse from(AdminRestaurantUserResponse user) {
        return new StaffResponse(user.id(), user.name(), user.email(), user.phone(), user.active(),
            new StaffRoleResponse(user.role().id(), user.role().code(), user.role().name()),
            user.lastLoginAt(), user.createdAt(), user.updatedAt());
    }
}
