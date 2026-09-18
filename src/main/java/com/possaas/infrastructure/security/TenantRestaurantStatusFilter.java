package com.possaas.infrastructure.security;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.restaurant.application.port.RestaurantAccessChecker;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@RequiredArgsConstructor
public class TenantRestaurantStatusFilter extends OncePerRequestFilter {
    private static final String API_V1_PACKAGES = "/api/v1/packages";

    private final RestaurantAccessChecker restaurantAccessChecker;
    private final SecurityErrorResponseWriter errorResponseWriter;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath();
        String method = request.getMethod();

        if ("/error".equals(path)) {
            return true;
        }
        if (HttpMethod.POST.matches(method)) {
            return "/api/v1/auth/register-restaurant".equals(path)
                || "/api/v1/auth/login".equals(path)
                || "/api/v1/auth/refresh".equals(path);
        }
        return HttpMethod.GET.matches(method)
            && (API_V1_PACKAGES.equals(path) || path.startsWith(API_V1_PACKAGES + "/"));
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !authentication.isAuthenticated()
            || !(authentication.getPrincipal() instanceof CurrentUser currentUser)
            || currentUser.isSuperAdmin()
            || currentUser.restaurantId() == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (restaurantAccessChecker.isActive(currentUser.restaurantId())) {
            filterChain.doFilter(request, response);
            return;
        }

        SecurityContextHolder.clearContext();
        errorResponseWriter.write(
            response,
            HttpStatus.FORBIDDEN,
            "RESTAURANT_INACTIVE",
            "Restaurant is not active"
        );
    }
}
