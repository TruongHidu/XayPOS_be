package com.possaas.modules.auth.repository;

import com.possaas.modules.auth.entity.AuthRefreshToken;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, UUID> {
    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update AuthRefreshToken t set t.revokedAt = :now where t.userId = :userId and t.revokedAt is null")
    int revokeAllActive(@Param("userId") UUID userId, @Param("now") Instant now);
}
