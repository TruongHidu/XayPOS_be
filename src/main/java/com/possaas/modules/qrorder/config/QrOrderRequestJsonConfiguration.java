package com.possaas.modules.qrorder.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.modules.qrorder.dto.QrGuestRequests;
import com.possaas.modules.qrorder.dto.QrStaffRequests;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.*;
import org.springframework.http.converter.*;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Ensures the project's Jackson 2 QR deserializers also run with Boot 4's Jackson 3 MVC default. */
@Configuration
public class QrOrderRequestJsonConfiguration implements WebMvcConfigurer {
    private static final Set<Class<?>> TYPES = Set.of(QrGuestRequests.Empty.class, QrGuestRequests.Submit.class,
        QrStaffRequests.Approve.class, QrStaffRequests.Reject.class);
    private final ObjectMapper requests;
    public QrOrderRequestJsonConfiguration(ObjectMapper mapper) { requests = mapper.copy(); }
    @Override public void extendMessageConverters(List<HttpMessageConverter<?>> converters) {
        converters.addFirst(new AbstractHttpMessageConverter<Object>(MediaType.APPLICATION_JSON) {
            @Override protected boolean supports(Class<?> type) { return TYPES.contains(type); }
            @Override public boolean canWrite(Class<?> type, MediaType mediaType) { return false; }
            @Override protected Object readInternal(Class<?> type, HttpInputMessage input) throws IOException {
                try { return requests.readValue(input.getBody(), type); }
                catch (JsonProcessingException malformed) {
                    throw new HttpMessageNotReadableException("Invalid QR request body", input);
                }
            }
            @Override protected void writeInternal(Object value, HttpOutputMessage output) {
                throw new HttpMessageNotWritableException("QR request converter is read-only");
            }
        });
    }
}
