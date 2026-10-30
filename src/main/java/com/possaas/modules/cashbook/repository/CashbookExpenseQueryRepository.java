package com.possaas.modules.cashbook.repository;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.service.*;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.sql.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
@Repository @RequiredArgsConstructor
public class CashbookExpenseQueryRepository {
    private final NamedParameterJdbcTemplate jdbc;
    private static final String SELECT="""
      SELECT e.*,c.code AS category_code,c.name AS category_name
      FROM cashbook_expenses e JOIN cashbook_categories c ON c.id=e.category_id
      """;
    private static final String MATCHING="""
      WHERE e.restaurant_id=:restaurantId AND e.currency_code=:currencyCode
        AND e.expense_at>=:fromInclusive AND e.expense_at<:toExclusive
        AND (CAST(:status AS varchar) IS NULL OR e.status=:status)
        AND (CAST(:categoryCode AS varchar) IS NULL OR c.code=:categoryCode)
        AND (CAST(:paymentMethod AS varchar) IS NULL OR e.payment_method=:paymentMethod)
        AND (CAST(:stockDocumentId AS uuid) IS NULL OR e.stock_document_id=:stockDocumentId)
        AND (CAST(:createdBy AS uuid) IS NULL OR e.created_by=:createdBy)
        AND (CAST(:q AS varchar) IS NULL OR lower(concat_ws(' ',e.expense_code,e.reference_no,e.source_document_code,e.payee_name,e.note)) LIKE :q ESCAPE '!')
      """;
    public List<CashbookCategoryResponse> categories(String type) {
        return jdbc.query("SELECT id,code,name,type,active FROM cashbook_categories WHERE (CAST(:type AS varchar) IS NULL OR type=:type) ORDER BY type,code",
            new MapSqlParameterSource("type",type),(rs,n)->new CashbookCategoryResponse(rs.getObject("id",UUID.class),rs.getString("code"),rs.getString("name"),rs.getString("type"),rs.getBoolean("active")));
    }
    public Optional<CashbookExpenseResponse> detail(UUID tenant,UUID id) {
        return jdbc.query(SELECT+" WHERE e.restaurant_id=:tenant AND e.id=:id",new MapSqlParameterSource("tenant",tenant).addValue("id",id),(rs,n)->map(rs)).stream().findFirst();
    }
    public long count(CashbookQueryContext c,CashbookReadRequests.Expenses f,String status) {
        return jdbc.queryForObject("SELECT count(*) FROM cashbook_expenses e JOIN cashbook_categories c ON c.id=e.category_id "+MATCHING,parameters(c,f,status),Long.class);
    }
    public List<CashbookExpenseResponse> page(CashbookQueryContext c,CashbookReadRequests.Expenses f,String status,CashbookFilterPolicy.Page p) {
        String column=switch(p.sortBy()){case "expenseAt"->"e.expense_at";case "amount"->"e.amount";case "expenseCode"->"e.expense_code";case "createdAt"->"e.created_at";default->throw new IllegalArgumentException("Unsupported sort");};
        return jdbc.query(SELECT+MATCHING+" ORDER BY "+column+" "+CashbookEntryQueryRepository.direction(p)+",e.id ASC LIMIT :limit OFFSET :offset",
            parameters(c,f,status).addValue("limit",p.size()).addValue("offset",(long)p.page()*p.size()),(rs,n)->map(rs));
    }
    private MapSqlParameterSource parameters(CashbookQueryContext c,CashbookReadRequests.Expenses f,String status) {
        return CashbookSqlSupport.parameters(c).addValue("status",status).addValue("categoryCode",f.categoryCode()==null?null:f.categoryCode().trim().toUpperCase(Locale.ROOT))
            .addValue("paymentMethod",f.paymentMethod()==null?null:f.paymentMethod().name()).addValue("stockDocumentId",f.stockDocumentId())
            .addValue("createdBy",f.createdBy()).addValue("q",CashbookSqlSupport.pattern(f.q()==null||f.q().isBlank()?null:f.q().trim()));
    }
    private static CashbookExpenseResponse map(ResultSet rs) throws SQLException {
        UUID source=rs.getObject("stock_document_id",UUID.class);
        return new CashbookExpenseResponse(rs.getObject("id",UUID.class),rs.getString("expense_code"),rs.getString("category_code"),rs.getString("category_name"),
            CashbookSqlSupport.money(rs.getBigDecimal("amount")),rs.getString("currency_code"),PaymentMethod.valueOf(rs.getString("payment_method")),
            CashbookSqlSupport.instant(rs,"expense_at"),source,rs.getString("source_document_code"),source==null?null:rs.getString("source_currency_code")==null?"UNKNOWN":"KNOWN",
            rs.getString("reference_no"),rs.getString("payee_name"),rs.getString("note"),rs.getString("status"),rs.getObject("created_by",UUID.class),
            CashbookSqlSupport.instant(rs,"created_at"),CashbookSqlSupport.instant(rs,"updated_at"),rs.getLong("version"),rs.getObject("voided_by",UUID.class),CashbookSqlSupport.instant(rs,"voided_at"),rs.getString("void_reason"));
    }
}
