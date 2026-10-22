package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.modules.inventory.dto.LowStockAlertResponse;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LowStockAlertOpenApiTest {
    @Test void routesPermissionsValidationDtosAndReferencesMatchImplementation() throws Exception {
        JsonNode root;
        try(var stream=Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))){root=new ObjectMapper().valueToTree(new Yaml().load(stream));}
        var paths=root.path("paths");
        for(String route:List.of("/api/v1/inventory/alerts","/api/v1/inventory/alerts/{alertId}","/api/v1/inventory/alerts/{alertId}/acknowledge")) {
            var path=paths.path(route);assertThat(path.size()).isEqualTo(1);
            var operation=path.elements().next();assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
            assertThat(operation.path("description").asText()).contains("INVENTORY_VIEW","INVENTORY_MANAGEMENT","LOW_STOCK_ALERT");
            for(String code:List.of("200","400","401","403"))assertThat(operation.path("responses").has(code)).as(route+code).isTrue();
        }
        var ack=paths.path("/api/v1/inventory/alerts/{alertId}/acknowledge").path("post");
        assertThat(ack.path("description").asText()).contains("INVENTORY_ALERT_ACKNOWLEDGE","idempotent");
        assertThat(ack.path("responses").has("409")).isTrue();
        var request=root.at("/components/schemas/LowStockAlertAcknowledge");
        assertThat(request.path("required").toString()).contains("expectedVersion");assertThat(request.at("/properties/expectedVersion/minimum").asInt()).isZero();
        Set<String> javaFields=new HashSet<>();for(var field:LowStockAlertResponse.class.getRecordComponents())javaFields.add(field.getName());
        Set<String> yamlFields=new HashSet<>();root.at("/components/schemas/LowStockAlert/properties").fieldNames().forEachRemaining(yamlFields::add);
        assertThat(yamlFields).isEqualTo(javaFields);
        for(String field:List.of("currentQuantity","reservedQuantity","availableQuantity","currentMinQuantity"))
            assertThat(root.at("/components/schemas/LowStockAlert/properties/"+field+"/nullable").asBoolean()).isTrue();
        references(root,root);
    }
    void references(JsonNode node,JsonNode root) {
        if(node.has("$ref")) {String ref=node.path("$ref").asText();assertThat(ref).startsWith("#/");assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();}
        if(node.isContainerNode())node.forEach(child->references(child,root));
    }
}
