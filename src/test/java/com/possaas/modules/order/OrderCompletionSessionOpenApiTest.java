package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OrderCompletionSessionOpenApiTest {
    private static final Path LIFECYCLE = Path.of("docs/openapi/kitchen-lifecycle-api.yaml");
    private static final Path ORDER = Path.of("docs/openapi/order-api.yaml");

    @Test
    void completeKeepsItsExistingPathOrderVersionBodyAndCanonicalOrderResponse() throws Exception {
        var lifecycle = read(LIFECYCLE);
        var orderDocument = read(ORDER);
        var operation = complete(lifecycle);

        assertThat(operation.path("operationId").asText()).isEqualTo("completeOrder");
        assertThat(operation.path("parameters")).hasSize(1);
        var parameter = local(operation.path("parameters").get(0), lifecycle);
        assertThat(parameter.path("name").asText()).isEqualTo("orderId");
        assertThat(parameter.path("in").asText()).isEqualTo("path");
        assertThat(parameter.path("required").asBoolean()).isTrue();
        assertThat(parameter.at("/schema/format").asText()).isEqualTo("uuid");

        var request = local(operation.path("requestBody"), lifecycle);
        assertThat(request.path("required").asBoolean()).isTrue();
        versionRequest(local(request.at("/content/application~1json/schema"), lifecycle));
        assertThat(request.at("/content/application~1json/example/expectedVersion").asLong())
            .isEqualTo(9);
        assertThat(operation.path("description").asText()).contains(
            "expectedVersion is the ORDER version", "no sessionId/tableId/expectedSessionVersion");

        var response = local(operation.at("/responses/200"), lifecycle);
        assertThat(response.at("/headers/Cache-Control/schema/example").asText()).isEqualTo("no-store");
        assertThat(response.at("/content/application~1json/schema/$ref").asText())
            .isEqualTo("#/components/schemas/Order");
        assertThat(lifecycle.at("/components/schemas/Order/$ref").asText())
            .isEqualTo("order-api.yaml#/components/schemas/Order");
        var canonical = orderDocument.at("/components/schemas/Order");
        assertThat(canonical.isMissingNode()).isFalse();
        for (var property : List.of("id", "status", "paymentStatus", "version", "items",
                "totalAmount", "paidAmount", "completedAt", "tableSessionId"))
            assertThat(canonical.path("properties").has(property)).as(property).isTrue();
        assertThat(canonical.path("properties").has("session")).isFalse();
        assertThat(canonical.path("properties").has("sessionVersion")).isFalse();
        assertThat(orderDocument.at("/paths/~1api~1v1~1orders~1{orderId}~1complete").isMissingNode())
            .as("Completion remains in the lifecycle document without a second contract").isTrue();
    }

    @Test
    void accessRequirementsAreConditionalForDineInAndCheckedBeforeRetries() throws Exception {
        var lifecycle = read(LIFECYCLE);
        var operation = complete(lifecycle);
        var security = operation.has("security") ? operation.path("security") : lifecycle.path("security");
        assertThat(security).hasSize(1);
        assertThat(security.get(0).has("BearerAuth")).isTrue();
        assertThat(lifecycle.at("/components/securitySchemes/BearerAuth/type").asText()).isEqualTo("http");
        assertThat(lifecycle.at("/components/securitySchemes/BearerAuth/scheme").asText()).isEqualTo("bearer");
        assertThat(operation.path("x-permission").asText()).isEqualTo("ORDER_COMPLETE");
        assertThat(operation.path("x-feature").asText()).isEqualTo("ORDER_MANAGEMENT");
        assertThat(operation.at("/x-conditional-access/DINE_IN/permissions")).hasSize(1);
        assertThat(operation.at("/x-conditional-access/DINE_IN/permissions/0").asText())
            .isEqualTo("TABLE_CLOSE");
        assertThat(operation.at("/x-conditional-access/DINE_IN/features")).hasSize(1);
        assertThat(operation.at("/x-conditional-access/DINE_IN/features/0").asText())
            .isEqualTo("TABLE_MANAGEMENT");
        assertThat(operation.path("x-conditional-access").has("TAKEAWAY")).isFalse();
        assertThat(operation.path("description").asText()).contains(
            "Requires effective ORDER_COMPLETE and ORDER_MANAGEMENT",
            "DINE_IN additionally requires TABLE_CLOSE and TABLE_MANAGEMENT",
            "checked before mutation, no-op or repair",
            "TAKEAWAY does not require TABLE permissions/features and never calls TABLE closure",
            "all live items SERVED", "paidAmount equal to totalAmount > 0",
            "one transaction", "rolls back the whole request",
            "Retry COMPLETED+CLOSED accepts stale expectedVersion",
            "Legacy COMPLETED+OPEN rechecks payment/items",
            "Never resolve a newer session by tableId", "preserving user DENY");
    }

    @Test
    void completionDocumentsAuthUnexpectedFailureAndSessionBusinessConflicts() throws Exception {
        var lifecycle = read(LIFECYCLE);
        var responses = complete(lifecycle).path("responses");
        for (var status : List.of("200", "400", "401", "403", "404", "409", "500"))
            assertThat(responses.has(status)).as(status).isTrue();
        for (var status : List.of("400", "401", "403", "404", "409", "500")) {
            var response = local(responses.path(status), lifecycle);
            var error = local(response.at("/content/application~1json/schema"), lifecycle);
            for (var field : List.of("success", "code", "message", "fieldErrors", "timestamp"))
                assertThat(error.path("properties").has(field)).as("%s %s", status, field).isTrue();
        }
        assertThat(local(responses.path("401"), lifecycle).path("description").asText())
            .contains("UNAUTHORIZED");
        assertThat(local(responses.path("500"), lifecycle).path("description").asText())
            .contains("INTERNAL_ERROR");
        assertThat(local(responses.path("409"), lifecycle).path("description").asText()).contains(
            "ORDER_NOT_PAID", "ORDER_NOT_READY_FOR_COMPLETION", "INVALID_ORDER_SEATING",
            "TABLE_SESSION_NOT_OPEN", "TABLE_SESSION_NOT_READY_TO_CLOSE", "CONCURRENT_ORDER_UPDATE",
            "CONCURRENT_TABLE_UPDATE");
        assertThat(complete(lifecycle).path("description").asText())
            .contains("TABLE_SESSION_NOT_FOUND");
    }

    @Test
    void manualCloseKeepsSessionVersionBodyAndIndependentClosureSemantics() throws Exception {
        var lifecycle = read(LIFECYCLE);
        var operation = lifecycle.path("paths").path("/api/v1/table-sessions/{sessionId}/close").path("post");
        assertThat(operation.isMissingNode()).isFalse();
        assertThat(operation.path("operationId").asText()).isEqualTo("closeTableSession");
        assertThat(operation.path("parameters")).hasSize(1);
        var parameter = local(operation.path("parameters").get(0), lifecycle);
        assertThat(parameter.path("name").asText()).isEqualTo("sessionId");
        assertThat(parameter.path("required").asBoolean()).isTrue();
        var request = local(operation.path("requestBody"), lifecycle);
        assertThat(request.path("required").asBoolean()).isTrue();
        versionRequest(local(request.at("/content/application~1json/schema"), lifecycle));
        assertThat(operation.path("description").asText()).contains(
            "TABLE_CLOSE + TABLE_MANAGEMENT", "expectedVersion is the SESSION version",
            "CLOSED retry is a no-op", "Does not complete orders",
            "FE need not call it again");
        assertThat(operation.at("/responses/200/content/application~1json/schema/$ref").asText())
            .isEqualTo("#/components/schemas/TableSession");
    }

    private void versionRequest(JsonNode schema) {
        assertThat(schema.path("type").asText()).isEqualTo("object");
        assertThat(schema.path("required")).hasSize(1);
        assertThat(schema.path("required").get(0).asText()).isEqualTo("expectedVersion");
        assertThat(schema.has("additionalProperties")).isTrue();
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
        assertThat(schema.path("properties")).hasSize(1);
        var version = schema.at("/properties/expectedVersion");
        assertThat(version.path("type").asText()).isEqualTo("integer");
        assertThat(version.path("format").asText()).isEqualTo("int64");
        assertThat(version.has("minimum")).isTrue();
        assertThat(version.path("minimum").asLong()).isZero();
    }

    private JsonNode complete(JsonNode lifecycle) {
        var operation = lifecycle.path("paths").path("/api/v1/orders/{orderId}/complete").path("post");
        assertThat(operation.isMissingNode()).isFalse();
        return operation;
    }

    private JsonNode local(JsonNode node, JsonNode document) {
        if (!node.has("$ref")) return node;
        var reference = node.path("$ref").asText();
        assertThat(reference).startsWith("#/");
        var resolved = document.at(reference.substring(1));
        assertThat(resolved.isMissingNode()).as(reference).isFalse();
        return resolved;
    }

    private JsonNode read(Path file) throws Exception {
        try (var input = Files.newInputStream(file)) {
            return new ObjectMapper().valueToTree(new Yaml().load(input));
        }
    }
}
