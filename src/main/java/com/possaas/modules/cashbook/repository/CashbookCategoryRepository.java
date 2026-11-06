package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.entity.CashbookCategory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CashbookCategoryRepository extends JpaRepository<CashbookCategory,UUID> {
    Optional<CashbookCategory> findByCodeAndTypeAndActiveTrue(String code,String type);
}
