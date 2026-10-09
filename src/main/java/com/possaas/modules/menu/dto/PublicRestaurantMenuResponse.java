package com.possaas.modules.menu.dto;

import java.util.List;
import java.util.UUID;

public record PublicRestaurantMenuResponse(Restaurant restaurant, List<Group> groups) {
    public record Restaurant(String name, String currencyCode) {}
    public record Group(UUID id, String name, int displayOrder) {}
}
