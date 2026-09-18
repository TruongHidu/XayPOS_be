package com.possaas.infrastructure.security;

import com.fasterxml.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SecurityErrorResponseWriter {
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public void write(
        HttpServletResponse response,
        HttpStatus status,
        String code,
        String message
    ) throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.resetBuffer();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        jsonMapper.writeValue(
            response.getOutputStream(),
            new SecurityErrorResponse(false, code, message, Map.of(), clock.instant().toString())
        );
        response.flushBuffer();
    }

    private record SecurityErrorResponse(
        boolean success,
        String code,
        String message,
        Map<String, String> fieldErrors,
        String timestamp
    ) {}
}
