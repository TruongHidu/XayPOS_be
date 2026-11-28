package com.possaas.modules.auth.controller;

import com.possaas.common.ratelimit.RequestRateLimitRule;
import com.possaas.common.ratelimit.RequestRateLimitRules;
import com.possaas.config.AuthRateLimitProperties;
import java.util.List;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

/**
 * Per-client-address limits for the public {@link AuthController} endpoints. Enforced by the request filter before
 * the body is parsed, so floods are rejected without validation, transactions or database work.
 */
@Component
public class AuthRateLimitRules implements RequestRateLimitRules {
    static final String LOGIN = "/api/v1/auth/login";
    static final String REGISTER = "/api/v1/auth/register-restaurant";
    static final String REFRESH = "/api/v1/auth/refresh";

    private final List<RequestRateLimitRule> rules;

    public AuthRateLimitRules(AuthRateLimitProperties properties) {
        // Built once so a misconfigured limit fails at startup.
        this.rules = List.of(
            new RequestRateLimitRule(HttpMethod.POST, LOGIN, properties.getLogin().toPolicy("auth.login")),
            new RequestRateLimitRule(HttpMethod.POST, REGISTER, properties.getRegister().toPolicy("auth.register")),
            new RequestRateLimitRule(HttpMethod.POST, REFRESH, properties.getRefresh().toPolicy("auth.refresh"))
        );
    }

    @Override
    public List<RequestRateLimitRule> rules() {
        return rules;
    }
}
