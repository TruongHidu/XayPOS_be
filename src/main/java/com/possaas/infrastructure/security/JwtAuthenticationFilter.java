package com.possaas.infrastructure.security;

import com.possaas.common.security.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtDecoder decoder;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, java.io.IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Jwt jwt = decoder.decode(header.substring(7));
                UUID userId = UUID.fromString(jwt.getSubject());
                String restaurantValue = jwt.getClaimAsString("restaurant_id");
                UUID restaurantId = restaurantValue == null ? null : UUID.fromString(restaurantValue);
                String role = jwt.getClaimAsString("role");
                List<String> permissionsClaim = jwt.getClaimAsStringList("permissions");
                Set<String> permissions = permissionsClaim == null ? Set.of() : Set.copyOf(permissionsClaim);
                CurrentUser currentUser = new CurrentUser(userId, restaurantId, jwt.getClaimAsString("email"), role, permissions);
                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                if (role != null) authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
                permissions.forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                var authentication = new UsernamePasswordAuthenticationToken(currentUser, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException | IllegalArgumentException ignored) {
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }
}
