package com.possaas.modules.audit.dto;

import java.util.List;
import org.springframework.data.domain.Page;

public record AuditLogPageResponse(
    List<AuditLogResponse> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static AuditLogPageResponse from(Page<AuditLogResponse> source) {
        return new AuditLogPageResponse(
            source.getContent(),
            source.getNumber(),
            source.getSize(),
            source.getTotalElements(),
            source.getTotalPages()
        );
    }
}
