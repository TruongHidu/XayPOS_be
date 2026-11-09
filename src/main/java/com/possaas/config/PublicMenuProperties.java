package com.possaas.config;

import org.springframework.beans.factory.InitializingBean;
import java.net.URI;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Frontend origin that serves the public menu routes; encoded into printed table/restaurant QR codes. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.public-menu")
public class PublicMenuProperties implements InitializingBean {
    private String baseUrl = "http://localhost:3000";
    private boolean requireHttps;

    @Override
    public void afterPropertiesSet() {
        URI uri;
        try {
            uri = URI.create(baseUrl == null ? "" : baseUrl.trim());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("app.public-menu.base-url must be an absolute http(s) URL");
        }
        String scheme = uri.getScheme();
        boolean http = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (!http || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalStateException("app.public-menu.base-url must be an absolute http(s) URL without query or fragment");
        if (requireHttps && !"https".equalsIgnoreCase(scheme))
            throw new IllegalStateException("app.public-menu.base-url must use https");
    }

    /** Joins the configured origin with a relative frontend path such as /menu/qr/{token}. */
    public String absolute(String path) {
        String base = baseUrl.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + (path.startsWith("/") ? path : "/" + path);
    }
}
