package com.possaas.modules.table.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.possaas.common.exception.BusinessException;
import com.possaas.common.exception.ConflictException;
import com.possaas.modules.table.dto.TableRequests;
import com.possaas.modules.table.repository.TableSessionTransferRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class TableSessionTransferIdempotency {
    private final ObjectMapper mapper;
    private final TableSessionTransferRepository transfers;

    public String key(String key) {
        if (key == null || key.isBlank() || key.length() > 100)
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key is required and must be at most 100 characters");
        return key;
    }

    public String hash(UUID actor, UUID session, TableRequests.TransferSession request) {
        var projection = new LinkedHashMap<String, Object>();
        projection.put("operation", "table-session-transfer-v1");
        projection.put("actorUserId", actor); projection.put("sessionId", session);
        projection.put("targetTableId", request.targetTableId());
        projection.put("expectedVersion", request.expectedVersion()); projection.put("reason", request.reason());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(mapper.writeValueAsString(projection).getBytes(StandardCharsets.UTF_8)));
        } catch (java.io.IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Could not fingerprint table transfer request");
        }
    }

    public boolean replay(UUID tenant, UUID session, UUID actor, String key, String hash) {
        var existing = transfers.findByRestaurantIdAndIdempotencyKey(tenant, key);
        if (existing.isEmpty()) return false;
        var transfer = existing.get();
        if (!session.equals(transfer.getSessionId()) || !actor.equals(transfer.getActorUserId())
                || !hash.equals(transfer.getRequestHash()))
            throw new ConflictException("IDEMPOTENCY_KEY_REUSED", "Transfer key was used for a different request or actor");
        return true;
    }
}
