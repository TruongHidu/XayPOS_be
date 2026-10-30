package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.service.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class CashbookReceiptQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String FROM="""
      FROM stock_documents d JOIN warehouses w ON w.restaurant_id=d.restaurant_id AND w.id=d.warehouse_id
      """;
    private static final String MATCHING="""
      WHERE d.restaurant_id=:restaurantId AND d.document_type='RECEIPT' AND d.status='CONFIRMED'
        AND d.confirmed_at>=:fromInclusive AND d.confirmed_at<:toExclusive
        AND (d.currency_code=:currencyCode OR (:includeUnknown AND d.currency_code IS NULL))
        AND (CAST(:warehouseId AS uuid) IS NULL OR d.warehouse_id=:warehouseId)
        AND (CAST(:q AS varchar) IS NULL OR lower(d.document_code) LIKE :q ESCAPE '!')
      """;
    public long count(CashbookQueryContext c,CashbookReadRequests.Receipts f) {
        return jdbc.queryForObject("SELECT count(*) "+FROM+MATCHING,parameters(c,f),Long.class);
    }
    public List<CashbookStockReceiptResponse> page(CashbookQueryContext c,CashbookReadRequests.Receipts f,CashbookFilterPolicy.Page p) {
        String sort=switch(p.sortBy()){case "confirmedAt"->"d.confirmed_at";case "documentCode"->"d.document_code";case "totalCost"->"d.total_cost";default->throw new IllegalArgumentException("Unsupported sort");};
        String sql="""
          WITH linked AS (
            SELECT restaurant_id,stock_document_id,count(*) AS expense_count,
              coalesce(sum(amount) FILTER(WHERE currency_code=:currencyCode),0) AS comparable_amount
            FROM cashbook_expenses WHERE restaurant_id=:restaurantId AND status='POSTED' AND stock_document_id IS NOT NULL
            GROUP BY restaurant_id,stock_document_id
          )
          SELECT d.id,d.document_code,d.warehouse_id,w.name AS warehouse_name,d.confirmed_at,d.total_cost,d.currency_code,
            coalesce(l.expense_count,0) AS linked_count,
            CASE WHEN d.currency_code IS NULL THEN NULL ELSE coalesce(l.comparable_amount,0) END AS linked_amount
          """+FROM+" LEFT JOIN linked l ON l.restaurant_id=d.restaurant_id AND l.stock_document_id=d.id "+MATCHING+" ORDER BY "+sort+" "+CashbookEntryQueryRepository.direction(p)+",d.id ASC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql,parameters(c,f).addValue("limit",p.size()).addValue("offset",(long)p.page()*p.size()),(rs,n)->new CashbookStockReceiptResponse(
            rs.getObject("id",UUID.class),rs.getString("document_code"),rs.getObject("warehouse_id",UUID.class),rs.getString("warehouse_name"),
            CashbookSqlSupport.instant(rs,"confirmed_at"),CashbookSqlSupport.money(rs.getBigDecimal("total_cost")),rs.getString("currency_code"),
            rs.getString("currency_code")==null?"UNKNOWN":"KNOWN",rs.getLong("linked_count"),rs.getBigDecimal("linked_amount")==null?null:CashbookSqlSupport.money(rs.getBigDecimal("linked_amount"))));
    }
    private MapSqlParameterSource parameters(CashbookQueryContext c,CashbookReadRequests.Receipts f) {
        return CashbookSqlSupport.parameters(c).addValue("includeUnknown",Boolean.TRUE.equals(f.includeUnknownCurrency()))
            .addValue("warehouseId",f.warehouseId()).addValue("q",CashbookSqlSupport.pattern(f.q()==null||f.q().isBlank()?null:f.q().trim()));
    }
}
