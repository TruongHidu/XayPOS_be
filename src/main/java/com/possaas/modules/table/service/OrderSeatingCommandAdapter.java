package com.possaas.modules.table.service;

import com.possaas.modules.table.application.port.OrderSeatingCommand;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service @RequiredArgsConstructor
public class OrderSeatingCommandAdapter implements OrderSeatingCommand {
    private final TableSessionOpeningOperation opening;
    @Override @Transactional(propagation=Propagation.MANDATORY)
    public UUID open(UUID tenant,UUID tableId,short guests,Instant now,String ip) {
        return opening.open(tenant,tableId,(int)guests,null,now,ip).getId();
    }
}
