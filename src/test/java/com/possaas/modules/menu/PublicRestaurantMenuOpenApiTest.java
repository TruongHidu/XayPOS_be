package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PublicRestaurantMenuOpenApiTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Path, JsonNode> documents = new HashMap<>();

    @Test void allFiveOperationsHaveCorrectSecurityAndResponseContracts() throws Exception {
        var file = Path.of("docs/openapi/public-restaurant-menu-api.yaml"); var root = document(file);
        var operations = new HashSet<String>();
        root.path("paths").fields().forEachRemaining(path -> path.getValue().forEach(operation -> {
            assertThat(operations.add(operation.path("operationId").asText())).isTrue();
            assertThat(operation.path("responses").has("200")).isTrue();
            assertThat(operation.path("responses").has("500")).isTrue();
            if (path.getKey().startsWith("/api/v1/public/menu/restaurants/")) {
                assertThat(path.getKey()).startsWith("/api/v1/public/menu/restaurants/{menuToken}");
                assertThat(operation.path("security").isArray()).isTrue(); assertThat(operation.path("security").size()).isZero();
                for (String status : List.of("400", "403", "404")) assertThat(operation.path("responses").has(status)).isTrue();
            } else {
                assertThat(operation.path("security").get(0).has("BearerAuth")).isTrue();
                assertThat(operation.path("description").asText()).contains("RESTAURANT_PROFILE_UPDATE");
                assertThat(operation.path("responses").has("401")).isTrue();
            }
        }));
        assertThat(operations).hasSize(5); assertThat(root.path("paths").size()).isEqualTo(4);
        var fields = new HashSet<String>(); root.at("/components/schemas/PublicRestaurantMenu/properties").fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("restaurant", "groups");
        assertThat(root.at("/components/schemas/RotateMenuLink/required").toString()).contains("expectedToken").doesNotContain("expectedVersion");
    }

    @Test void localAndSharedQrDocumentReferencesResolve() throws Exception {
        var file = Path.of("docs/openapi/public-restaurant-menu-api.yaml");
        references(document(file), file, new HashSet<>());
    }

    private JsonNode document(Path path) throws Exception {
        path = path.normalize();
        if (!documents.containsKey(path)) try (var input = Files.newInputStream(path)) { documents.put(path, mapper.valueToTree(new Yaml().load(input))); }
        return documents.get(path);
    }
    private void references(JsonNode node, Path file, Set<String> visited) throws Exception {
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText(); String[] parts = ref.split("#", 2);
            assertThat(parts).hasSize(2);
            Path target = parts[0].isEmpty() ? file : file.getParent().resolve(parts[0]).normalize();
            assertThat(target).startsWith(Path.of("docs/openapi"));
            JsonNode resolved = document(target).at(parts[1]); assertThat(resolved.isMissingNode()).as(ref).isFalse();
            if (visited.add(target + "#" + parts[1])) references(resolved, target, visited);
        }
        if (node.isContainerNode()) for (var child : node) references(child, file, visited);
    }
}
