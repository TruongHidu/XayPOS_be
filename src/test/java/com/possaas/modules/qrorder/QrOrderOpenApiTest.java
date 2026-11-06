package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.service.QrQuoteService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** The public receipt allowlist and explicit approval target must not drift from runtime DTOs. */
class QrOrderOpenApiTest {
    private static final Path FILE = Path.of("docs/openapi/public-qr-order-api.yaml");
    private static final String BASE = "/api/v1/public/qr-order";
    private static final String STAFF = "/api/v1/qr-order-requests";
    private final Map<Path, JsonNode> documents = new HashMap<>();

    @Test
    void documentsExactlyEightOperationsWithoutOpeningPrivateOrderOrTableRoutes() throws Exception {
        var root = document(FILE);
        assertThat(names(root.path("paths"))).containsExactlyInAnyOrder(
            BASE + "/tables/{qrToken}/guest-access", BASE + "/restaurants/{menuToken}/guest-access",
            BASE + "/requests", BASE + "/requests/{requestId}", STAFF,
            STAFF + "/{requestId}", STAFF + "/{requestId}/approve", STAFF + "/{requestId}/reject");
        var operationIds = new HashSet<String>();
        root.path("paths").fields().forEachRemaining(path -> {
            assertThat(path.getValue()).hasSize(1);
            path.getValue().forEach(operation -> {
                assertThat(operationIds.add(operation.path("operationId").asText())).isTrue();
                assertThat(operation.path("responses").has("500")).isTrue();
            });
        });
        assertThat(operationIds).hasSize(8);
        for (var entry : List.of("tables/{qrToken}", "restaurants/{menuToken}")) {
            var operation = root.path("paths").path(BASE + "/" + entry + "/guest-access").path("post");
            assertThat(operation.path("security")).isEmpty();
            assertThat(operation.path("responses").has("201")).isTrue();
            var body = resolve(operation.path("requestBody"), FILE);
            assertThat(body.path("required").asBoolean()).isTrue();
            assertThat(body.at("/content/application~1json/schema/additionalProperties").asBoolean()).isFalse();
            assertThat(body.at("/content/application~1json/schema/maxProperties").asInt()).isZero();
        }
        assertThat(root.at("/components/securitySchemes/QrGuestToken/name").asText()).isEqualTo("X-QR-Guest-Token");
        assertThat(root.at("/components/securitySchemes/QrGuestToken/in").asText()).isEqualTo("header");
        for (var path : List.of(BASE + "/requests", BASE + "/requests/{requestId}"))
            root.path("paths").path(path).forEach(operation ->
                assertThat(operation.path("security").get(0).has("QrGuestToken")).isTrue());
        for (var path : List.of(STAFF, STAFF + "/{requestId}", STAFF + "/{requestId}/approve", STAFF + "/{requestId}/reject"))
            root.path("paths").path(path).forEach(operation ->
                assertThat(operation.path("security").get(0).has("BearerAuth")).isTrue());
    }

    @Test
    void snapshotFeatureAndStaffPermissionMatrixPreservesIndependentTakeawayGate() throws Exception {
        var paths = document(FILE).path("paths");
        assertThat(values(paths.path(BASE + "/tables/{qrToken}/guest-access").path("post").path("x-features")))
            .containsExactly("QR_MENU_VIEW", "QR_TABLE_ORDER", "ORDER_MANAGEMENT", "TABLE_MANAGEMENT");
        assertThat(values(paths.path(BASE + "/restaurants/{menuToken}/guest-access").path("post").path("x-features")))
            .containsExactly("QR_MENU_VIEW", "QR_STATIC_ORDER", "ORDER_MANAGEMENT");
        var approve = paths.path(STAFF + "/{requestId}/approve").path("post");
        assertThat(values(approve.path("x-permissions"))).containsExactly("ORDER_CREATE", "ORDER_UPDATE");
        assertThat(approve.path("x-conditional-permission").asText()).contains("TABLE_OPEN", "only");
        for (var operation : List.of(approve, paths.path(BASE + "/requests").path("post"))) {
            assertThat(values(operation.path("x-features-by-entry").path("RESTAURANT")))
                .doesNotContain("TABLE_MANAGEMENT", "POS_QUICK_ORDER");
            assertThat(values(operation.path("x-features-by-entry").path("TABLE"))).contains("TABLE_MANAGEMENT", "QR_TABLE_ORDER");
        }
        assertThat(values(paths.path(STAFF).path("get").path("x-permissions"))).containsExactly("ORDER_VIEW");
        assertThat(values(paths.path(STAFF + "/{requestId}").path("get").path("x-permissions"))).containsExactly("ORDER_VIEW");
        assertThat(values(paths.path(STAFF + "/{requestId}/reject").path("post").path("x-permissions"))).containsExactly("ORDER_UPDATE");
    }

