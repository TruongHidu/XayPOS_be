package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.entity.InventoryTransaction;
import jakarta.persistence.EntityManager;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class InventoryTransactionWriteRepository {
    private final EntityManager entityManager;

    public void append(InventoryTransaction transaction) {
        entityManager.persist(transaction);
    }

    public void flush() {
        entityManager.flush();
    }

    public List<InventoryTransaction> findForReservations(UUID tenant, Collection<UUID> reservationIds) {
        if (reservationIds.isEmpty()) return List.of();
        return entityManager.createQuery("select t from InventoryTransaction t where t.restaurantId=:tenant "
                + "and t.reservationId in :reservations", InventoryTransaction.class)
                .setParameter("tenant", tenant).setParameter("reservations", reservationIds).getResultList();
    }
}
