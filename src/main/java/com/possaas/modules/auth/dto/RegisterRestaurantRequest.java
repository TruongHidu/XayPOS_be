package com.possaas.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRestaurantRequest(
    @NotBlank @Size(min = 2, max = 50) String restaurantCode,
    @NotBlank @Size(max = 150) String restaurantName,
    @Size(max = 200) String legalName,
    @Size(max = 30) String phone,
    @Size(max = 500) String address,
    @NotBlank @Size(max = 50) String timezone,
    @NotBlank @Size(min = 3, max = 3) String currencyCode,
    @NotBlank @Size(max = 150) String ownerName,
    @NotBlank @Email @Size(max = 254) String ownerEmail,
    @Size(max = 30) String ownerPhone,
    @NotBlank @Size(min = 8, max = 100) String password
) {}
