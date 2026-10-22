package com.possaas.modules.inventory.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.inventory.entity.LowStockAlert;
import com.possaas.modules.inventory.repository.LowStockAlertRepository;
import java.sql.SQLException;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Translate at the write boundary, including writes reached via ORDER, QR and MENU. */
@Component @RequiredArgsConstructor
public class LowStockAlertPersistence {
    private final LowStockAlertRepository alerts;

    public void save(Collection<LowStockAlert> changed) {
        if (changed.isEmpty()) return;
        try {alerts.saveAllAndFlush(changed);}
        catch (RuntimeException failure) {
            if (isAlertConflict(failure)) throw concurrent();
            throw failure;
        }
    }

    public static boolean isAlertConflict(Throwable failure) {
        for (Throwable cause=failure;cause!=null;cause=cause.getCause()) {
            if (cause instanceof ConstraintViolationException constraint && named(constraint.getConstraintName())) return true;
            if (cause instanceof SQLException sql && sql.getSQLState()!=null
                    && sql.getSQLState().startsWith("23") && named(sql.getMessage())) return true;
            if (cause instanceof ObjectOptimisticLockingFailureException optimistic
                    && LowStockAlert.class.getName().equals(optimistic.getPersistentClassName())) return true;
        }
        return false;
    }

    private static boolean named(String value) {
        return value!=null && (value.contains("ux_low_stock_alert_unresolved")
                || value.contains("ck_low_stock_alert_") || value.contains("fk_low_stock_alert_"));
    }

    public static ConflictException concurrent() {
        return new ConflictException("CONCURRENT_LOW_STOCK_ALERT_UPDATE","Low stock alert changed; reload before retrying");
    }
}
