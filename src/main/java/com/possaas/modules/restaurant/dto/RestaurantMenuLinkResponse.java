package com.possaas.modules.restaurant.dto;

public record RestaurantMenuLinkResponse(String menuToken, String menuPath) {
    public static RestaurantMenuLinkResponse fromToken(String token) {
        return new RestaurantMenuLinkResponse(token, "/menu/" + token);
    }

    @Override public String toString() {
        return "RestaurantMenuLinkResponse[menuToken=[REDACTED], menuPath=[REDACTED]]";
    }
}
