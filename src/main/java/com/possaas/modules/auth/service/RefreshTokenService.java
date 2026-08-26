package com.possaas.modules.auth.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.config.JwtProperties;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.auth.entity.AuthRefreshToken;
import com.possaas.modules.auth.repository.AuthRefreshTokenRepository;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final AuthRefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final AuditService auditService;

    @Transactional
    public IssuedRefreshToken issue(UUID userId, String deviceInfo, String ipAddress) {
        String rawToken = generateRawToken();
        AuthRefreshToken token = new AuthRefreshToken();
        token.setUserId(userId);
        token.setTokenHash(hash(rawToken));
        token.setExpiresAt(Instant.now().plus(jwtProperties.getRefreshTokenTtl()));
        token.setDeviceInfo(deviceInfo);
        token.setIpAddress(parseIpAddress(ipAddress));
        AuthRefreshToken savedToken = refreshTokenRepository.save(token);
        return new IssuedRefreshToken(rawToken, savedToken);
    }

    @Transactional(noRollbackFor = BusinessException.class)
    public AuthRefreshToken requireUsableForRotation(String rawToken, String ipAddress) {
        AuthRefreshToken token = refreshTokenRepository.findByTokenHash(hash(rawToken))
            .orElseThrow(RefreshTokenService::invalidRefreshToken);

        if (token.getRevokedAt() != null) {
            refreshTokenRepository.revokeAllActive(token.getUserId(), Instant.now());
            auditService.record(
                null,
                token.getUserId(),
                "REFRESH_TOKEN_REUSE_DETECTED",
                "auth_refresh_tokens",
                token.getId(),
                null,
                null,
                ipAddress
            );
            throw invalidRefreshToken();
        }
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw invalidRefreshToken();
        }
        return token;
    }

    @Transactional
    public void revoke(AuthRefreshToken token) {
        if (token.getRevokedAt() == null) {
            token.setRevokedAt(Instant.now());
            refreshTokenRepository.save(token);
        }
    }

    @Transactional
    public void revokeOwnedToken(UUID userId, String rawToken) {
        refreshTokenRepository.findByTokenHash(hash(rawToken))
            .filter(token -> token.getUserId().equals(userId))
            .ifPresent(this::revoke);
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static InetAddress parseIpAddress(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return null;
        }
        try {
            return InetAddress.getByName(ipAddress);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static BusinessException invalidRefreshToken() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "INVALID_REFRESH_TOKEN", "Invalid refresh token");
    }

    public record IssuedRefreshToken(String rawToken, AuthRefreshToken entity) {}
}
