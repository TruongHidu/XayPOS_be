package com.possaas.modules.user.dto;

import jakarta.validation.constraints.*;
import java.util.Locale;

public record UpdateStaffRequest(@Size(min=1,max=150) String name,
    @Email @Size(min=1,max=254) String email, @Size(max=30) String phone,
    @Size(min=1,max=50) String roleCode) {
    public UpdateStaffRequest {
        name = name == null ? null : name.trim();
        email = email == null ? null : email.trim().toLowerCase(Locale.ROOT);
        phone = phone == null ? null : phone.trim();
        roleCode = roleCode == null ? null : roleCode.trim().toUpperCase(Locale.ROOT);
    }
}
