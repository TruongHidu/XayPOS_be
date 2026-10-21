package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.AdjustmentDirection;
import com.possaas.modules.inventory.entity.StockDocumentItem;
import com.possaas.modules.inventory.service.strategy.AdjustmentStockMovementStrategy;
import com.possaas.modules.inventory.service.strategy.IssueStockMovementStrategy;
import com.possaas.modules.inventory.service.strategy.ReceiptStockMovementStrategy;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockMovementStrategyTest {
    @Test
    void strategiesCalculateSignedQuantityWithoutATypeSwitch() {
        StockDocumentItem plain = item(null);
        StockDocumentItem increase = item(AdjustmentDirection.INCREASE);
        StockDocumentItem decrease = item(AdjustmentDirection.DECREASE);

        assertThat(new ReceiptStockMovementStrategy().calculateQuantityChange(plain))
                .isEqualByComparingTo("2.500");
        assertThat(new IssueStockMovementStrategy().calculateQuantityChange(plain))
                .isEqualByComparingTo("-2.500");
        var adjustment = new AdjustmentStockMovementStrategy();
        assertThat(adjustment.calculateQuantityChange(increase)).isEqualByComparingTo("2.500");
        assertThat(adjustment.calculateQuantityChange(decrease)).isEqualByComparingTo("-2.500");
    }

    @Test
    void adjustmentDirectionBelongsOnlyToAdjustmentDocuments() {
        assertThatThrownBy(() -> new ReceiptStockMovementStrategy().validateLine(item(AdjustmentDirection.INCREASE)))
                .isInstanceOf(BusinessException.class).extracting("code")
                .isEqualTo("INVALID_ADJUSTMENT_DIRECTION");
        assertThatThrownBy(() -> new AdjustmentStockMovementStrategy().validateLine(item(null)))
                .isInstanceOf(BusinessException.class).extracting("code")
                .isEqualTo("INVALID_ADJUSTMENT_DIRECTION");
    }

    private static StockDocumentItem item(AdjustmentDirection direction) {
        StockDocumentItem item = new StockDocumentItem();
        item.initialize(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SKU", "Ingredient", "g",
                new BigDecimal("2.500"), direction, new BigDecimal("10.00"), null, Instant.EPOCH);
        return item;
    }
}
