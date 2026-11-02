package com.possaas.modules.subscriptionpurchase.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.possaas.modules.subscriptionpurchase.dto.PurchaseRequests;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Strict request-only converter; leaves Boot's response mapper and all other API contracts unchanged. */
@Configuration
public class PurchaseRequestJsonConfiguration implements WebMvcConfigurer {
    private static final Set<Class<?>> TYPES = Set.of(PurchaseRequests.Create.class, PurchaseRequests.Cancel.class);
    private final ObjectMapper requests;
    public PurchaseRequestJsonConfiguration(ObjectMapper mapper) {
        requests = mapper.copy();
        requests.coercionConfigFor(LogicalType.Integer).setCoercion(CoercionInputShape.String, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        requests.coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
            .setCoercion(CoercionInputShape.Float, CoercionAction.Fail).setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }
    @Override public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        builder.configureMessageConvertersList(converters -> converters.addFirst(new AbstractHttpMessageConverter<Object>(MediaType.APPLICATION_JSON) {
            @Override protected boolean supports(Class<?> type) { return TYPES.contains(type); }
            @Override public boolean canWrite(Class<?> type, MediaType mediaType) { return false; }
            @Override protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
                try { return requests.readValue(input.getBody(), type); }
                catch (JsonProcessingException invalid) { throw new HttpMessageNotReadableException("Invalid purchase request body", invalid, input); }
            }
            @Override protected void writeInternal(Object value, HttpOutputMessage output) {
                throw new HttpMessageNotWritableException("Purchase converter is request-only");
            }
        }));
    }
}
