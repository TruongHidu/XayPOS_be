package com.possaas.modules.subscription.repository;

import com.possaas.modules.subscription.entity.PackageFeature;
import com.possaas.modules.subscription.entity.PackageFeatureId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PackageFeatureRepository extends JpaRepository<PackageFeature, PackageFeatureId> {
    List<PackageFeature> findAllByIdPackageId(UUID packageId);

    List<PackageFeature> findAllByIdPackageIdIn(Collection<UUID> packageIds);
}
