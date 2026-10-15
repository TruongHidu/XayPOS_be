package com.possaas.modules.qrorder.dto;

import com.possaas.modules.qrorder.entity.QrEntryType;
import com.possaas.modules.qrorder.entity.QrRequestStatus;
import jakarta.validation.constraints.*;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

@Getter @Setter
public class QrStaffSearch {
    private QrEntryType entryType;
    private QrRequestStatus status;
    private UUID tableId;
    private UUID observedSessionId;
    private boolean includeExpired;
    @Min(0) private int page;
    @Min(1) @Max(100) private int size = 20;
    public Pageable pageable() { return PageRequest.of(page, size, Sort.by(Sort.Order.asc("createdAt"), Sort.Order.asc("id"))); }
}
