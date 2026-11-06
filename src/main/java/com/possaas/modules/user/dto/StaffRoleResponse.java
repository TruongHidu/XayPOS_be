package com.possaas.modules.user.dto;
import com.possaas.modules.authorization.entity.Role;
import java.util.UUID;
public record StaffRoleResponse(UUID id, String code, String name) {
    public static StaffRoleResponse from(Role role) { return new StaffRoleResponse(role.getId(), role.getCode(), role.getName()); }
}
