package com.possaas.modules.qrorder.repository;

import com.possaas.modules.qrorder.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QrGuestAuthorizationRepository extends JpaRepository<QrGuestAuthorization,UUID> {
    Optional<QrGuestAuthorization> findByTokenHash(String hash);
    Optional<QrGuestAuthorization> findByIdAndRestaurantId(UUID id,UUID tenant);
    List<QrGuestAuthorization> findAllByRestaurantIdAndTableIdAndRevokedAtIsNull(UUID tenant,UUID tableId);
    List<QrGuestAuthorization> findAllByRestaurantIdAndEntryTypeAndCredentialHashAndRevokedAtIsNull(UUID tenant,QrEntryType type,String credentialHash);
}
