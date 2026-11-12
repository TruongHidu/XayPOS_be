package com.possaas.modules.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Super admin support actions on a restaurant's user accounts. */
public final class AdminUserSupportDtos {
    private AdminUserSupportDtos() {}

    public record UpdateUserStatusRequest(@NotNull Boolean active, @NotBlank @Size(min = 5, max = 500) String reason) {}

    public record TemporaryPasswordRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}

    /** Shown once; the backend keeps only the hash. */
    public record TemporaryPasswordResponse(String temporaryPassword, int revokedSessions) {}
}
