package com.possaas.modules.subscription.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.audit.service.AuditService;
import com.possaas.modules.subscription.dto.CreateFeatureRequest;
import com.possaas.modules.subscription.dto.FeatureResponse;
import com.possaas.modules.subscription.dto.UpdateFeatureRequest;
import com.possaas.modules.subscription.entity.Feature;
import com.possaas.modules.subscription.mapper.PackageMapper;
import com.possaas.modules.subscription.repository.FeatureRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class FeatureAdminService {
    private final FeatureRepository featureRepository;
    private final PackageMapper packageMapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public List<FeatureResponse> findAll(boolean includeInactive) {
        List<Feature> features = includeInactive
            ? featureRepository.findAllByOrderByCode()
            : featureRepository.findAllByActiveTrueOrderByCode();
        return features.stream().map(packageMapper::toResponse).toList();
    }

    @Transactional
    public FeatureResponse create(CreateFeatureRequest request, UUID actorUserId, String ipAddress) {
        String code = normalizeCode(request.code());
        if (featureRepository.findByCode(code).isPresent()) {
            throw new BusinessException(HttpStatus.CONFLICT, "FEATURE_ALREADY_EXISTS", "Feature already exists");
        }
        Feature feature = new Feature();
        feature.setCode(code);
        feature.setName(request.name().trim());
        feature.setDescription(request.description());
        feature = featureRepository.saveAndFlush(feature);
        auditService.record(null, actorUserId, "FEATURE_CREATED", "features", feature.getId(),
            null, featureState(feature), ipAddress);
        return packageMapper.toResponse(feature);
    }

    @Transactional
    public FeatureResponse update(
        String featureCode,
        UpdateFeatureRequest request,
        UUID actorUserId,
        String ipAddress
    ) {
        Feature feature = requireFeature(featureCode);
        Map<String, Object> before = featureState(feature);
        feature.setName(request.name().trim());
        feature.setDescription(request.description());
        feature.setActive(request.active());
        feature = featureRepository.saveAndFlush(feature);
        auditService.record(null, actorUserId, "FEATURE_UPDATED", "features", feature.getId(),
            before, featureState(feature), ipAddress);
        return packageMapper.toResponse(feature);
    }

    private Feature requireFeature(String featureCode) {
        return featureRepository.findByCode(normalizeCode(featureCode))
            .orElseThrow(() -> new ResourceNotFoundException("FEATURE_NOT_FOUND", "Feature not found"));
    }

    private static Map<String, Object> featureState(Feature feature) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("code", feature.getCode());
        state.put("name", feature.getName());
        state.put("active", feature.isActive());
        return state;
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
