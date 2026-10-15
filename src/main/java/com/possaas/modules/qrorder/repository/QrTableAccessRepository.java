package com.possaas.modules.qrorder.repository;

import com.possaas.modules.qrorder.entity.QrTableAccess;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QrTableAccessRepository extends JpaRepository<QrTableAccess,UUID> {
    Optional<QrTableAccess> findByTableIdAndRestaurantId(UUID tableId,UUID tenant);
    List<QrTableAccess> findAllByRestaurantIdAndTableIdIn(UUID tenant,Collection<UUID> tableIds);
}
