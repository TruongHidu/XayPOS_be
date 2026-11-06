package com.possaas.infrastructure.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** Servlet boundary precedes Spring Security so authentication/authorization errors are not cached either. */
@Component @Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class PurchaseNoStoreFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path.isEmpty()) path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.equals("/api/v1/subscriptions/onboarding") && !path.equals("/api/v1/subscription-purchases")
            && !path.startsWith("/api/v1/subscription-purchases/");
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        response.setHeader("Cache-Control", "no-store");
        response.addHeader("Vary", "Authorization");
        chain.doFilter(request, response);
    }
}
