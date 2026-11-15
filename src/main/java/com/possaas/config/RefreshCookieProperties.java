package com.possaas.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** HttpOnly cookie that carries the refresh token for browser clients (opt-in via X-Auth-Transport: cookie). */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.auth.refresh-cookie")
public class RefreshCookieProperties {
    private String name = "xaypos_rt";
    /** Only the auth endpoints ever receive the cookie. */
    private String path = "/api/v1/auth";
    /** Must stay true outside local http development. */
    private boolean secure = true;
    private String sameSite = "Strict";
}
