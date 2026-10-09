package com.possaas.modules.order;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class OrderOpenApiTest {
    private JsonNode document() throws Exception {
        try(var input=Files.newInputStream(Path.of("docs/openapi/order-api.yaml"))) { return new ObjectMapper().valueToTree(new Yaml().load(input)); }
    }
    @Test void documentCoversTenPrivateOperationsAndResolvesEveryReference() throws Exception {
        var root=document(); var operations=new HashSet<String>();
        root.path("paths").fields().forEachRemaining(path->{
            assertThat(path.getKey().startsWith("/api/v1/orders") ||
                path.getKey().equals("/api/v1/table-sessions/{sessionId}/active-order")).isTrue();
            assertThat(path.getKey()).doesNotContain("/payment","/complete","/cooking","/ready");
            path.getValue().forEach(operation->{
                assertThat(operations.add(operation.path("operationId").asText())).isTrue();
                assertThat(operation.path("security").get(0).has("BearerAuth")).isTrue();
                assertThat(operation.path("description").asText()).contains("ORDER_MANAGEMENT","ORDER_");
                for(String status:List.of("400","401","403","404","409","500")) assertThat(operation.path("responses").has(status)).isTrue();
            });
        });
        assertThat(operations).hasSize(10); references(root,root);
        var create=root.at("/paths/~1api~1v1~1orders/post");
        assertThat(create.path("parameters").get(0).path("name").asText()).isEqualTo("Idempotency-Key");
        assertThat(create.path("parameters").get(0).path("required").asBoolean()).isTrue();
        assertThat(create.path("responses").has("201")).isTrue();
    }
    @Test void schemasDoNotExposeInternalDataOrAllowClientAmountsAndActors() throws Exception {
        var root=document(); var request=root.at("/components/schemas/CreateOrder/properties");
        for(String name:List.of("restaurantId","createdBy","unitPrice","status","paymentStatus","currencyCode","totalAmount")) assertThat(request.has(name)).isFalse();
        assertThat(request.at("/sourceChannel/enum").toString()).contains("CASHIER","WAITER").doesNotContain("QR_TABLE","QR_STATIC");
        var order=root.at("/components/schemas/Order/properties");
        for(String name:List.of("costPrice","requestHash","idempotencyKey","mutationSequence","metadata")) assertThat(order.has(name)).isFalse();
        assertThat(root.at("/components/schemas/OrderItem/properties").has("costPrice")).isFalse();
        assertThat(root.at("/components/schemas/UpdateItem/required").toString()).contains("expectedVersion");
    }
    @Test void tableSubmissionExtendsExistingRouteWithExclusiveSeatingShapesAndConditionalPermission() throws Exception {
        var root=document(); var create=root.at("/components/schemas/CreateOrder");
        assertThat(create.at("/properties/tableId/format").asText()).isEqualTo("uuid");
        assertThat(create.path("required").toString()).doesNotContain("tableId","submissionMode");
        assertThat(create.path("oneOf").size()).isEqualTo(3);
        assertThat(create.at("/oneOf/0/required").toString()).contains("tableId","submissionMode");
        assertThat(create.at("/oneOf/0/properties/submissionMode/enum").toString()).isEqualTo("[\"SUBMIT\"]");
        assertThat(create.at("/oneOf/1/required").toString()).contains("tableSessionId");
        assertThat(create.at("/oneOf/2/properties/serviceType/enum").toString()).contains("TAKEAWAY");
        var operation=root.at("/paths/~1api~1v1~1orders/post");
        assertThat(operation.path("description").asText()).contains("TABLE_OPEN","after replay","create-table-submit-v1");
        assertThat(operation.at("/requestBody/content/application~1json/examples/submitFromTable/value/submissionMode").asText()).isEqualTo("SUBMIT");
        assertThat(root.at("/paths/~1api~1v1~1orders~1from-table").isMissingNode()).isTrue();
    }
    private void references(JsonNode node,JsonNode root) {
        if(node.has("$ref")) { String ref=node.path("$ref").asText(); assertThat(ref).startsWith("#/"); assertThat(root.at(ref.substring(1)).isMissingNode()).as(ref).isFalse(); }
        if(node.isContainerNode()) node.forEach(child->references(child,root));
    }
    @Test void submissionIsOptionalAndAppendIdempotencyIsConditionalWithoutNewRoutes() throws Exception {
        var root=document();
        assertThat(root.at("/components/schemas/OrderSubmissionMode/default").asText()).isEqualTo("DRAFT");
        for(String schema:List.of("CreateOrder","AddItems")) {
            assertThat(root.at("/components/schemas/"+schema+"/properties/submissionMode/$ref").asText()).isEqualTo("#/components/schemas/OrderSubmissionMode");
            assertThat(root.at("/components/schemas/"+schema+"/required").toString()).doesNotContain("submissionMode");
        }
        var append=root.at("/paths/~1api~1v1~1orders~1{orderId}~1items/post");
        assertThat(append.path("parameters").get(1).path("name").asText()).isEqualTo("Idempotency-Key");
        assertThat(append.path("parameters").get(1).path("description").asText()).contains("Required for SUBMIT");
        assertThat(append.path("responses").path("200").path("headers").has("Idempotency-Replayed")).isTrue();
        assertThat(root.at("/paths/~1api~1v1~1orders/post/description").asText()).contains("ORDER_CREATE plus ORDER_UPDATE");
    }
}
