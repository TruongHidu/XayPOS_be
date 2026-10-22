package com.possaas.modules.inventory;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.possaas.common.exception.BusinessException;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.LowStockAlertRepository;
import com.possaas.modules.inventory.service.LowStockAlertPersistence;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

class LowStockAlertPersistenceTest {
    final LowStockAlertRepository repository=mock(LowStockAlertRepository.class);
    final LowStockAlertPersistence persistence=new LowStockAlertPersistence(repository);
    @Test void recognizedUniqueConflictBecomesStableBusinessErrorAtWriteBoundary() {
        doThrow(new DataIntegrityViolationException("conflict",new SQLException(
            "duplicate key violates ux_low_stock_alert_unresolved","23505"))).when(repository).saveAllAndFlush(any());
        assertThatThrownBy(()->persistence.save(List.of(alert()))).isInstanceOf(BusinessException.class)
            .extracting("code").isEqualTo("CONCURRENT_LOW_STOCK_ALERT_UPDATE");
    }
    @Test void unknownIntegrityFailureIsNotMisclassifiedOrSwallowed() {
        var failure=new DataIntegrityViolationException("other constraint",new SQLException("unknown_fk","23503"));
        doThrow(failure).when(repository).saveAllAndFlush(any());
        assertThatThrownBy(()->persistence.save(List.of(alert()))).isSameAs(failure);
    }
    @Test void optimisticMappingOnlyRecognizesTheAlertEntity() {
        assertThat(LowStockAlertPersistence.isAlertConflict(new ObjectOptimisticLockingFailureException(LowStockAlert.class,UUID.randomUUID()))).isTrue();
        assertThat(LowStockAlertPersistence.isAlertConflict(new ObjectOptimisticLockingFailureException(InventoryBalance.class,UUID.randomUUID()))).isFalse();
    }
    @Test void noChangeDoesNotFlush() {persistence.save(List.of());verifyNoInteractions(repository);}
    LowStockAlert alert() {return new LowStockAlert(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
        "MAIN","Main",null,"Ingredient","g",BigDecimal.ONE,BigDecimal.TEN,Instant.parse("2026-10-21T08:00:00Z"));}
}
