package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.entity.*;
import com.possaas.modules.subscription.dto.PageResponse;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository @RequiredArgsConstructor
public class LowStockAlertQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String FROM="""
        FROM low_stock_alerts a
        LEFT JOIN warehouses w ON w.restaurant_id=a.restaurant_id AND w.id=a.warehouse_id
        LEFT JOIN items i ON i.restaurant_id=a.restaurant_id AND i.id=a.item_id
        LEFT JOIN inventory_balances b ON b.restaurant_id=a.restaurant_id
            AND b.warehouse_id=a.warehouse_id AND b.item_id=a.item_id
        WHERE a.restaurant_id=:tenant
        """;
    private static final String SELECT="""
        SELECT a.*,b.quantity AS current_quantity,b.reserved_quantity AS current_reserved,
            b.min_quantity AS current_min,
            (w.is_active AND w.deleted_at IS NULL AND i.item_type='INGREDIENT'
                AND i.track_inventory AND i.deleted_at IS NULL AND i.base_unit=a.unit
                AND b.id IS NOT NULL) AS comparable
        """;

    public Optional<LowStockAlertResponse> find(UUID tenant,UUID id) {
        return jdbc.query(SELECT+FROM+" AND a.id=:id",Map.of("tenant",tenant,"id",id),
                (rs,row)->map(rs)).stream().findFirst();
    }

    public PageResponse<LowStockAlertResponse> search(UUID tenant,LowStockAlertSearch search) {
        var params=new HashMap<String,Object>();params.put("tenant",tenant);
        String sql=FROM;
        if (search.getWarehouseId()!=null) {sql+=" AND a.warehouse_id=:warehouse";params.put("warehouse",search.getWarehouseId());}
        if (search.getIngredientId()!=null) {sql+=" AND a.item_id=:ingredient";params.put("ingredient",search.getIngredientId());}
        if (search.getStatus()!=null) {sql+=" AND a.status=:status";params.put("status",search.getStatus().name());}
        if (search.getTriggeredFrom()!=null) {sql+=" AND a.triggered_at>=:from";params.put("from",Timestamp.from(search.getTriggeredFrom()));}
        if (search.getTriggeredTo()!=null) {sql+=" AND a.triggered_at<:to";params.put("to",Timestamp.from(search.getTriggeredTo()));}
        if (search.getQ()!=null && !search.getQ().isBlank()) {
            sql+=" AND (lower(a.ingredient_name) LIKE :q ESCAPE '\\' OR lower(coalesce(a.ingredient_sku,'')) LIKE :q ESCAPE '\\')";
            String q=search.getQ().trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
            params.put("q","%"+q+"%");
        }
        // Validate again at the repository boundary; never interpolate unchecked SQL identifiers.
        String sort=switch(search.getSortBy()) {case "triggeredAt" -> "a.triggered_at";case "updatedAt" -> "a.updated_at";
            default -> throw new IllegalArgumentException("Unsupported alert sort");};
        String direction="asc".equalsIgnoreCase(search.getDirection())?"ASC":
            "desc".equalsIgnoreCase(search.getDirection())?"DESC":null;
        if (direction==null) throw new IllegalArgumentException("Unsupported alert direction");
        long count=Objects.requireNonNull(jdbc.queryForObject("SELECT count(*) "+sql,params,Long.class));
        params.put("limit",search.getSize());params.put("offset",(long)search.getPage()*search.getSize());
        var content=jdbc.query(SELECT+sql+" ORDER BY "+sort+" "+direction+",a.id ASC LIMIT :limit OFFSET :offset",
                params,(rs,row)->map(rs));
        return new PageResponse<>(content,search.getPage(),search.getSize(),count,
                count==0?0:(int)((count+search.getSize()-1)/search.getSize()));
    }

    private static LowStockAlertResponse map(ResultSet rs) throws SQLException {
        boolean comparable=rs.getBoolean("comparable");
        var physical=comparable?rs.getBigDecimal("current_quantity"):null;
        var reserved=comparable?rs.getBigDecimal("current_reserved"):null;
        return new LowStockAlertResponse(rs.getObject("id",UUID.class),rs.getObject("warehouse_id",UUID.class),
            rs.getString("warehouse_code"),rs.getString("warehouse_name"),rs.getObject("item_id",UUID.class),
            rs.getString("ingredient_sku"),rs.getString("ingredient_name"),rs.getString("unit"),
            LowStockAlertStatus.valueOf(rs.getString("status")),rs.getBigDecimal("quantity_at_trigger"),
            rs.getBigDecimal("threshold_quantity"),physical,reserved,comparable?physical.subtract(reserved):null,
            comparable?rs.getBigDecimal("current_min"):null,instant(rs,"triggered_at"),
            rs.getObject("acknowledged_by",UUID.class),instant(rs,"acknowledged_at"),instant(rs,"resolved_at"),
            rs.getString("resolution_reason")==null?null:LowStockAlertResolutionReason.valueOf(rs.getString("resolution_reason")),
            rs.getLong("version"),instant(rs,"created_at"),instant(rs,"updated_at"));
    }

    private static Instant instant(ResultSet rs,String field) throws SQLException {
        Timestamp value=rs.getTimestamp(field);return value==null?null:value.toInstant();
    }
}
