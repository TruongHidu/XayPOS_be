package com.possaas.modules.qrorder.repository;

import com.possaas.modules.qrorder.entity.QrOrderRequestItem;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QrOrderRequestItemRepository extends JpaRepository<QrOrderRequestItem,UUID> {
    List<QrOrderRequestItem> findAllByRestaurantIdAndRequestIdOrderByLineNumberAsc(UUID tenant,UUID requestId);
    List<QrOrderRequestItem> findAllByRestaurantIdAndRequestIdInOrderByLineNumberAsc(UUID tenant,Collection<UUID> requestIds);
}
