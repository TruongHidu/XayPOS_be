package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.Feature;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeatureRepository extends JpaRepository<Feature, UUID> {
    Optional<Feature> findByCode(String code);

    Optional<Feature> findByCodeAndActiveTrue(String code);

    List<Feature> findAllByOrderByCode();

    List<Feature> findAllByActiveTrueOrderByCode();

    List<Feature> findAllByIdInAndActiveTrue(Collection<UUID> ids);
}
