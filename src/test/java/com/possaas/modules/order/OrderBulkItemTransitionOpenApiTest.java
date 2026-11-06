package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.yaml.snakeyaml.Yaml;

class OrderBulkItemTransitionOpenApiTest {
    private static final Path LIFECYCLE = Path.of("docs/openapi/kitchen-lifecycle-api.yaml")
        .toAbsolutePath().normalize();
    private static final Path ORDER = Path.of("docs/openapi/order-api.yaml")
        .toAbsolutePath().normalize();

    @ParameterizedTest
    @CsvSource({
        "mark-ready,markAllOrderItemsReady,KITCHEN_MARK_READY,KITCHEN_DISPLAY",
        "mark-served,markAllOrderItemsServed,ORDER_MARK_SERVED,ORDER_MANAGEMENT"
    })
    void bulkOperationsDocumentSecurityVersionedRequestAndExistingOrderResponse(
            String action, String operationId, String permission, String feature) throws Exception {
        var documents = documents();
        var root = documents.get(LIFECYCLE);
        var operation = root.path("paths").path("/api/v1/orders/{orderId}/" + action).path("post");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("operationId").asText()).isEqualTo(operationId);
        assertThat(operation.path("x-permission").asText()).isEqualTo(permission);
        assertThat(operation.path("x-feature").asText()).isEqualTo(feature);

        var security = operation.has("security") ? operation.path("security") : root.path("security");
        assertThat(security.isArray()).isTrue();
        assertThat(security.size()).isEqualTo(1);
        assertThat(security.get(0).has("BearerAuth")).isTrue();
        assertThat(security.get(0).path("BearerAuth").isArray()).isTrue();
        assertThat(root.at("/components/securitySchemes/BearerAuth/type").asText()).isEqualTo("http");
        assertThat(root.at("/components/securitySchemes/BearerAuth/scheme").asText()).isEqualTo("bearer");

        assertThat(operation.path("parameters").size()).isEqualTo(1);
        var parameter = resolve(operation.path("parameters").get(0), LIFECYCLE, documents);
        assertThat(parameter.path("name").asText()).isEqualTo("orderId");
        assertThat(parameter.path("in").asText()).isEqualTo("path");
        assertThat(parameter.path("required").asBoolean()).isTrue();
        assertThat(parameter.at("/schema/format").asText()).isEqualTo("uuid");

        var request = resolve(operation.path("requestBody"), LIFECYCLE, documents);
        assertThat(request.path("required").asBoolean()).isTrue();
        var schema = request.at("/content/application~1json/schema");
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("expectedOrderVersion");
        assertThat(schema.has("additionalProperties")).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties")).hasSize(1);
        var version = schema.at("/properties/expectedOrderVersion");
        assertThat(version.path("type").asText()).isEqualTo("integer");
        assertThat(version.path("format").asText()).isEqualTo("int64");
        assertThat(version.has("minimum")).isTrue();
        assertThat(version.path("minimum").asLong()).isZero();

        var responses = operation.path("responses");
        for (var status : List.of("200", "400", "401", "403", "404", "409", "500"))
            assertThat(responses.has(status)).as("%s %s", action, status).isTrue();
        assertThat(responses.path("200").path("$ref").asText())
            .isEqualTo("#/components/responses/OrderUpdated");
        var success = resolve(responses.path("200"), LIFECYCLE, documents);
        assertThat(success.at("/headers/Cache-Control/schema/example").asText()).isEqualTo("no-store");
        assertThat(success.at("/content/application~1json/schema/$ref").asText())
            .isEqualTo("#/components/schemas/Order");
        assertThat(root.at("/components/schemas/Order/$ref").asText())
            .isEqualTo("order-api.yaml#/components/schemas/Order");
        var responseSchema = resolve(success.at("/content/application~1json/schema"), LIFECYCLE, documents);
        assertThat(responseSchema).isEqualTo(documents.get(ORDER).at("/components/schemas/Order"));
        for (var property : List.of("id", "status", "paymentStatus", "version", "items", "totalAmount"))
            assertThat(responseSchema.path("properties").has(property)).as(property).isTrue();

