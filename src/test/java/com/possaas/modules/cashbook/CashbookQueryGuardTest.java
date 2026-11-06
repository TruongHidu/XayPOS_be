package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.modules.cashbook.dto.CashbookReadRequests;
import com.possaas.modules.cashbook.repository.*;
import com.possaas.modules.cashbook.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
class CashbookQueryGuardTest {
    @Test void deniedAccessPrecedesRangeQueryAndCalculationForEveryRead(){
        var access=mock(CashbookAccessService.class);var ranges=mock(CashbookRangePolicy.class);var filters=mock(CashbookFilterPolicy.class);
        var entries=mock(CashbookEntryQueryRepository.class);var expenses=mock(CashbookExpenseQueryRepository.class);var receipts=mock(CashbookReceiptQueryRepository.class);
        var queries=new CashbookQueryService(access,ranges,filters,entries,expenses,receipts);
        when(access.read(anyBoolean())).thenThrow(new AccessDeniedException("denied"));
        var filter=new CashbookReadRequests.Filter(null,null,null,null,null,null,null,null,null);
        var page=new CashbookReadRequests.Page(null,null,null,null);
        assertThatThrownBy(()->queries.summary(filter)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.entries(filter,page)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.series(filter,"DAY")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.categories(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.expense(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.expenses(new CashbookReadRequests.Expenses(null,null,null,null,null,null,null,null,null),page)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->queries.receipts(new CashbookReadRequests.Receipts(null,null,null,null,null,null),page)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(ranges,filters,entries,expenses,receipts);
    }
}
