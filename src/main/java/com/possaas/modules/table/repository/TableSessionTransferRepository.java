package com.possaas.modules.table.repository;

import com.possaas.modules.table.entity.TableSessionTransfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TableSessionTransferRepository extends JpaRepository<TableSessionTransfer, UUID> {
    Optional<TableSessionTransfer> findByRestaurantIdAndIdempotencyKey(UUID tenant, String key);
    Page<TableSessionTransfer> findAllByRestaurantIdAndSessionId(UUID tenant, UUID sessionId, Pageable pageable);
}
