package com.possaas.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank @Email @Size(max = 254) String email,
    @NotBlank String password,
    @Size(max = 255) String deviceInfo
) {}
