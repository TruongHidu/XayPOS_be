package com.possaas.modules.table;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class TableOpenApiTest {
    @Test
    void documentCoversEveryTableOperationAndLocalReference() throws Exception {
        try (var input = Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))) {
            JsonNode root = new ObjectMapper().valueToTree(new Yaml().load(input));
            Set<String> operations = new HashSet<>();
            root.path("paths").fields().forEachRemaining(path -> {
                if (path.getKey().startsWith("/api/v1/table")) {
                    assertThat(path.getKey()).doesNotEndWith("/close");
                    path.getValue().forEach(operation -> {
                        assertThat(operations.add(operation.path("operationId").asText())).isTrue();
                        assertThat(operation.path("description").asText()).contains("TABLE_MANAGEMENT", "TABLE_");
                        assertThat(operation.path("responses").has("403")).isTrue();
                    });
                }
            });
            assertThat(operations).hasSize(21);
            references(root, root);
            var assignment = root.at("/components/schemas/TableAreaAssignment");
            assertThat(assignment.path("required").toString()).contains("areaId", "expectedVersion");
            assertThat(assignment.at("/properties/areaId/nullable").asBoolean()).isTrue();
            assertThat(root.at("/components/schemas/RestaurantTable/properties").has("qrToken")).isFalse();
        }
    }

    private void references(JsonNode node, JsonNode root) {
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertThat(ref).startsWith("#/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        if (node.isContainerNode()) node.forEach(child -> references(child, root));
    }
}
