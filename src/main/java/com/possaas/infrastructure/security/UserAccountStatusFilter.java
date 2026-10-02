package com.possaas.infrastructure.security;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.user.application.port.UserAccessChecker;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class UserAccountStatusFilter extends OncePerRequestFilter {
    private final UserAccessChecker accounts;
    private final SecurityErrorResponseWriter errors;

    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        if ("/error".equals(path)) return true;
        if ("POST".equals(request.getMethod())) return path.equals("/api/v1/auth/login")
            || path.equals("/api/v1/auth/refresh") || path.equals("/api/v1/auth/register-restaurant");
        return "GET".equals(request.getMethod()) && (path.equals("/api/v1/packages") || path.startsWith("/api/v1/packages/"));
    }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        // This boundary validates bearer sessions established by our JWT filter, not unrelated authentication mechanisms.
        if (Boolean.TRUE.equals(request.getAttribute(JwtAuthenticationFilter.JWT_AUTHENTICATED))
            && authentication != null && authentication.getPrincipal() instanceof CurrentUser user
            && !accounts.isActive(user.userId(), user.restaurantId())) {
            SecurityContextHolder.clearContext();
            errors.write(response, HttpStatus.UNAUTHORIZED, "ACCOUNT_INACTIVE", "Account is inactive or unavailable");
            return;
        }
        chain.doFilter(request, response);
    }
}
