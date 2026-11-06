package com.possaas.modules.user.dto;
import jakarta.validation.constraints.*;
import java.util.Set;
public record ReplaceStaffPermissionsRequest(
    @NotNull @Size(max=200) Set<@NotBlank @Size(max=100) String> grants,
    @NotNull @Size(max=200) Set<@NotBlank @Size(max=100) String> denies) {}
