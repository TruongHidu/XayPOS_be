package com.possaas.modules.inventory.service.strategy;

import com.possaas.modules.inventory.entity.StockDocumentType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StockMovementStrategyRegistry {
    private final Map<StockDocumentType, StockMovementStrategy> strategies;

    public StockMovementStrategyRegistry(List<StockMovementStrategy> values) {
        var map = new EnumMap<StockDocumentType, StockMovementStrategy>(StockDocumentType.class);
        values.forEach(value -> {
            if (map.put(value.supportedType(), value) != null)
                throw new IllegalStateException("Duplicate stock strategy: " + value.supportedType());
        });
        this.strategies = Map.copyOf(map);
    }

    public StockMovementStrategy get(StockDocumentType type) {
        StockMovementStrategy strategy = strategies.get(type);
        if (strategy == null) throw new IllegalStateException("Missing stock strategy: " + type);
        return strategy;
    }
}
