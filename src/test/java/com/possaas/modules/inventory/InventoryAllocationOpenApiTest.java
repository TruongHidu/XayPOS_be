package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class InventoryAllocationOpenApiTest {
    JsonNode document() throws Exception {try(var in=Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))){return new ObjectMapper().valueToTree(new Yaml().load(in));}}
    @Test void allocationIsPrivateReadOnlyAndDocumentsEveryPermissionFeatureAndStatus() throws Exception {
        var root=document();var path=root.at("/paths/~1api~1v1~1inventory~1orders~1{orderId}~1items~1{orderItemId}~1allocation");
        assertThat(path.size()).isEqualTo(1);var get=path.path("get");assertThat(get.path("operationId").asText()).isEqualTo("getOrderItemInventoryAllocation");
        assertThat(get.path("description").asText()).contains("ORDER_VIEW","RECIPE_VIEW","INVENTORY_VIEW","ORDER_MANAGEMENT","RECIPE_MANAGEMENT","INVENTORY_MANAGEMENT","NOT_SUBMITTED","SKIPPED");
        assertThat(get.path("security").get(0).has("bearerAuth")).isTrue();assertThat(get.has("requestBody")).isFalse();
        assertThat(get.path("parameters").size()).isEqualTo(2);assertThat(get.at("/responses/200/headers/Cache-Control/schema/example").asText()).isEqualTo("no-store");
        for(String code:List.of("200","400","401","403","404","409"))assertThat(get.path("responses").has(code)).as(code).isTrue();
        var schema=root.at("/components/schemas/OrderItemInventoryAllocation/properties");assertThat(schema.at("/status/enum").toString()).contains("RESERVED","CONSUMED","RELEASED","SKIPPED","NOT_SUBMITTED");
        assertThat(schema.at("/skipReason/enum").toString()).contains("INVENTORY_FEATURE_DISABLED","SNAPSHOT_NO_RECIPE","SNAPSHOT_FEATURE_DISABLED","NO_TRACKED_INGREDIENTS","LEGACY_UNAVAILABLE");
        assertThat(schema.has("unitCost")).isFalse();assertThat(root.at("/components/schemas/InventoryReservation/properties").has("unitCost")).isFalse();
        references(root,root);
    }
    @Test void balanceQuantitiesAreReadOnlyAndLedgerAllowsUnvaluedOrderSourcesWithoutDocuments() throws Exception {
        var root=document();var balance=root.at("/components/schemas/InventoryBalance/properties");
        for(String field:List.of("quantity","reservedQuantity","availableQuantity"))assertThat(balance.path(field).path("readOnly").asBoolean()).as(field).isTrue();
        assertThat(root.at("/components/schemas/InventoryThresholdUpdate/properties").has("reservedQuantity")).isFalse();
        assertThat(root.at("/components/schemas/InventoryTransactionType/enum").toString()).contains("RECIPE_USE","RECEIPT","ISSUE");
        assertThat(root.at("/components/schemas/InventoryTransactionSourceType/enum").toString()).contains("STOCK_DOCUMENT","ORDER_ITEM");
        var ledger=root.at("/components/schemas/InventoryTransaction/properties");
        for(String field:List.of("unitCost","stockDocumentId","documentCode","documentType"))assertThat(ledger.path(field).path("nullable").asBoolean()).as(field).isTrue();
        for(String field:List.of("sourceType","orderId","orderItemId","reservationId","recipeUsageId"))assertThat(ledger.has(field)).as(field).isTrue();
        var parameters=root.at("/paths/~1api~1v1~1inventory~1transactions/get/parameters");var names=new HashSet<String>();parameters.forEach(p->names.add(p.path("name").asText()));
        assertThat(names).contains("sourceType","orderId","orderItemId","warehouseId","ingredientId","page","size","direction");
    }
    void references(JsonNode node,JsonNode root){if(node.has("$ref")){String ref=node.path("$ref").asText();assertThat(ref).startsWith("#/");assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();}if(node.isContainerNode())node.forEach(child->references(child,root));}
}
