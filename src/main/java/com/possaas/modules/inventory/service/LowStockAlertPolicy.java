package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.*;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
public class LowStockAlertPolicy {
    public boolean low(BigDecimal physical,BigDecimal threshold) {
        return threshold.signum()>0 && physical.compareTo(threshold)<=0;
    }

    public LowStockAlertResolutionReason resolution(boolean warehouseDeleted,boolean warehouseActive,
            boolean ingredientDeleted,boolean tracked,boolean sameUnit,BigDecimal quantity,BigDecimal threshold) {
        if (warehouseDeleted) return LowStockAlertResolutionReason.WAREHOUSE_DELETED;
        if (!warehouseActive) return LowStockAlertResolutionReason.WAREHOUSE_INACTIVE;
        if (ingredientDeleted) return LowStockAlertResolutionReason.INGREDIENT_DELETED;
        if (!tracked) return LowStockAlertResolutionReason.INGREDIENT_UNTRACKED;
        if (!sameUnit) return LowStockAlertResolutionReason.INGREDIENT_UNIT_CHANGED;
        if (threshold.signum()==0) return LowStockAlertResolutionReason.THRESHOLD_DISABLED;
        return low(quantity,threshold) ? null : LowStockAlertResolutionReason.STOCK_RECOVERED;
    }

    /** Returns false for desired-state replay, before checking its old version. */
    public boolean requireAcknowledge(LowStockAlert alert,long expectedVersion) {
        if (alert.getStatus()==LowStockAlertStatus.ACKNOWLEDGED) return false;
        if (alert.getStatus()!=LowStockAlertStatus.OPEN)
            throw new ConflictException("INVALID_LOW_STOCK_ALERT_TRANSITION","Resolved alerts cannot be acknowledged");
        if (alert.getVersion()!=expectedVersion)
            throw new ConflictException("CONCURRENT_LOW_STOCK_ALERT_UPDATE","Alert changed; reload before retrying");
        return true;
    }
}
