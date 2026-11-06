package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.PackagePlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PackagePlanRepository extends JpaRepository<PackagePlan, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PackagePlan p where p.id = :id")
    Optional<PackagePlan> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PackagePlan p where p.code = :code")
    Optional<PackagePlan> findByCodeForUpdate(@Param("code") String code);

    Optional<PackagePlan> findByCode(String code);

    Optional<PackagePlan> findByCodeAndActiveTrue(String code);

    List<PackagePlan> findAllByOrderByPriceAmountAsc();

    List<PackagePlan> findAllByActiveTrueOrderByPriceAmountAsc();
}