    @Test
    void guestBodyAllowsOnlyIntentFieldsAndSeparateSmallLimits() throws Exception {
        var schemas = document(FILE).at("/components/schemas");
        var submit = schemas.path("SubmitQrRequest");
        assertThat(names(submit.path("properties"))).containsExactlyInAnyOrder("customerName", "customerPhone", "note", "items");
        assertThat(submit.path("additionalProperties").asBoolean()).isFalse();
        assertThat(values(submit.path("required"))).containsExactly("items");
        assertThat(submit.at("/properties/items/maxItems").asInt()).isEqualTo(20);
        assertThat(submit.at("/properties/customerName/maxLength").asInt()).isEqualTo(100);
        assertThat(submit.at("/properties/customerPhone/maxLength").asInt()).isEqualTo(30);
        assertThat(submit.at("/properties/note/maxLength").asInt()).isEqualTo(500);
        var line = schemas.path("SubmitLine");
        assertThat(names(line.path("properties"))).containsExactlyInAnyOrder("itemId", "quantity", "note");
        assertThat(line.path("additionalProperties").asBoolean()).isFalse();
        assertThat(line.at("/properties/quantity/maximum").asInt()).isEqualTo(20);
        assertThat(line.at("/properties/quantity/multipleOf").decimalValue()).isEqualByComparingTo("0.001");
        assertThat(line.at("/properties/note/maxLength").asInt()).isEqualTo(500);
        assertThat(document(FILE).at("/components/parameters/IdempotencyKey/required").asBoolean()).isTrue();
        var responses = document(FILE).path("paths").path(BASE + "/requests").path("post").path("responses");
        assertThat(responses.has("200")).isTrue();
        assertThat(responses.has("201")).isTrue();
        for (var code : List.of("200", "201"))
            assertThat(resolve(responses.path(code), FILE).path("headers").has("Idempotency-Replayed")).isTrue();
    }

    @Test
    void approveRequiresExplicitNullableTargetAndQrRequestVersionNotOrderVersion() throws Exception {
        var approve = document(FILE).at("/components/schemas/ApproveQrRequest");
        assertThat(values(approve.path("required")))
            .containsExactlyInAnyOrder("expectedRequestVersion", "expectedReviewContextHash", "targetSessionId");
        assertThat(names(approve.path("properties")))
            .containsExactlyInAnyOrder("expectedRequestVersion", "expectedReviewContextHash", "targetSessionId");
        assertThat(approve.path("additionalProperties").asBoolean()).isFalse();
        assertThat(approve.at("/properties/targetSessionId/nullable").asBoolean()).isTrue();
        assertThat(approve.at("/properties/targetSessionId/description").asText()).contains("Explicit", "RESTAURANT", "null");
        assertThat(approve.at("/properties/expectedRequestVersion/description").asText()).contains("not Order.version");
        assertThat(approve.at("/properties/expectedReviewContextHash/pattern").asText()).isEqualTo("^[a-f0-9]{64}$");
        var reject = document(FILE).at("/components/schemas/RejectQrRequest");
        assertThat(values(reject.path("required"))).containsExactlyInAnyOrder("expectedRequestVersion", "reason");
        assertThat(reject.at("/properties/reason/maxLength").asInt()).isEqualTo(1000);
    }

    @Test
    void documentedDtoFieldsExactlyMatchRuntimeRecords() throws Exception {
        var records = Map.<String, Class<?>>ofEntries(
            Map.entry("GuestAccess", QrGuestAccessResponse.class),
            Map.entry("GuestRestaurant", QrGuestAccessResponse.RestaurantMetadata.class),
            Map.entry("GuestTable", QrGuestAccessResponse.TableMetadata.class),
            Map.entry("GuestReceipt", QrPublicResponse.class), Map.entry("GuestLine", QrPublicResponse.Line.class),
            Map.entry("StaffSummary", QrStaffRequestSummary.class), Map.entry("StaffLine", QrStaffRequestSummary.Line.class),
            Map.entry("StaffDetail", QrStaffRequestDetail.class), Map.entry("TableReviewContext", QrStaffRequestDetail.ReviewContext.class),
            Map.entry("ServingOrder", QrStaffRequestDetail.ServingOrder.class), Map.entry("Approval", QrApprovalResponse.class),
            Map.entry("Quote", QrQuoteService.QrQuote.class), Map.entry("QuoteLine", QrQuoteService.Line.class));
        var root = document(FILE);
        for (var entry : records.entrySet()) {
            var fields = Arrays.stream(entry.getValue().getRecordComponents()).map(component -> component.getName()).collect(Collectors.toSet());
            var schema = root.at("/components/schemas/" + entry.getKey());
            assertThat(names(schema.path("properties"))).as(entry.getKey()).containsExactlyInAnyOrderElementsOf(fields);
            assertThat(schema.path("additionalProperties").asBoolean()).as(entry.getKey()).isFalse();
        }
    }

