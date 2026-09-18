package com.possaas.modules.audit.repository;

import com.possaas.modules.audit.query.AuditLogCriteria;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AuditLogQueryRepository {
    Page<AuditLogQueryRow> search(AuditLogCriteria criteria, Pageable pageable);
}