        for (var status : List.of("400", "401", "403", "404", "409", "500")) {
            var error = resolve(responses.path(status), LIFECYCLE, documents);
            var errorSchema = resolve(error.at("/content/application~1json/schema"), LIFECYCLE, documents);
            for (var property : List.of("success", "code", "message", "fieldErrors", "timestamp"))
                assertThat(errorSchema.path("properties").has(property)).as("%s %s", status, property).isTrue();
        }
        var conflict = resolve(responses.path("409"), LIFECYCLE, documents);
        assertThat(conflict.path("description").asText()).contains(
            "INVALID_ORDER_ITEM_TRANSITION", "EMPTY_ORDER", "CONCURRENT_ORDER_UPDATE");
    }

    @Test
    void directBulkServingIsDocumentedWithoutRelaxingSingleItemOrReadyTransitions() throws Exception {
        var root = documents().get(LIFECYCLE);
        var paths = root.path("paths");
        var bulk = paths.path("/api/v1/orders/{orderId}/mark-served").path("post");
        assertThat(bulk.path("description").asText()).contains(
            "PENDING, COOKING and READY become SERVED directly",
            "actual PENDING/COOKING/READY -> SERVED",
            "CONCURRENT_ORDER_UPDATE");
        assertThat(bulk.path("x-feature").asText()).isEqualTo("ORDER_MANAGEMENT");
        assertThat(paths.path("/api/v1/orders/{orderId}/items/{orderItemId}/mark-served")
            .path("post").path("description").asText()).contains("READY -> SERVED only");
        assertThat(paths.path("/api/v1/orders/{orderId}/mark-ready")
            .path("post").path("description").asText()).contains("Any PENDING item rejects");
    }

    @Test
    void everyLocalAndExternalReferenceResolvesWithinItsOwningDocument() throws Exception {
        var documents = documents();
        var visited = new HashSet<Reference>();
        for (var entry : documents.entrySet())
            references(entry.getValue(), entry.getKey(), documents, visited);
        assertThat(visited).contains(new Reference(ORDER, "/components/schemas/Order"));
    }

    private Map<Path, JsonNode> documents() throws Exception {
        return Map.of(LIFECYCLE, read(LIFECYCLE), ORDER, read(ORDER));
    }

    private JsonNode read(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            return new ObjectMapper().valueToTree(new Yaml().load(input));
        }
    }

    private JsonNode resolve(JsonNode node, Path owner, Map<Path, JsonNode> documents) {
        Set<Reference> aliases = new HashSet<>();
        while (node.has("$ref")) {
            var reference = reference(node.path("$ref").asText(), owner, documents);
            assertThat(aliases.add(reference)).as("Cyclic reference alias %s", reference).isTrue();
            owner = reference.document();
            node = documents.get(owner).at(reference.pointer());
        }
        assertThat(node.isMissingNode()).isFalse();
        return node;
    }

    private void references(JsonNode node, Path owner, Map<Path, JsonNode> documents,
            Set<Reference> visited) {
        if (node.has("$ref")) {
            var reference = reference(node.path("$ref").asText(), owner, documents);
            if (visited.add(reference))
                references(documents.get(reference.document()).at(reference.pointer()),
                    reference.document(), documents, visited);
        }
        if (node.isContainerNode())
            node.forEach(child -> references(child, owner, documents, visited));
    }

    private Reference reference(String value, Path owner, Map<Path, JsonNode> documents) {
        int fragment = value.indexOf('#');
        assertThat(fragment).as("Reference must contain a JSON pointer: %s", value).isGreaterThanOrEqualTo(0);
        String file = value.substring(0, fragment);
        var target = file.isEmpty() ? owner : owner.getParent().resolve(file).normalize();
        assertThat(documents).as(value).containsKey(target);
        String pointer = value.substring(fragment + 1);
        assertThat(pointer).as(value).startsWith("/");
        assertThat(documents.get(target).at(pointer).isMissingNode()).as("%s in %s", value, owner).isFalse();
        return new Reference(target, pointer);
    }

    private record Reference(Path document, String pointer) {}
}
