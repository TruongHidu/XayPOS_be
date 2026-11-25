package com.possaas.modules.menu.dto;

import java.util.List;
import java.util.UUID;

/** {@code orderingEnabled=false}: the menu is view-only, guests cannot order from this table right now. */
public record PublicQrMenuResponse(Restaurant restaurant, Table table, List<Group> groups, boolean orderingEnabled) {
    public record Restaurant(String name, String currencyCode) {}
    public record Table(String code, String name) {}
    public record Group(UUID id, String name, int displayOrder) {}
}
