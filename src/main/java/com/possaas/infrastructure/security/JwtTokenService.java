package com.possaas.infrastructure.security;

import com.possaas.common.security.CurrentUser;
import com.possaas.config.JwtProperties;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class JwtTokenService {
    private final JwtEncoder encoder;
    private final JwtProperties properties;

    public String createAccessToken(CurrentUser user) {
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
            .issuer("pos-saas-be")
            .subject(user.userId().toString())
            .issuedAt(now)
            .expiresAt(now.plus(properties.getAccessTokenTtl()))
            .id(UUID.randomUUID().toString())
            .claim("email", user.email())
            .claim("role", user.role())
            .claim("permissions", user.permissions());
        if (user.restaurantId() != null) {
            claims.claim("restaurant_id", user.restaurantId().toString());
        }
        return encoder.encode(JwtEncoderParameters.from(
            JwsHeader.with(MacAlgorithm.HS256).build(), claims.build())).getTokenValue();
    }

    public long accessTokenExpiresInSeconds() {
        return properties.getAccessTokenTtl().toSeconds();
    }
}
