package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.modules.inventory.dto.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class StockAuditOpenApiTest {
    @Test void routesSchemasSecurityAndReferencesMatchControllerAndRecords()throws Exception {
        JsonNode root;try(var input=Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))){root=new ObjectMapper().valueToTree(new Yaml().load(input));}
        var base="/api/v1/inventory/audits";int count=0;
        for(String suffix:List.of("","/{auditId}","/{auditId}/items","/{auditId}/start","/{auditId}/confirm","/{auditId}/cancel")) {
            var path=root.path("paths").path(base+suffix);assertThat(path.isMissingNode()).isFalse();
            var fields=path.fields();while(fields.hasNext()) {
                var method=fields.next();var operation=method.getValue();count++;
                assertThat(operation.path("security").get(0).has("bearerAuth")).isTrue();
                assertThat(operation.path("description").asText()).contains("INVENTORY_VIEW","INVENTORY_MANAGEMENT","STOCK_AUDIT");
                if(!method.getKey().equals("get"))assertThat(operation.path("description").asText()).contains(suffix.endsWith("/confirm")?"STOCK_AUDIT_CONFIRM":"STOCK_AUDIT_MANAGE");
                for(String code:List.of("400","401","403","404","409"))assertThat(operation.path("responses").has(code)).as(base+suffix+code).isTrue();
            }
        }
        assertThat(count).isEqualTo(9);
        fields(root,"StockAuditDetail",StockAuditDetailResponse.class);fields(root,"StockAuditSummary",StockAuditSummaryResponse.class);fields(root,"StockAuditItem",StockAuditItemResponse.class);
        assertThat(root.at("/components/schemas/StockAuditCounts/properties/items/maxItems").asInt()).isEqualTo(100);
        assertThat(root.at("/components/schemas/StockAuditCountLine/properties/countedQuantity/minimum").asInt()).isZero();
        assertThat(root.at("/components/schemas/StockAuditCountLine/required").toString()).contains("auditItemId","countedQuantity");
        assertThat(root.at("/components/schemas/StockAuditItem/properties/countedQuantity/nullable").asBoolean()).isTrue();
        assertThat(root.at("/components/schemas/StockDocumentSummary/properties/stockAuditId/nullable").asBoolean()).isTrue();
        references(root,root);
    }
    void fields(JsonNode root,String schema,Class<?> type){Set<String> actual=new HashSet<>(),expected=new HashSet<>();root.at("/components/schemas/"+schema+"/properties").fieldNames().forEachRemaining(actual::add);for(var field:type.getRecordComponents())expected.add(field.getName());assertThat(actual).isEqualTo(expected);}
    void references(JsonNode node,JsonNode root){if(node.has("$ref")){String ref=node.path("$ref").asText();assertThat(ref).startsWith("#/");assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();}if(node.isContainerNode())node.forEach(child->references(child,root));}
}
