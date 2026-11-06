package com.possaas.modules.cashbook;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.possaas.common.security.CurrentUser;
import com.possaas.modules.cashbook.application.port.StockReceiptQuery;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.entity.CashbookExpense;
import com.possaas.modules.cashbook.repository.*;
import com.possaas.modules.cashbook.service.*;
import com.possaas.modules.restaurant.entity.Restaurant;
import com.possaas.modules.payment.entity.PaymentMethod;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class CashbookExpenseCommandServiceTest {
    private final CashbookAccessService access=mock(CashbookAccessService.class);
    private final CashbookExpensePolicy policy=new CashbookExpensePolicy();
    private final CashbookExpenseIdempotency idem=mock(CashbookExpenseIdempotency.class);
    private final CashbookExpenseRepository expenses=mock(CashbookExpenseRepository.class);
    private final CashbookCategoryRepository categories=mock(CashbookCategoryRepository.class);
    private final StockReceiptQuery receipts=mock(StockReceiptQuery.class);
    private final CashbookExpenseMapper mapper=mock(CashbookExpenseMapper.class);
    private final CashbookExpenseAudit audit=mock(CashbookExpenseAudit.class);
    private final Instant now=Instant.parse("2026-10-29T05:00:00Z");
    private final CashbookExpenseCommandService commands=new CashbookExpenseCommandService(access,policy,idem,expenses,categories,receipts,mapper,audit,Clock.fixed(now,ZoneOffset.UTC));
    private final CurrentUser actor=new CurrentUser(UUID.randomUUID(),UUID.randomUUID(),"x@test","OWNER",Set.of("CASHBOOK_CREATE","CASHBOOK_VOID"));
    private final Restaurant restaurant=new Restaurant();
    @Test void replayPrecedesCategorySourceAndFutureTimeValidation() {
        UUID receipt=UUID.randomUUID();var request=new CashbookExpenseRequests.Create("INGREDIENT_PURCHASE",new BigDecimal("10"),"VND",PaymentMethod.CASH,now.plusSeconds(1),receipt,null,null,null);
        when(access.create(true)).thenReturn(new CashbookAccessService.Access(actor,restaurant));
        when(idem.key("key")).thenReturn("key");when(idem.fingerprint(eq(actor.userId()),any())).thenReturn("hash");
        var previous=mock(CashbookExpense.class);when(expenses.findByRestaurantIdAndIdempotencyKey(actor.restaurantId(),"key")).thenReturn(Optional.of(previous));
        assertThat(commands.create(request,"key","127.0.0.1").replay()).isTrue();verify(idem).requireMatch(previous,actor.userId(),"hash");
        verifyNoInteractions(receipts,categories,audit);verify(expenses,never()).saveAndFlush(any());
    }
    @Test void voidReplayDoesNotValidateOldVersionOrWriteAudit() {
        UUID id=UUID.randomUUID();var previous=mock(CashbookExpense.class);when(previous.getStatus()).thenReturn("VOIDED");
        when(access.voidExpense()).thenReturn(new CashbookAccessService.Access(actor,restaurant));
        when(expenses.findForUpdate(actor.restaurantId(),id)).thenReturn(Optional.of(previous));
        commands.voidExpense(id,new CashbookExpenseRequests.Void(999L,"different"),null);
        verifyNoInteractions(audit,receipts);verify(expenses,never()).saveAndFlush(any());
    }
    @Test void staleVersionRejectsBeforeVoidMutationAndAudit() {
        UUID id=UUID.randomUUID();var previous=mock(CashbookExpense.class);when(previous.getStatus()).thenReturn("POSTED");when(previous.getVersion()).thenReturn(2L);
        when(access.voidExpense()).thenReturn(new CashbookAccessService.Access(actor,restaurant));
        when(expenses.findForUpdate(actor.restaurantId(),id)).thenReturn(Optional.of(previous));
        assertThatThrownBy(()->commands.voidExpense(id,new CashbookExpenseRequests.Void(1L,"wrong"),null)).hasMessageContaining("another request");
        verifyNoInteractions(audit,receipts);verify(previous,never()).voidExpense(any(),any(),any());
    }
}
