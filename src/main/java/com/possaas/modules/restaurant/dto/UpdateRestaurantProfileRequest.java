package com.possaas.modules.restaurant.dto;

import jakarta.validation.constraints.Size;

public record UpdateRestaurantProfileRequest(
    @Size(max = 150) String name, @Size(max = 200) String legalName,
    @Size(max = 30) String phone, @Size(max = 500) String address,
    @Size(max = 50) String timezone, String currencyCode) {
    public UpdateRestaurantProfileRequest {
        name = trim(name);
        legalName = trim(legalName);
        phone = trim(phone);
        address = trim(address);
        timezone = trim(timezone);
        currencyCode = trim(currencyCode);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}
