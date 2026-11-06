package com.possaas.modules.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.order.dto.OrderRequests;
import com.possaas.modules.order.entity.Order;
import com.possaas.modules.order.entity.*;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class OrderIdempotencyService {
    private final ObjectMapper mapper;
    public String key(String key) {
        if(key==null || key.isBlank() || key.length()>100) throw new BusinessException(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY","Idempotency-Key is required and must be at most 100 characters");
        return key;
    }
    public String hash(UUID actor,OrderRequests.Create normalized) {
        if(normalized.tableId()!=null)
            return fingerprint(actor,new TableSubmittedCreate("create-table-submit-v1",normalized.tableId(),
                normalized.serviceType(),normalized.sourceChannel(),normalized.effectiveMode(),normalized.guestCount(),
                normalized.customerName(),normalized.customerPhone(),normalized.note(),normalized.items()));
        // This exact eight-field projection preserves pre-submissionMode JSON hashes.
        var legacy=new LegacyCreate(normalized.serviceType(),normalized.sourceChannel(),normalized.tableSessionId(),
                normalized.guestCount(),normalized.customerName(),normalized.customerPhone(),normalized.note(),normalized.items());
        return fingerprint(actor,normalized.effectiveMode()==OrderSubmissionMode.DRAFT?legacy:new SubmittedCreate("create-submit-v1",legacy));
    }
    public String appendHash(UUID actor,UUID orderId,OrderRequests.AddItems normalized) {
        return fingerprint(actor,new AppendRequest("append-submit-v1",orderId,normalized.expectedVersion(),normalized.effectiveMode(),normalized.items()));
    }
    private String fingerprint(UUID actor,Object request) {
        try {
            String payload=actor+"\n"+mapper.writeValueAsString(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch(java.io.IOException|NoSuchAlgorithmException failure) { throw new IllegalStateException("Could not fingerprint order request"); }
    }
    private record LegacyCreate(ServiceType serviceType,SourceChannel sourceChannel,UUID tableSessionId,Integer guestCount,
                                String customerName,String customerPhone,String note,List<OrderRequests.Line> items) {}
    private record SubmittedCreate(String operation,LegacyCreate request) {}
    private record TableSubmittedCreate(String operation,UUID tableId,ServiceType serviceType,SourceChannel sourceChannel,
                                        OrderSubmissionMode submissionMode,Integer guestCount,String customerName,
                                        String customerPhone,String note,List<OrderRequests.Line> items) {}
    private record AppendRequest(String operation,UUID orderId,Long expectedVersion,OrderSubmissionMode submissionMode,List<OrderRequests.Line> items) {}
    public void requireReplayMatch(Order order,UUID actor,String hash) {
        if(!actor.equals(order.getCreatedBy()) || !hash.equals(order.getRequestHash()))
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED","Idempotency key was already used for a different request or actor");
    }
}
