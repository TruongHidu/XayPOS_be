package com.possaas.modules.audit.service;

import com.possaas.common.exception.BusinessException;
import com.possaas.modules.audit.dto.AuditLogPageResponse;
import com.possaas.modules.audit.dto.AuditLogResponse;
import com.possaas.modules.audit.mapper.AuditLogResponseMapper;
import com.possaas.modules.audit.query.AuditLogCriteria;
import com.possaas.modules.audit.query.AuditScope;
import com.possaas.modules.audit.repository.AuditLogQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditQueryService {
    private final AuditLogQueryRepository auditLogQueryRepository;
    private final AuditLogResponseMapper auditLogResponseMapper;

    @Transactional(readOnly = true)
    public AuditLogPageResponse search(AuditLogCriteria criteria, int page, int size) {
        validate(criteria);
        Page<AuditLogResponse> responses = auditLogQueryRepository
            .search(criteria, PageRequest.of(page, size))
            .map(auditLogResponseMapper::toResponse);
        return AuditLogPageResponse.from(responses);
    }

    private void validate(AuditLogCriteria criteria) {
        if (criteria.from() != null
            && criteria.to() != null
            && !criteria.to().isAfter(criteria.from())) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_AUDIT_PERIOD",
                "Audit period end must be after its start"
            );
        }
        if (criteria.scope() == AuditScope.SYSTEM && criteria.restaurantId() != null) {
            throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "INVALID_AUDIT_FILTER",
                "A restaurant filter cannot be used with SYSTEM scope"
            );
        }
    }
}
