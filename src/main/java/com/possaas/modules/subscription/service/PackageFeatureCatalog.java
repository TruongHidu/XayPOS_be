package com.possaas.modules.subscription.service;

import com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshot;
import com.possaas.modules.subscription.entity.Feature;
import com.possaas.modules.subscription.entity.PackageFeature;
import com.possaas.modules.subscription.repository.FeatureRepository;
import com.possaas.modules.subscription.repository.PackageFeatureRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PackageFeatureCatalog {
    private final PackageFeatureRepository packageFeatureRepository;
    private final FeatureRepository featureRepository;

    @Transactional(readOnly = true)
    public List<SubscriptionFeatureSnapshot.FeatureGrant> getActiveFeatures(UUID packageId) {
        return getActiveFeatures(Set.of(packageId)).getOrDefault(packageId, List.of());
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> getActiveFeatures(
        Collection<UUID> packageIds
    ) {
        return loadFeatures(packageIds, true);
    }

    @Transactional(readOnly = true)
    public Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> getAllFeatures(
        Collection<UUID> packageIds
    ) {
        return loadFeatures(packageIds, false);
    }

    private Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> loadFeatures(
        Collection<UUID> packageIds,
        boolean activeOnly
    ) {
        if (packageIds.isEmpty()) {
            return Map.of();
        }
        List<PackageFeature> mappings = packageFeatureRepository.findAllByIdPackageIdIn(packageIds);
        Set<UUID> featureIds = mappings.stream()
            .map(mapping -> mapping.getId().getFeatureId())
            .collect(Collectors.toSet());
        List<Feature> featureList = activeOnly
            ? featureRepository.findAllByIdInAndActiveTrue(featureIds)
            : featureRepository.findAllById(featureIds);
        Map<UUID, Feature> availableFeatures = featureList.stream()
            .collect(Collectors.toMap(Feature::getId, Function.identity()));
        Map<UUID, List<SubscriptionFeatureSnapshot.FeatureGrant>> result = new HashMap<>();

        for (UUID packageId : packageIds) {
            List<SubscriptionFeatureSnapshot.FeatureGrant> grants = mappings.stream()
                .filter(mapping -> mapping.getId().getPackageId().equals(packageId))
                .filter(mapping -> availableFeatures.containsKey(mapping.getId().getFeatureId()))
                .map(mapping -> new SubscriptionFeatureSnapshot.FeatureGrant(
                    availableFeatures.get(mapping.getId().getFeatureId()).getCode(),
                    mapping.getLimits()
                ))
                .sorted(java.util.Comparator.comparing(SubscriptionFeatureSnapshot.FeatureGrant::code))
                .toList();
            result.put(packageId, grants);
        }
        return Map.copyOf(result);
    }
}
