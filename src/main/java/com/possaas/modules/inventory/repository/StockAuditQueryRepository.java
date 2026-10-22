package com.possaas.modules.inventory.repository;

import com.possaas.modules.inventory.dto.*;
import com.possaas.modules.inventory.entity.StockAuditStatus;
import com.possaas.modules.inventory.service.StockAuditPolicy;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class StockAuditQueryRepository {
    private static final String HEADER = """
        SELECT a.*,w.code warehouse_code,w.name warehouse_name,
               s.item_count,s.counted_count,s.changed_count,d.id adjustment_id,d.document_code adjustment_code
        FROM stock_audits a
        JOIN warehouses w ON w.restaurant_id=a.restaurant_id AND w.id=a.warehouse_id
        LEFT JOIN stock_documents d ON d.restaurant_id=a.restaurant_id AND d.stock_audit_id=a.id
        CROSS JOIN LATERAL (
            SELECT count(*) item_count,count(counted_quantity) counted_count,
                   count(*) FILTER(WHERE difference_quantity<>0) changed_count
            FROM stock_audit_items i WHERE i.restaurant_id=a.restaurant_id AND i.stock_audit_id=a.id
        ) s
        """;
    private static final Map<String,String> HEADER_SORT = Map.of(
        "createdAt","a.created_at","startedAt","a.started_at","updatedAt","a.updated_at","auditCode","a.audit_code");
    private static final Map<String,String> ITEM_SORT = Map.of(
        "name","lower(i.ingredient_name_snapshot)","sku","lower(i.ingredient_sku_snapshot)",
        "systemQuantity","i.system_quantity","countedQuantity","i.counted_quantity","differenceQuantity","i.difference_quantity");
    private final NamedParameterJdbcTemplate jdbc;

    public StockAuditDetailResponse detail(UUID tenant,UUID id) {
        return jdbc.query(HEADER+" WHERE a.restaurant_id=:tenant AND a.id=:id",
            Map.of("tenant",tenant,"id",id),(rs,n)->header(rs)).stream().findFirst().orElseThrow(StockAuditPolicy::missing);
    }

    public Page<StockAuditSummaryResponse> search(UUID tenant,StockAuditSearch search) {
        StringBuilder where=new StringBuilder(" WHERE a.restaurant_id=:tenant");
        Map<String,Object> params=new HashMap<>();params.put("tenant",tenant);
        if(search.getQ()!=null && !search.getQ().isBlank()) {
            where.append(" AND (lower(a.audit_code) LIKE :q ESCAPE '\\' OR lower(coalesce(a.note,'')) LIKE :q ESCAPE '\\')");
            params.put("q",like(search.getQ()));
        }
        if(search.getWarehouseId()!=null){where.append(" AND a.warehouse_id=:warehouse");params.put("warehouse",search.getWarehouseId());}
        if(search.getStatus()!=null){where.append(" AND a.status=:status");params.put("status",search.getStatus().name());}
        if(search.getCreatedFrom()!=null){where.append(" AND a.created_at>=:from");params.put("from",Timestamp.from(search.getCreatedFrom()));}
        if(search.getCreatedTo()!=null){where.append(" AND a.created_at<:to");params.put("to",Timestamp.from(search.getCreatedTo()));}
        Long total=jdbc.queryForObject("SELECT count(*) FROM stock_audits a"+where,params,Long.class);
        page(params,search.getPage(),search.getSize());
        var content=jdbc.query(HEADER+where+order(HEADER_SORT,search.getSortBy(),search.getDirection(),"a.id")
            +" LIMIT :limit OFFSET :offset",params,(rs,n)->header(rs).summary());
        return new Page<>(content,total==null?0:total);
    }

    public Page<StockAuditItemResponse> items(UUID tenant,UUID auditId,StockAuditItemSearch search) {
        StringBuilder where=new StringBuilder(" WHERE i.restaurant_id=:tenant AND i.stock_audit_id=:audit");
        Map<String,Object> params=new HashMap<>();params.put("tenant",tenant);params.put("audit",auditId);
        if(search.getQ()!=null && !search.getQ().isBlank()) {
            where.append(" AND (lower(i.ingredient_name_snapshot) LIKE :q ESCAPE '\\' OR lower(coalesce(i.ingredient_sku_snapshot,'')) LIKE :q ESCAPE '\\')");
            params.put("q",like(search.getQ()));
        }
        if(search.getCounted()!=null)where.append(search.getCounted()?" AND i.counted_quantity IS NOT NULL":" AND i.counted_quantity IS NULL");
        if(search.isDifferenceOnly())where.append(" AND i.difference_quantity<>0");
        Long total=jdbc.queryForObject("SELECT count(*) FROM stock_audit_items i"+where,params,Long.class);
        page(params,search.getPage(),search.getSize());
        var content=jdbc.query("SELECT i.* FROM stock_audit_items i"+where
            +order(ITEM_SORT,search.getSortBy(),search.getDirection(),"i.id")+" LIMIT :limit OFFSET :offset",params,(rs,n)->new StockAuditItemResponse(
                rs.getObject("id",UUID.class),rs.getObject("ingredient_id",UUID.class),rs.getString("ingredient_sku_snapshot"),
                rs.getString("ingredient_name_snapshot"),rs.getString("unit_snapshot"),rs.getBigDecimal("system_quantity"),
                rs.getBigDecimal("counted_quantity"),rs.getBigDecimal("difference_quantity"),rs.getBigDecimal("reference_unit_cost_snapshot"),rs.getString("note")));
        return new Page<>(content,total==null?0:total);
    }

    private static StockAuditDetailResponse header(ResultSet rs)throws SQLException {
        return new StockAuditDetailResponse(rs.getObject("id",UUID.class),rs.getString("audit_code"),rs.getObject("warehouse_id",UUID.class),
            rs.getString("warehouse_code"),rs.getString("warehouse_name"),StockAuditStatus.valueOf(rs.getString("status")),rs.getString("note"),
            rs.getLong("item_count"),rs.getLong("counted_count"),rs.getLong("changed_count"),rs.getObject("adjustment_id",UUID.class),rs.getString("adjustment_code"),
            rs.getLong("version"),rs.getObject("created_by",UUID.class),rs.getObject("started_by",UUID.class),rs.getObject("confirmed_by",UUID.class),rs.getObject("cancelled_by",UUID.class),
            instant(rs,"created_at"),instant(rs,"started_at"),instant(rs,"confirmed_at"),instant(rs,"cancelled_at"),instant(rs,"updated_at"));
    }
    private static Instant instant(ResultSet rs,String name)throws SQLException {var value=rs.getTimestamp(name);return value==null?null:value.toInstant();}
    private static String like(String value){return "%"+value.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";}
    private static void page(Map<String,Object> params,int page,int size){params.put("limit",size);params.put("offset",(long)page*size);}
    private static String order(Map<String,String> columns,String sort,String direction,String id){
        if(!columns.containsKey(sort) || !("asc".equalsIgnoreCase(direction)||"desc".equalsIgnoreCase(direction)))throw StockAuditPolicy.invalid("Unsupported sort");
        return " ORDER BY "+columns.get(sort)+" "+direction.toUpperCase(Locale.ROOT)+" NULLS LAST,"+id+" ASC";
    }
    public record Page<T>(List<T> content,long totalElements){}
}
