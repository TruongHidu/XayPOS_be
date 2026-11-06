package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.subscription.dto.PackageFeatureSelectionRequest;
import com.possaas.modules.subscription.entity.Feature;
import com.possaas.modules.subscription.entity.PackageFeature;
import com.possaas.modules.subscription.entity.PackageFeatureId;
import com.possaas.modules.subscription.repository.FeatureRepository;
import com.possaas.modules.subscription.repository.PackageFeatureRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PackageFeatureAssignmentService {
    private final FeatureRepository features;
    private final PackageFeatureRepository mappings;
    private final PackageFeatureSelectionPolicy policy;

    public record Assignment(Feature feature, Map<String, Object> limits) {}

    public List<Assignment> prepare(List<PackageFeatureSelectionRequest> requests) {
        var selected = policy.validate(requests);
        if (selected.isEmpty()) return List.of();
        var catalog = features.findAllByCodeIn(selected.stream().map(PackageFeatureSelectionRequest::code).toList())
            .stream().collect(Collectors.toMap(Feature::getCode, Function.identity()));
        return selected.stream().map(request -> {
            Feature feature = catalog.get(request.code());
            if (feature == null) throw new ResourceNotFoundException("FEATURE_NOT_FOUND", "Feature not found: " + request.code());
            if (!feature.isActive()) throw new BusinessException(HttpStatus.BAD_REQUEST, "FEATURE_DISABLED", "Feature is inactive: " + request.code());
            return new Assignment(feature, request.limits());
        }).toList();
    }

    /** Caller owns the package lock (or is creating a new package). */
    public boolean replace(UUID packageId, List<Assignment> selected) {
        var existing = mappings.findAllByIdPackageId(packageId).stream()
            .collect(Collectors.toMap(row -> row.getId().getFeatureId(), Function.identity()));
        boolean changed = false;
        for (var assignment : selected) {
            UUID featureId = assignment.feature().getId();
            PackageFeature mapping = existing.remove(featureId);
            if (mapping == null) {
                mapping = new PackageFeature();
                mapping.setId(new PackageFeatureId(packageId, featureId));
                mapping.setLimits(assignment.limits());
                mappings.save(mapping);
                changed = true;
            } else if (!policy.sameLimits(assignment.feature().getCode(), mapping.getLimits(), assignment.limits())) {
                mapping.setLimits(assignment.limits());
                changed = true;
            }
        }
        if (!existing.isEmpty()) {
            mappings.deleteAll(existing.values());
            changed = true;
        }
        if (changed) mappings.flush();
        return changed;
    }
}
