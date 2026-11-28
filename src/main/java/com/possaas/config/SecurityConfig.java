package com.possaas.config;

import com.possaas.infrastructure.security.JwtAuthenticationFilter;
import com.possaas.infrastructure.security.PublicMenuRequests;
import com.possaas.infrastructure.security.PublicQrOrderRequests;
import com.possaas.infrastructure.security.RequestRateLimitFilter;
import com.possaas.infrastructure.security.RestAccessDeniedHandler;
import com.possaas.infrastructure.security.RestAuthenticationEntryPoint;
import com.possaas.infrastructure.security.TenantRestaurantStatusFilter;
import com.possaas.infrastructure.security.UserAccountStatusFilter;
import com.possaas.infrastructure.subscriptionpayment.sepay.SepayWebhookAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.CorsFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        JwtAuthenticationFilter jwtFilter,
        RequestRateLimitFilter rateLimitFilter,
        SepayWebhookAuthenticationFilter sepayWebhookFilter,
        UserAccountStatusFilter userAccountStatusFilter,
        TenantRestaurantStatusFilter tenantRestaurantStatusFilter,
        RestAuthenticationEntryPoint authenticationEntryPoint,
        RestAccessDeniedHandler accessDeniedHandler
    ) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint(authenticationEntryPoint)
                .accessDeniedHandler(accessDeniedHandler))
            .authorizeHttpRequests(auth -> auth
                // JWT authentication for this handshake is performed by STOMP CONNECT.
                .requestMatchers(HttpMethod.GET, "/ws").permitAll()
                .requestMatchers(PublicMenuRequests.GET_ENDPOINTS).permitAll()
                .requestMatchers(PublicQrOrderRequests.ENDPOINTS).permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register-restaurant", "/api/v1/auth/login", "/api/v1/auth/refresh").permitAll()
                // Logout proves possession of the refresh token itself (cookie or body); see AuthController.
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/logout").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/packages", "/api/v1/packages/**").permitAll()
                // SePay is authenticated by HMAC in SepayWebhookAuthenticationFilter, never by JWT; only this exact path.
                .requestMatchers(HttpMethod.POST, SepayWebhookAuthenticationFilter.PATH).hasAuthority(SepayWebhookAuthenticationFilter.AUTHORITY)
                .requestMatchers("/error").permitAll()
                .anyRequest().authenticated())
            // After CORS so a 429 keeps its CORS headers; before anything reads the body or touches the database.
            .addFilterAfter(rateLimitFilter, CorsFilter.class)
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(sepayWebhookFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(userAccountStatusFilter, JwtAuthenticationFilter.class)
            .addFilterAfter(tenantRestaurantStatusFilter, UserAccountStatusFilter.class);
        return http.build();
    }
}