    @Test
    void guestReceiptIsNotFullBillOrOtherCustomerStaffData() throws Exception {
        var root = document(FILE);
        for (var schema : List.of("GuestReceipt", "GuestLine")) {
            assertThat(names(root.at("/components/schemas/" + schema + "/properties"))).doesNotContain(
                "order", "totalAmount", "paidAmount", "createdBy", "approvedBy", "customerName", "customerPhone",
                "costPrice", "metadata", "tableSessionId", "featureSnapshot", "guestToken", "tokenDigest", "idempotencyKey");
        }
        assertThat(root.at("/components/schemas/GuestReceipt/properties/approvedAmount/description").asText()).contains("request only", "never entire table bill");
        assertThat(root.at("/components/schemas/GuestLine/properties/status/nullable").asBoolean()).isTrue();
        assertThat(root.at("/components/schemas/Approval/properties/order/$ref").asText())
            .isEqualTo("order-api.yaml#/components/schemas/Order");
    }

    @Test
    void everyResponseHasNoStoreAndRateLimitIncludesRetryAfter() throws Exception {
        var root = document(FILE);
        root.path("paths").forEach(path -> path.forEach(operation -> operation.path("responses").forEach(response -> {
            var resolved = resolve(response, FILE);
            var header = resolve(resolved.path("headers").path("Cache-Control"), FILE);
            assertThat(header.at("/schema/example").asText()).isEqualTo("no-store");
            assertThat(resolved.at("/content/application~1json/schema").isMissingNode()).isFalse();
        })));
        assertThat(root.at("/components/responses/RateLimited/headers/Retry-After/schema/minimum").asInt()).isEqualTo(1);
        assertThat(root.at("/components/responses/RateLimited/description").asText()).contains("single-instance", "shared limiter");
        assertThat(names(root.at("/components/schemas/Error/properties")))
            .containsExactlyInAnyOrder("success", "code", "message", "fieldErrors", "timestamp");
    }

    @Test
    void everyLocalAndExternalReferenceResolves() throws Exception {
        references(document(FILE), FILE, new HashSet<>());
        assertThat(documents).containsKey(Path.of("docs/openapi/order-api.yaml"));
    }

    @Test
    void yamlFlowDescriptionsDoNotBecomeAccidentalSchemaKeys() throws Exception {
        var schemas = document(FILE).at("/components/schemas");
        schemas.fields().forEachRemaining(entry -> validateSchema(entry.getValue(), entry.getKey()));
    }

    private void validateSchema(JsonNode schema, String name) {
        var allowed = Set.of("$ref", "type", "format", "nullable", "description", "required", "properties",
            "additionalProperties", "enum", "minLength", "maxLength", "pattern", "minimum", "maximum",
            "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "minItems", "maxItems", "items", "allOf", "oneOf", "example");
        assertThat(names(schema)).as("schema keys of %s", name).allMatch(allowed::contains);
        schema.path("properties").fields().forEachRemaining(entry -> validateSchema(entry.getValue(), name + "." + entry.getKey()));
        if (schema.has("items")) validateSchema(schema.path("items"), name + "[]");
        for (var keyword : List.of("allOf", "oneOf"))
            schema.path(keyword).forEach(child -> validateSchema(child, name + "." + keyword));
    }

    private List<String> values(JsonNode node) {
        var result = new ArrayList<String>();
        node.forEach(value -> result.add(value.asText()));
        return result;
    }
    private Set<String> names(JsonNode node) {
        var result = new HashSet<String>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
    private JsonNode document(Path file) throws Exception {
        var key = file.normalize();
        if (!documents.containsKey(key)) try (var input = Files.newInputStream(key)) {
            documents.put(key, new ObjectMapper().valueToTree(new Yaml().load(input)));
        }
        return documents.get(key);
    }
    private JsonNode resolve(JsonNode node, Path owner) {
        var aliases = new HashSet<String>();
        while (node.has("$ref")) {
            var value = node.path("$ref").asText();
            assertThat(aliases.add(owner + "#" + value)).isTrue();
            var parts = value.split("#", 2);
            assertThat(parts).hasSize(2);
            owner = parts[0].isEmpty() ? owner : owner.getParent().resolve(parts[0]).normalize();
            assertThat(owner).startsWith(Path.of("docs/openapi"));
            try { node = document(owner).at(parts[1]); }
            catch (Exception failure) { throw new AssertionError(value, failure); }
            assertThat(node.isMissingNode()).as(value).isFalse();
        }
        return node;
    }
    private void references(JsonNode node, Path owner, Set<String> visited) throws Exception {
        if (node.has("$ref")) {
            var value = node.path("$ref").asText();
            var parts = value.split("#", 2);
            assertThat(parts).hasSize(2);
            var target = parts[0].isEmpty() ? owner : owner.getParent().resolve(parts[0]).normalize();
            assertThat(target).startsWith(Path.of("docs/openapi"));
            var resolved = document(target).at(parts[1]);
            assertThat(resolved.isMissingNode()).as(value).isFalse();
            if (visited.add(target + "#" + parts[1])) references(resolved, target, visited);
        }
        if (node.isContainerNode()) for (var child : node) references(child, owner, visited);
    }
}
