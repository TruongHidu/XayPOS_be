package com.possaas.modules.user.dto;
import jakarta.validation.constraints.*;
public record UpdateStaffStatusRequest(@NotNull Boolean active, @Size(max=500) String reason) {}
