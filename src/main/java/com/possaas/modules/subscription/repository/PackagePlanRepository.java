package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.PackagePlan;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackagePlanRepository extends JpaRepository<PackagePlan, UUID> {
    Optional<PackagePlan> findByCode(String code);

    Optional<PackagePlan> findByCodeAndActiveTrue(String code);

    List<PackagePlan> findAllByOrderByPriceAmountAsc();

    List<PackagePlan> findAllByActiveTrueOrderByPriceAmountAsc();
}
