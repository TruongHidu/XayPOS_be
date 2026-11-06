package com.possaas.modules.menu;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.*;
import org.yaml.snakeyaml.constructor.SafeConstructor;
class DirectStockContractTest {
    @Test void expandedContractHasNoDuplicateKeysAndDescribesDirectStock() throws Exception {
        var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        JsonNode root;
        try(var input=Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))) {
            root=new ObjectMapper().valueToTree(new Yaml(new SafeConstructor(options)).load(input));
        }
        for(String schema:java.util.List.of("MenuCreateItem","MenuUpdateItem","MenuItem"))
            assertThat(root.at("/components/schemas/"+schema+"/properties/trackInventory").isMissingNode()).isFalse();
        for(String schema:java.util.List.of("StockDocumentItem","InventoryBalance","InventoryTransaction","InventoryReservation","StockAuditItem","LowStockAlert")) {
            var fields=root.at("/components/schemas/"+schema+"/properties");
            assertThat(fields.has("itemId")).as(schema).isTrue();assertThat(fields.has("itemType")).as(schema).isTrue();
            assertThat(fields.path("ingredientId").path("nullable").asBoolean()).as(schema).isTrue();
        }
        assertThat(root.at("/paths/~1api~1v1~1inventory~1stock-items/get/security/0/bearerAuth").isMissingNode()).isFalse();
        assertThat(root.at("/components/schemas/InventoryTransactionType/enum").toString()).contains("DIRECT_USE");
    }
}
