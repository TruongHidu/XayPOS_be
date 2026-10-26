package com.possaas.modules.inventory.service;

import com.possaas.common.security.CurrentUser;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.inventory.repository.*;
import com.possaas.modules.inventory.repository.LowStockAlertBalanceQuery.Key;
import com.possaas.modules.inventory.repository.LowStockAlertBalanceQuery.Balance;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Caller holds the restaurant mutation lock. Internal maintenance never checks entitlement. */
@Service @RequiredArgsConstructor @Transactional(propagation=Propagation.MANDATORY)
public class LowStockAlertReconciliationService {
    private final LowStockAlertBalanceQuery balances;
    private final LowStockAlertRepository alerts;
    private final LowStockAlertPolicy policy;
    private final LowStockAlertPersistence persistence;
    private final LowStockAlertAudit audit;
    private final LowStockAlertEventPublisher realtime;
    private final EntityManager entityManager;
    private final Clock clock;

    public void changed(CurrentUser actor,Collection<InventoryBalance> changed,String ip) {
        Set<Key> keys=changed.stream().map(b->new Key(b.getWarehouseId(),b.getItemId())).collect(Collectors.toSet());
        if (keys.isEmpty()) return;
        entityManager.flush(); // Native JDBC projection must observe this transaction's final JPA changes.
        var current=balances.find(actor.restaurantId(),keys.stream().map(Key::warehouseId).distinct().toList(),
            keys.stream().map(Key::ingredientId).distinct().toList()).stream().filter(b->keys.contains(b.key())).toList();
        reconcile(actor,current,ip);
    }

    public void warehouseChanged(CurrentUser actor,UUID warehouseId,String ip) {
        entityManager.flush();reconcile(actor,balances.find(actor.restaurantId(),List.of(warehouseId),List.of()),ip);
    }

    public void ingredientChanged(CurrentUser actor,UUID ingredientId,String ip) {
        entityManager.flush();reconcile(actor,balances.find(actor.restaurantId(),List.of(),List.of(ingredientId)),ip);
    }

    private void reconcile(CurrentUser actor,List<Balance> current,String ip) {
        if (current.isEmpty()) return;
        Set<Key> keys=current.stream().map(Balance::key).collect(Collectors.toSet());
        Map<Key,LowStockAlert> existing=alerts.findUnresolvedForUpdate(actor.restaurantId(),
            current.stream().map(Balance::warehouseId).distinct().toList(),
            current.stream().map(Balance::ingredientId).distinct().toList()).stream()
            .filter(a->keys.contains(new Key(a.getWarehouseId(),a.getItemId())))
            .collect(Collectors.toMap(a->new Key(a.getWarehouseId(),a.getItemId()),Function.identity()));
        var now=clock.instant();
        List<LowStockAlert> resolved=new ArrayList<>(),created=new ArrayList<>();
        Map<UUID,Map<String,Object>> before=new HashMap<>();
        for (Balance balance:current) {
            LowStockAlert alert=existing.get(balance.key());
            if (alert!=null) {
                var reason=policy.resolution(balance.warehouseDeleted(),balance.warehouseActive(),
                    balance.ingredientDeleted(),balance.tracked(),alert.getUnit().equals(balance.unit()),
                    balance.quantity(),balance.threshold());
                if (reason!=null) {
                    before.put(alert.getId(),audit.snapshot(alert));alert.resolve(reason,now);resolved.add(alert);alert=null;
                }
            }
            if (alert==null && balance.eligible() && policy.low(balance.quantity(),balance.threshold()))
                created.add(new LowStockAlert(actor.restaurantId(),balance.warehouseId(),balance.ingredientId(),
                    balance.warehouseCode(),balance.warehouseName(),balance.sku(),balance.name(),balance.unit(),
                    balance.quantity(),balance.threshold(),now));
        }
        // Release the partial unique slot before opening a new-unit episode.
        persistence.save(resolved);persistence.save(created);
        List<LowStockAlert> changed=new ArrayList<>(resolved);changed.addAll(created);
        if (!changed.isEmpty()) {
            audit.transitions(actor.userId(),changed,before,ip);
            realtime.publish(changed);
        }
    }
}
