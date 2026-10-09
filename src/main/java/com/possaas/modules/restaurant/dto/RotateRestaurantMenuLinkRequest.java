package com.possaas.modules.restaurant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RotateRestaurantMenuLinkRequest(@NotBlank @Size(max = 128) String expectedToken) {
    @Override public String toString() { return "RotateRestaurantMenuLinkRequest[expectedToken=[REDACTED]]"; }
}
