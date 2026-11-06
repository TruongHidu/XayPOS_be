package com.possaas.modules.qrorder.repository;

import com.possaas.modules.qrorder.entity.*;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface QrOrderRequestRepository extends JpaRepository<QrOrderRequest,UUID>,JpaSpecificationExecutor<QrOrderRequest> {
    Optional<QrOrderRequest> findByIdAndRestaurantId(UUID id,UUID tenant);
    Optional<QrOrderRequest> findByRestaurantIdAndIdempotencyKey(UUID tenant,String key);
    List<QrOrderRequest> findAllByRestaurantIdAndStatus(UUID tenant,QrRequestStatus status);
    List<QrOrderRequest> findAllByRestaurantIdAndTableIdAndStatus(UUID tenant,UUID tableId,QrRequestStatus status);
    List<QrOrderRequest> findAllByRestaurantIdAndObservedSessionIdAndStatus(UUID tenant,UUID sessionId,QrRequestStatus status);
    List<QrOrderRequest> findAllByRestaurantIdAndEntryTypeAndCredentialHashAndStatus(UUID tenant,QrEntryType type,String credentialHash,QrRequestStatus status);
}
