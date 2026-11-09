package com.possaas.modules.restaurant.dto;

import com.possaas.config.PublicMenuProperties;

public record RestaurantMenuLinkResponse(String menuToken, String menuPath, String menuUrl) {
    public static String path(String token) {
        return "/menu/" + token;
    }

    public static RestaurantMenuLinkResponse fromToken(String token, PublicMenuProperties menu) {
        String path = path(token);
        return new RestaurantMenuLinkResponse(token, path, menu.absolute(path));
    }

    @Override public String toString() {
        return "RestaurantMenuLinkResponse[menuToken=[REDACTED], menuPath=[REDACTED], menuUrl=[REDACTED]]";
    }
}
