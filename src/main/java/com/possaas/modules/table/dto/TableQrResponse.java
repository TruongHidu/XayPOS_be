package com.possaas.modules.table.dto;

import com.possaas.modules.table.entity.RestaurantTable;
import java.util.UUID;

public record TableQrResponse(UUID tableId, String qrToken, String qrPath, long version) {
    public static TableQrResponse from(RestaurantTable table) {
        return new TableQrResponse(table.getId(), table.getQrToken(), "/menu/qr/" + table.getQrToken(), table.getVersion());
    }

    @Override
    public String toString() {
        return "TableQrResponse[tableId=" + tableId + ", version=" + version + ", qrToken=[REDACTED], qrPath=[REDACTED]]";
    }
}
