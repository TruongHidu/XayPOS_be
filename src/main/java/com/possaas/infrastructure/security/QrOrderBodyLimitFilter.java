package com.possaas.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Enforces a byte budget even for chunked requests before Jackson allocates the full body. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 11) @RequiredArgsConstructor
public class QrOrderBodyLimitFilter extends OncePerRequestFilter {
    private static final int MAX_BYTES = 32768;
    private final ObjectMapper mapper;
    private final Clock clock;
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod()) || (!PublicQrOrderRequests.matches(request)
            && !request.getServletPath().startsWith("/api/v1/qr-order-requests/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
        throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) { reject(response); return; }
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) { reject(response); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var buffer = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return buffer.read(); }
                    @Override public int read(byte[] b,int off,int len) { return buffer.read(b,off,len); }
                    @Override public boolean isFinished() { return buffer.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("Synchronous request body"); }
                };
            }
            @Override public BufferedReader getReader() throws IOException {
                return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
            }
        }, response);
    }
    private void reject(HttpServletResponse response) throws IOException {
        response.setStatus(413); response.setContentType("application/json");
        mapper.writeValue(response.getOutputStream(), Map.of("success",false,"code","QR_REQUEST_BODY_TOO_LARGE",
            "message","QR request exceeds the body size limit","fieldErrors",Map.of(),"timestamp",clock.instant().toString()));
    }
}
