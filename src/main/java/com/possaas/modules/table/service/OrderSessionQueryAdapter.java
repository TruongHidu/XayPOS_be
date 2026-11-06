package com.possaas.modules.table.service;

import com.possaas.common.exception.ResourceNotFoundException;
import com.possaas.modules.table.application.port.OrderSessionQuery;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly=true)
public class OrderSessionQueryAdapter implements OrderSessionQuery {
    private final TableSessionRepository sessions;
    @Override public void requireOwned(UUID tenant, UUID id) {
        sessions.findByIdAndRestaurantId(id,tenant).orElseThrow(() ->
            new ResourceNotFoundException("TABLE_SESSION_NOT_FOUND","Session not found"));
    }
}
