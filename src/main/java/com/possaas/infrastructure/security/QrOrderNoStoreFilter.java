package com.possaas.infrastructure.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Includes authentication/validation failures, not just successful controller responses. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class QrOrderNoStoreFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !PublicQrOrderRequests.matches(request)
            && !request.getServletPath().equals("/api/v1/qr-order-requests")
            && !request.getServletPath().startsWith("/api/v1/qr-order-requests/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        chain.doFilter(request, response);
    }
}
