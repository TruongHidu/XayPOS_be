package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.StockAuditItem;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockAuditItemRepository extends JpaRepository<StockAuditItem,UUID> {
    List<StockAuditItem> findAllByRestaurantIdAndStockAuditIdOrderByIngredientId(UUID tenant,UUID audit);
    List<StockAuditItem> findAllByRestaurantIdAndStockAuditIdAndIdIn(UUID tenant,UUID audit,Collection<UUID> ids);
}
