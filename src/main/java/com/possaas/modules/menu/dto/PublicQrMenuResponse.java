package com.possaas.modules.menu.dto;

import java.util.List;
import java.util.UUID;

public record PublicQrMenuResponse(Restaurant restaurant, Table table, List<Group> groups) {
    public record Restaurant(String name, String currencyCode) {}
    public record Table(String code, String name) {}
    public record Group(UUID id, String name, int displayOrder) {}
}
