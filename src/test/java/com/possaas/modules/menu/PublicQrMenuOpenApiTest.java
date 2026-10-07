package com.possaas.modules.menu;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class PublicQrMenuOpenApiTest {
    @Test void documentsOnlyTwoAnonymousReadRoutesAndResolvesReferences() throws Exception {
        try (var input = Files.newInputStream(Path.of("docs/openapi/public-qr-menu-api.yaml"))) {
            JsonNode root = new ObjectMapper().valueToTree(new Yaml().load(input));
            assertThat(root.path("paths").size()).isEqualTo(2);
            root.path("paths").fields().forEachRemaining(path -> {
                assertThat(path.getKey()).startsWith("/api/v1/public/qr-menu/{qrToken}");
                assertThat(path.getValue().size()).isEqualTo(1);
                var get = path.getValue().path("get");
                assertThat(get.path("security").isArray()).isTrue();
                assertThat(get.path("security").size()).isZero();
                for (String status : List.of("200", "403", "404", "500")) assertThat(get.path("responses").has(status)).isTrue();
            });
            references(root, root);
            var fields = new HashSet<String>();
            root.at("/components/schemas/PublicMenuItem/properties").fieldNames().forEachRemaining(fields::add);
            assertThat(fields).containsExactlyInAnyOrder("id", "group", "name", "description", "imageUrl", "baseUnit", "salePrice", "availabilityStatus");
        }
    }
    private void references(JsonNode node, JsonNode root) {
        if (node.has("$ref")) {
            String ref = node.path("$ref").asText(); assertThat(ref).startsWith("#/");
            assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse();
        }
        if (node.isContainerNode()) node.forEach(child -> references(child, root));
    }
}
