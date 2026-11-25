package com.possaas.modules.menu.dto;

import java.util.List;
import java.util.UUID;

/** {@code orderingEnabled=false}: the menu is view-only, guests cannot place takeaway orders right now. */
public record PublicRestaurantMenuResponse(Restaurant restaurant, List<Group> groups, boolean orderingEnabled) {
    public record Restaurant(String name, String currencyCode) {}
    public record Group(UUID id, String name, int displayOrder) {}
}
