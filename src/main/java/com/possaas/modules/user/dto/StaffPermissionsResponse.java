package com.possaas.modules.user.dto;
import java.util.List;
import java.util.UUID;
public record StaffPermissionsResponse(UUID staffId, String roleCode, List<String> rolePermissions,
    List<String> grants, List<String> denies, List<String> effectivePermissions) {}
