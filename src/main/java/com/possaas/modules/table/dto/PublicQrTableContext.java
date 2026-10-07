package com.possaas.modules.table.dto;

import java.util.UUID;

/** Internal cross-module context; never returned directly by a public controller. */
public record PublicQrTableContext(UUID restaurantId, UUID tableId, String restaurantName,
        String currencyCode, String tableCode, String tableName) {}
