package com.possaas.modules.qrorder;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.*;
import com.possaas.modules.qrorder.dto.*;
import com.possaas.modules.qrorder.entity.*;
import com.possaas.modules.qrorder.service.*;
import com.possaas.modules.menu.application.port.*;
import com.possaas.modules.order.entity.*;
import com.possaas.modules.order.service.OrderMoneyCalculator;
import com.possaas.modules.order.repository.OrderItemRepository;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class QrGuestPolicyTest {
    private final ObjectMapper json=new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final UUID item=UUID.randomUUID();

    @Test void serverOwnsServiceAndSourceMapping() {
        assertThat(QrEntryType.TABLE.serviceType()).isEqualTo(ServiceType.DINE_IN);
        assertThat(QrEntryType.TABLE.sourceChannel()).isEqualTo(SourceChannel.QR_TABLE);
        assertThat(QrEntryType.RESTAURANT.serviceType()).isEqualTo(ServiceType.TAKEAWAY);
        assertThat(QrEntryType.RESTAURANT.sourceChannel()).isEqualTo(SourceChannel.QR_STATIC);
    }
    @Test void scopedDeserializerNormalizesWithoutChangingGlobalUnknownFieldPolicy() throws Exception {
        var command=json.readValue("{\"customerName\":\"  An  \",\"customerPhone\":\"  \",\"note\":\"  hi  \",\"items\":[{\"itemId\":\""+item+"\",\"quantity\":2,\"note\":\" no onion \"}]}",QrGuestRequests.Submit.class);
        assertThat(command.customerName()).isEqualTo("An"); assertThat(command.customerPhone()).isNull();
        assertThat(command.note()).isEqualTo("hi"); assertThat(command.items().getFirst().quantity()).isEqualByComparingTo("2.000");
        assertThat(command.items().getFirst().note()).isEqualTo("no onion");
    }
    @Test void rejectsUnknownScopeAndPriceFieldsIncludingNestedFields() {
        for(String field:List.of("restaurantId","tableId","sessionId","orderId","entryType","serviceType","sourceChannel","unitPrice","status","actor")) {
            assertThatThrownBy(()->json.readValue("{\""+field+"\":\"override\",\"items\":[{\"itemId\":\""+item+"\",\"quantity\":1}]}",QrGuestRequests.Submit.class)).isInstanceOf(JsonMappingException.class);
        }
        assertThatThrownBy(()->json.readValue("{\"items\":[{\"itemId\":\""+item+"\",\"quantity\":1,\"unitPrice\":1}]}",QrGuestRequests.Submit.class)).isInstanceOf(JsonMappingException.class);
    }
    @Test void quantityValidationKeepsDecimalPrecisionAndSeparateGuestCap() {
        for(String quantity:List.of("0","-1","20.001","0.0001","1.000000000000000000000001","1e100","\"2\"")) {
            assertThatThrownBy(()->json.readValue(body(quantity),QrGuestRequests.Submit.class)).as(quantity).isInstanceOf(JsonMappingException.class);
        }
        assertThatCode(()->json.readValue(body("20.000"),QrGuestRequests.Submit.class)).doesNotThrowAnyException();
        assertThatCode(()->json.readValue(body("0.001"),QrGuestRequests.Submit.class)).doesNotThrowAnyException();
    }
    @Test void rejectsEmptyTooManyLinesLongTextAndUnknownEmptyAccessBody() {
        assertThatThrownBy(()->json.readValue("{\"items\":[]}",QrGuestRequests.Submit.class)).isInstanceOf(JsonMappingException.class);
        var line="{\"itemId\":\""+item+"\",\"quantity\":1}";
        assertThatThrownBy(()->json.readValue("{\"items\":["+String.join(",",Collections.nCopies(21,line))+"]}",QrGuestRequests.Submit.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(()->json.readValue("{\"note\":\""+"x".repeat(501)+"\",\"items\":["+line+"]}",QrGuestRequests.Submit.class)).isInstanceOf(JsonMappingException.class);
        assertThatThrownBy(()->json.readValue("{\"sessionId\":null}",QrGuestRequests.Empty.class)).isInstanceOf(JsonMappingException.class);
    }
    @Test void requestDtoAndGrantDoNotExposeCustomerOrCredentialInToString() {
        var submit=new QrGuestRequests.Submit("Sensitive Name","Sensitive Phone","Sensitive Note",List.of());
        assertThat(submit.toString()).doesNotContain("Sensitive");
        var access=new QrGuestAccessResponse("secret-token",null,QrEntryType.TABLE,ServiceType.DINE_IN,SourceChannel.QR_TABLE,null,null);
        assertThat(access.toString()).doesNotContain("secret-token");
        var grant=new QrGuestAuthorization(); grant.setTokenHash("token-digest"); grant.setCredentialHash("credential-digest");
        assertThat(grant.toString()).doesNotContain("digest");
    }
    @Test void quoteReusesMenuBoundaryAndMoneyCalculation() {
        var menu=mock(OrderMenuQuery.class); var tenant=UUID.randomUUID();
        when(menu.requireSellable(tenant,List.of(item))).thenReturn(Map.of(item,new SellableMenuItem(item,"Dish","portion",new BigDecimal("12.35"))));
        var requested=new QrOrderRequestItem(); requested.setItemId(item); requested.setQuantity(new BigDecimal("1.500")); requested.setNote("no onion");
        var quote=new QrQuoteService(menu,new OrderMoneyCalculator()).quote(tenant,"VND",List.of(requested));
        assertThat(quote.amount()).isEqualByComparingTo("18.53");
        assertThat(quote.lines().getFirst().itemName()).isEqualTo("Dish"); verify(menu).requireSellable(tenant,List.of(item));
    }
    @Test void fingerprintsAreCanonicalAndDoNotIncludeMutableSeatingQuotes() {
        var hashes=new QrHashes(json); var a=new LinkedHashMap<String,Object>(); a.put("z",1); a.put("a",2);
        var b=new LinkedHashMap<String,Object>(); b.put("a",2); b.put("z",1);
        assertThat(hashes.hash(a)).isEqualTo(hashes.hash(b)).matches("[a-f0-9]{64}");
        assertThat(hashes.hash(Map.of("expiresAt",Instant.parse("2026-10-14T00:00:00Z"))))
                .isEqualTo(hashes.hash(Map.of("expiresAt","2026-10-14T00:00:00Z")));
        assertThat(QrHashes.digest("secret")).doesNotContain("secret").hasSize(64);
    }
    @Test void pendingReceiptHasNoOrderLineLookupAndNoNullKeyLookup() {
        var orderItems=mock(OrderItemRepository.class); var now=Instant.parse("2026-10-14T00:00:00Z");
        var request=new QrOrderRequest(); request.setId(UUID.randomUUID()); request.initialize(UUID.randomUUID(),now);
        request.setEntryType(QrEntryType.TABLE); request.setExpiresAt(now.plusSeconds(900)); request.setCurrencyCode("VND"); request.setEstimatedAmount(BigDecimal.TEN);
        var line=new QrOrderRequestItem(); line.setId(UUID.randomUUID()); line.setItemId(item); line.setQuantity(BigDecimal.ONE);
        line.setQuotedItemName("Dish"); line.setQuotedUnit("portion"); line.setQuotedUnitPrice(BigDecimal.TEN); line.setQuotedLineTotal(BigDecimal.TEN);
        var receipt=new QrRequestMapper(Clock.fixed(now,ZoneOffset.UTC),orderItems).publicResponse(request,List.of(line));
        assertThat(receipt.status()).isEqualTo(QrRequestStatus.PENDING); assertThat(receipt.items()).hasSize(1);
        assertThat(receipt.items().getFirst().approvedOrderItemId()).isNull(); assertThat(receipt.items().getFirst().status()).isNull();
        verifyNoInteractions(orderItems);
    }
    private String body(String quantity) { return "{\"items\":[{\"itemId\":\""+item+"\",\"quantity\":"+quantity+"}]}"; }
}
