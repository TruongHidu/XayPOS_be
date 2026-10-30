package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
public interface CashbookExpenseRepository extends JpaRepository<CashbookExpense,UUID> {
    Optional<CashbookExpense> findByRestaurantIdAndIdempotencyKey(UUID restaurantId,String idempotencyKey);
    Optional<CashbookExpense> findByIdAndRestaurantId(UUID id,UUID restaurantId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from CashbookExpense e where e.restaurantId=:restaurantId and e.id=:id")
    Optional<CashbookExpense> findForUpdate(@Param("restaurantId") UUID restaurantId,@Param("id") UUID id);
}
