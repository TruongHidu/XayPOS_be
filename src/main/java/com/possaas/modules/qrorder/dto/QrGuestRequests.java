package com.possaas.modules.qrorder.dto;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.*;

public final class QrGuestRequests {
    private QrGuestRequests() {}
    public static final class Empty {
        @JsonAnySetter public void unknown(String field,Object value) { throw new IllegalArgumentException("Guest access body must be empty"); }
        @Override public String toString() { return "QrGuestAccessBody[]"; }
    }
    @JsonDeserialize(using=SubmitDeserializer.class)
    public record Submit(String customerName,String customerPhone,String note,List<Line> items) {
        @Override public String toString() { return "QrGuestSubmit[redacted]"; }
    }
    public record Line(UUID itemId,BigDecimal quantity,String note) {
        @Override public String toString() { return "QrGuestLine[redacted]"; }
    }
    /** Scoped strict deserialization; does not change legacy staff ObjectMapper behavior. */
    public static final class SubmitDeserializer extends JsonDeserializer<Submit> {
        private static final ObjectMapper STRICT_NUMBERS=new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
        @Override public Submit deserialize(JsonParser parser,DeserializationContext context) throws IOException {
            JsonNode root=STRICT_NUMBERS.readTree(parser);
            requireFields(parser,root,Set.of("customerName","customerPhone","note","items"));
            JsonNode source=root.get("items");
            if(source==null || !source.isArray() || source.isEmpty() || source.size()>20) throw invalid(parser);
            var lines=new ArrayList<Line>();
            for(JsonNode item:source) {
                requireFields(parser,item,Set.of("itemId","quantity","note"));
                JsonNode id=item.get("itemId"),qty=item.get("quantity");
                if(id==null || !id.isTextual() || id.textValue().length()!=36 || qty==null || !qty.isNumber() || qty.asText().length()>32) throw invalid(parser);
                UUID itemId; BigDecimal quantity;
                try { itemId=UUID.fromString(id.textValue()); quantity=qty.decimalValue(); }
                catch(RuntimeException malformed) { throw invalid(parser); }
                if(quantity.signum()<=0 || quantity.compareTo(new BigDecimal("20"))>0 || quantity.stripTrailingZeros().scale()>3) throw invalid(parser);
                lines.add(new Line(itemId,quantity.setScale(3),text(parser,item,"note",500)));
            }
            return new Submit(text(parser,root,"customerName",100),text(parser,root,"customerPhone",30),text(parser,root,"note",500),List.copyOf(lines));
        }
        private static String text(JsonParser parser,JsonNode root,String name,int max) throws IOException {
            var value=root.get(name); if(value==null || value.isNull()) return null;
            if(!value.isTextual() || value.textValue().length()>max) throw invalid(parser);
            var result=value.textValue().trim(); return result.isEmpty()?null:result;
        }
        private static void requireFields(JsonParser parser,JsonNode node,Set<String> allowed) throws IOException {
            if(node==null || !node.isObject()) throw invalid(parser);
            var fields=node.fieldNames(); while(fields.hasNext()) if(!allowed.contains(fields.next())) throw invalid(parser);
        }
        private static JsonMappingException invalid(JsonParser parser) {
            return JsonMappingException.from(parser,"Invalid QR request body");
        }
    }
}
