package com.possaas.infrastructure.subscriptionpayment.sepay;

import com.possaas.infrastructure.security.SecurityErrorResponseWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates SePay as a machine caller: bounded raw body, then HMAC over exactly those bytes.
 * The verified bytes travel as a request attribute so the controller parses what was signed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SepayWebhookAuthenticationFilter extends OncePerRequestFilter {
    public static final String PATH = "/api/v1/webhooks/subscriptions/sepay";
    public static final String AUTHORITY = "SEPAY_WEBHOOK";
    public static final String VERIFIED_BODY = SepayWebhookAuthenticationFilter.class.getName() + ".body";
    private final SepayWebhookProperties properties;
    private final SepayWebhookVerifier verifier;
    private final SecurityErrorResponseWriter errors;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !"POST".equals(request.getMethod()) || !PATH.equals(path);
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        int max = properties.getMaxBodyBytes();
        if (request.getContentLengthLong() > max) { tooLarge(response); return; }
        byte[] body = request.getInputStream().readNBytes(max + 1);
        if (body.length > max) { tooLarge(response); return; }
        var result = verifier.verify(request.getHeader("X-SePay-Timestamp"), request.getHeader("X-SePay-Signature"), body);
        if (result != SepayWebhookVerifier.Result.VALID) {
            log.warn("Rejected SePay webhook from {}: {}", request.getRemoteAddr(), result);
            SecurityContextHolder.clearContext();
            errors.write(response, HttpStatus.UNAUTHORIZED,
                result == SepayWebhookVerifier.Result.TIMESTAMP_INVALID ? "WEBHOOK_TIMESTAMP_INVALID" : "WEBHOOK_AUTHENTICATION_FAILED",
                "SePay webhook authentication failed");
            return;
        }
        request.setAttribute(VERIFIED_BODY, body);
        var authentication = new UsernamePasswordAuthenticationToken("sepay-webhook", null, List.of(new SimpleGrantedAuthority(AUTHORITY)));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        chain.doFilter(request, response);
    }

    private void tooLarge(HttpServletResponse response) throws IOException {
        errors.write(response, HttpStatus.PAYLOAD_TOO_LARGE, "WEBHOOK_PAYLOAD_TOO_LARGE", "Webhook payload too large");
    }
}
