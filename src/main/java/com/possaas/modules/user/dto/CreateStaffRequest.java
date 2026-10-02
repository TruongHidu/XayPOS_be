package com.possaas.modules.user.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record CreateStaffRequest(@NotBlank @Size(max=150) String name,
    @NotBlank @Email @Size(max=254) String email, @Size(max=30) String phone,
    @NotBlank @Size(max=50) String roleCode, @NotBlank @Size(min=8, max=100) String initialPassword) {
    public CreateStaffRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        phone = phone == null || phone.isBlank() ? null : phone.trim();
        roleCode = roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }
    @Override public String toString() { return "CreateStaffRequest[credentials omitted]"; }
}
