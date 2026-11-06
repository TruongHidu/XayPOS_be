package com.possaas.modules.table.service;

import com.possaas.common.exception.ConflictException;
import com.possaas.modules.table.application.port.QrOrderSeatingQuery;
import com.possaas.modules.table.entity.TableSessionStatus;
import com.possaas.modules.table.repository.TableSessionRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class QrOrderSeatingQueryAdapter implements QrOrderSeatingQuery {
    private final TableSessionRepository sessions;
    @Override public Optional<CurrentSession> current(UUID tenant, UUID tableId) {
        var found = sessions.findAllByRestaurantIdAndTableIdAndStatus(tenant, tableId, TableSessionStatus.OPEN);
        if (found.size() > 1) throw new ConflictException("INCONSISTENT_TABLE_SESSION_STATE", "Table has inconsistent open sessions");
        return found.stream().findFirst().map(session -> new CurrentSession(session.getId(), session.getGuestCount(), session.getVersion()));
    }
}
