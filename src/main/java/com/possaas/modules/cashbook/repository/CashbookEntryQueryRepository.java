package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.service.*;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class CashbookEntryQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String COHORT="""
      WITH cash_entries AS (
        SELECT 'ORDER_PAYMENT'::varchar AS source_type,p.id AS source_id,p.payment_code AS code,
          'INCOME'::varchar AS entry_type,'SALES_COLLECTION'::varchar AS category_code,
          'Thu bán hàng'::varchar AS category_name,p.amount,p.currency_code,p.payment_method,
          p.paid_at AS occurred_at,p.created_at AS recorded_at,p.created_by,p.order_id,o.order_code,
          NULL::uuid AS stock_document_id,NULL::varchar AS source_document_code,
          concat_ws(' ',p.payment_code,o.order_code,p.reference_no) AS search_text
        FROM payments p
        JOIN orders o ON o.restaurant_id=p.restaurant_id AND o.id=p.order_id
        WHERE p.restaurant_id=:restaurantId AND p.status='SUCCESS'
          AND p.currency_code=:currencyCode AND p.paid_at>=:fromInclusive AND p.paid_at<:toExclusive
        UNION ALL
        SELECT 'EXPENSE_VOUCHER',e.id,e.expense_code,'EXPENSE',c.code,c.name,e.amount,e.currency_code,e.payment_method,
          e.expense_at,e.created_at,e.created_by,NULL::uuid,NULL::varchar,e.stock_document_id,e.source_document_code,
          concat_ws(' ',e.expense_code,e.reference_no,e.source_document_code)
        FROM cashbook_expenses e JOIN cashbook_categories c ON c.id=e.category_id
        WHERE e.restaurant_id=:restaurantId AND e.status='POSTED'
          AND e.currency_code=:currencyCode AND e.expense_at>=:fromInclusive AND e.expense_at<:toExclusive
      ), filtered AS (
        SELECT * FROM cash_entries x
        WHERE (CAST(:entryType AS varchar) IS NULL OR x.entry_type=:entryType)
          AND (CAST(:sourceType AS varchar) IS NULL OR x.source_type=:sourceType)
          AND (CAST(:categoryCode AS varchar) IS NULL OR x.category_code=:categoryCode)
          AND (CAST(:paymentMethod AS varchar) IS NULL OR x.payment_method=:paymentMethod)
          AND (CAST(:createdBy AS uuid) IS NULL OR x.created_by=:createdBy)
          AND (CAST(:q AS varchar) IS NULL OR lower(x.search_text) LIKE :q ESCAPE '!')
      )
      """;
    public long count(CashbookQueryContext c,CashbookFilterPolicy.Filter f) {
        return jdbc.queryForObject(COHORT+" SELECT count(*) FROM filtered",parameters(c,f),Long.class);
    }
    public List<CashbookEntryResponse> page(CashbookQueryContext c,CashbookFilterPolicy.Filter f,CashbookFilterPolicy.Page p) {
        String sort=switch(p.sortBy()) {case "amount"->"amount";case "code"->"code";case "occurredAt"->"occurred_at";default->throw new IllegalArgumentException("Unsupported sort");};
        String sql=COHORT+" SELECT * FROM filtered ORDER BY "+sort+" "+direction(p)+",source_type ASC,source_id ASC LIMIT :limit OFFSET :offset";
        return jdbc.query(sql,parameters(c,f).addValue("limit",p.size()).addValue("offset",(long)p.page()*p.size()),(rs,n)->new CashbookEntryResponse(
            rs.getString("source_type"),rs.getObject("source_id",UUID.class),rs.getString("code"),rs.getString("entry_type"),
            rs.getString("category_code"),rs.getString("category_name"),CashbookSqlSupport.money(rs.getBigDecimal("amount")),rs.getString("currency_code"),
            PaymentMethod.valueOf(rs.getString("payment_method")),CashbookSqlSupport.instant(rs,"occurred_at"),CashbookSqlSupport.instant(rs,"recorded_at"),
            rs.getObject("created_by",UUID.class),rs.getObject("order_id",UUID.class),rs.getString("order_code"),rs.getObject("stock_document_id",UUID.class),rs.getString("source_document_code")));
    }
    private static final String TOTALS="""
        coalesce(sum(amount) FILTER(WHERE entry_type='INCOME'),0) AS income,
        coalesce(sum(amount) FILTER(WHERE entry_type='EXPENSE'),0) AS expense,
        count(*) FILTER(WHERE entry_type='INCOME') AS income_count,
        count(*) FILTER(WHERE entry_type='EXPENSE') AS expense_count,
        coalesce(sum(amount) FILTER(WHERE source_type='ORDER_PAYMENT'),0) AS sale_income,
        coalesce(sum(amount) FILTER(WHERE entry_type='EXPENSE' AND stock_document_id IS NOT NULL),0) AS linked_expense,
        coalesce(sum(amount) FILTER(WHERE entry_type='EXPENSE' AND stock_document_id IS NULL),0) AS other_expense
        """;
    public CashbookSummaryResponse summary(CashbookQueryContext c,CashbookFilterPolicy.Filter f) {
        return jdbc.queryForObject(COHORT+" SELECT "+TOTALS+" FROM filtered",parameters(c,f),(rs,n)->{
            BigDecimal income=CashbookSqlSupport.money(rs.getBigDecimal("income")),expense=CashbookSqlSupport.money(rs.getBigDecimal("expense"));
            return new CashbookSummaryResponse(income,expense,income.subtract(expense),rs.getLong("income_count"),rs.getLong("expense_count"),
                CashbookSqlSupport.money(rs.getBigDecimal("sale_income")),CashbookSqlSupport.money(rs.getBigDecimal("linked_expense")),CashbookSqlSupport.money(rs.getBigDecimal("other_expense")));
        });
    }
    public List<CashbookSeriesPointResponse> series(CashbookQueryContext c,CashbookFilterPolicy.Filter f,String granularity) {
        String bucket="MONTH".equals(granularity)?"CAST(date_trunc('month',timezone(:timezone,occurred_at)) AS date)":"CAST(timezone(:timezone,occurred_at) AS date)";
        return jdbc.query(COHORT+" SELECT "+bucket+" AS bucket_date,"+TOTALS+" FROM filtered GROUP BY bucket_date ORDER BY bucket_date",parameters(c,f),(rs,n)->{
            var income=CashbookSqlSupport.money(rs.getBigDecimal("income"));var expense=CashbookSqlSupport.money(rs.getBigDecimal("expense"));
            return new CashbookSeriesPointResponse(rs.getObject("bucket_date",LocalDate.class),income,expense,income.subtract(expense),rs.getLong("income_count"),rs.getLong("expense_count"));
        });
    }
    private MapSqlParameterSource parameters(CashbookQueryContext c,CashbookFilterPolicy.Filter f) {
        return CashbookSqlSupport.parameters(c).addValue("entryType",f.type()).addValue("sourceType",f.sourceType()).addValue("categoryCode",f.categoryCode())
            .addValue("paymentMethod",f.paymentMethod()).addValue("createdBy",f.createdBy()).addValue("q",CashbookSqlSupport.pattern(f.q()));
    }
    static String direction(CashbookFilterPolicy.Page p) {return "asc".equals(p.direction())?"ASC":"DESC";}
}
