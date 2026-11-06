package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class InventoryOpenApiTest {
    @Test
    void tenantOpenApiDocumentsInventoryRoutesAndReadOnlyQuantity() throws Exception {
        String document;
        try (var stream = getClass().getResourceAsStream("/../docs/openapi/tenant-api.yaml")) {
            document = stream == null
                    ? java.nio.file.Files.readString(java.nio.file.Path.of("docs/openapi/tenant-api.yaml"))
                    : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertThat(document).contains(
                "\"/api/v1/inventory/warehouses\"",
                "\"/api/v1/inventory/warehouses/{warehouseId}/status\"",
                "\"/api/v1/inventory/warehouses/{warehouseId}/make-default\"",
                "\"/api/v1/inventory/warehouses/{warehouseId}/balances\"",
                "\"/api/v1/inventory/warehouses/{warehouseId}/balances/{ingredientId}/threshold\"",
                "\"/api/v1/inventory/low-stock\"",
                "INVENTORY_THRESHOLD_UPDATE",
                "readOnly: true",
                "LOW_STOCK_ALERT");
        assertThat(document).contains(
                "\"/api/v1/inventory/stock-documents\"",
                "\"/api/v1/inventory/stock-documents/{documentId}\"",
                "\"/api/v1/inventory/stock-documents/{documentId}/items\"",
                "\"/api/v1/inventory/stock-documents/{documentId}/items/{documentItemId}\"",
                "\"/api/v1/inventory/stock-documents/{documentId}/confirm\"",
                "\"/api/v1/inventory/stock-documents/{documentId}/cancel\"",
                "\"/api/v1/inventory/transactions\"",
                "Idempotency-Key",
                "INVENTORY_RECEIVE",
                "INVENTORY_ISSUE",
                "INVENTORY_ADJUST");
        assertThat(document).contains("MOVING_WEIGHTED_AVERAGE", "HALF_UP",
                "INGREDIENT_COST_RECALCULATED", "INVALID_INVENTORY_COST_DATA");
    }
}
