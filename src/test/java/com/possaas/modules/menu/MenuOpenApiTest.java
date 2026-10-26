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
            Set<String> menuOperations = new HashSet<>();
            Set<String> ingredientOperations = new HashSet<>();
            Set<String> recipeOperations = new HashSet<>();
            root.path("paths").fields().forEachRemaining(path -> {
                if (path.getKey().startsWith("/api/v1/menu/")) {
                    path.getValue().forEach(operation -> {
                        String description = operation.path("description").asText();
                        if (path.getKey().contains("/recipes")) {
                            assertThat(recipeOperations.add(operation.path("operationId").asText())).isTrue();
                            assertThat(description).contains("RECIPE_");
                        } else if (path.getKey().startsWith("/api/v1/menu/ingredients")) {
                            assertThat(ingredientOperations.add(operation.path("operationId").asText())).isTrue();
                            assertThat(description).contains("INGREDIENT_");
                        } else {
                            assertThat(menuOperations.add(operation.path("operationId").asText())).isTrue();
                            assertThat(description).contains("MENU_MANAGEMENT", "MENU_");
                        }
                    });
                }
            });
            assertThat(menuOperations).hasSize(14);
            assertThat(ingredientOperations).hasSize(6);
            assertThat(recipeOperations).hasSize(7).contains("estimateRecipeCost");
            verifyReferences(root, root);
            var assignment = root.at("/components/schemas/MenuGroupAssignment");
            assertThat(assignment.path("required").toString()).contains("groupId", "expectedVersion");
            assertThat(assignment.at("/properties/groupId/nullable").asBoolean()).isTrue();
            verifyRecipeCostEstimateContract(root);
            var availability = root.path("paths").path("/api/v1/menu/items/{itemId}/availability").path("patch");
            assertThat(availability.path("description").asText())
                .startsWith("Requires MENU_AVAILABILITY_UPDATE.")
                .contains("MENU_MANAGEMENT", "MENU_UPDATE alone is insufficient");
            assertThat(root.path("paths").path("/api/v1/menu/items/{itemId}").path("patch").path("description").asText())
                .startsWith("Requires MENU_UPDATE.");
            assertThat(root.path("paths").path("/api/v1/menu/items/{itemId}/status").path("patch").path("description").asText())
                .startsWith("Requires MENU_UPDATE.");
        }
    }

    private void verifyRecipeCostEstimateContract(JsonNode root) {
        var estimate = root.path("paths")
            .path("/api/v1/menu/items/{menuItemId}/recipes/{recipeId}/cost-estimate").path("get");
        assertThat(estimate.path("description").asText()).contains("RECIPE_VIEW", "RECIPE_MANAGEMENT");
        assertThat(estimate.has("requestBody")).isFalse();
        assertThat(estimate.path("parameters").size()).isEqualTo(2);
        assertThat(estimate.at("/responses/200/headers/Cache-Control/schema/enum/0").asText())
            .isEqualTo("no-store");
        assertThat(estimate.at("/responses/409/description").asText()).contains(
            "INGREDIENT_COST_UNIT_MISMATCH", "INGREDIENT_COST_UNAVAILABLE",
            "INVALID_INGREDIENT_REFERENCE_COST", "INVALID_RECIPE_COST_DATA"
        );
        var example = estimate.at("/responses/200/content/application~1json/example");
        assertThat(example.path("costSource").asText()).isEqualTo("INGREDIENT_COST_PRICE");
        assertThat(example.path("totalBatchCost").decimalValue()).isEqualByComparingTo("369000.00");
        assertThat(example.path("costPerYieldUnit").decimalValue()).isEqualByComparingTo("36900.00");
        var properties = root.at("/components/schemas/RecipeCostEstimate/properties");
        assertThat(properties.has("recipeVersion")).isTrue();
        assertThat(properties.has("version")).isFalse();
        assertThat(properties.has("warnings")).isTrue();
        assertThat(properties.has("estimatedAt")).isTrue();
        var grossQuantity = root.at("/components/schemas/RecipeCostEstimateIngredient/properties/grossQuantity");
        assertThat(grossQuantity.has("multipleOf")).isFalse();
        assertThat(grossQuantity.path("description").asText()).contains("0.0010001");
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
