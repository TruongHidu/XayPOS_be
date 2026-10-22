package com.possaas.modules.inventory.repository;

import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class StockAuditMovementQuery {
    private final NamedParameterJdbcTemplate jdbc;
    public Map<UUID,Long> counts(UUID tenant,UUID warehouse,Collection<UUID> ingredients) {
        if(ingredients.isEmpty())return Map.of();
        Map<UUID,Long> result=new HashMap<>();
        jdbc.query("SELECT item_id,count(*) AS movements FROM inventory_transactions WHERE restaurant_id=:tenant "
            +"AND warehouse_id=:warehouse AND item_id IN (:items) GROUP BY item_id",
            Map.of("tenant",tenant,"warehouse",warehouse,"items",ingredients),rs->{
                result.put(rs.getObject("item_id",UUID.class),rs.getLong("movements"));
            });
        return result;
    }
}
