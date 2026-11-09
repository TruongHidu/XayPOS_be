package com.possaas.modules.table.dto;

import com.possaas.config.PublicMenuProperties;
import com.possaas.modules.table.entity.RestaurantTable;
import java.util.UUID;

public record TableQrResponse(UUID tableId, String qrToken, String qrPath, String menuUrl, long version) {
    public static String path(String qrToken) {
        return "/menu/qr/" + qrToken;
    }

    public static TableQrResponse from(RestaurantTable table, PublicMenuProperties menu) {
        String path = path(table.getQrToken());
        return new TableQrResponse(table.getId(), table.getQrToken(), path, menu.absolute(path), table.getVersion());
    }

    @Override
    public String toString() {
        return "TableQrResponse[tableId=" + tableId + ", version=" + version
                + ", qrToken=[REDACTED], qrPath=[REDACTED], menuUrl=[REDACTED]]";
    }
}
