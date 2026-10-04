package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class MenuOpenApiTest {
    @Test
    void documentParsesAndAllMenuOperationsAndLocalReferencesExist() throws Exception {
        try (var input = Files.newInputStream(Path.of("docs/openapi/tenant-api.yaml"))) {
            Object document = new Yaml().load(input);
            JsonNode root = new ObjectMapper().valueToTree(document);
            assertThat(root.path("openapi").asText()).isEqualTo("3.0.3");
            Set<String> operations = new HashSet<>();
            root.path("paths").fields().forEachRemaining(path -> {
                if (path.getKey().startsWith("/api/v1/menu/")) {
                    path.getValue().forEach(operation -> {
                        assertThat(operations.add(operation.path("operationId").asText())).isTrue();
                        assertThat(operation.path("description").asText()).contains("MENU_MANAGEMENT", "MENU_");
                    });
                }
            });
            assertThat(operations).hasSize(14);
            verifyReferences(root, root);
            var assignment = root.at("/components/schemas/MenuGroupAssignment");
            assertThat(assignment.path("required").toString()).contains("groupId", "expectedVersion");
            assertThat(assignment.at("/properties/groupId/nullable").asBoolean()).isTrue();
        }
    }

    private void verifyReferences(JsonNode node, JsonNode root) {
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText();
            assertThat(ref).startsWith("#/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        if (node.isContainerNode())
            node.forEach(child -> verifyReferences(child, root));
    }
}
