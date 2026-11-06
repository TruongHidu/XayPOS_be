package com.possaas.modules.auth.service;
import com.possaas.modules.user.application.port.UserSessionRevoker;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
@Component
@RequiredArgsConstructor
public class RefreshTokenUserSessionRevoker implements UserSessionRevoker {
    private final RefreshTokenService refreshTokens;
    @Override public int revokeAllActiveForUser(UUID userId) { return refreshTokens.revokeAllActiveForUser(userId); }
}
