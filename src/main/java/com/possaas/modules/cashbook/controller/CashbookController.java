package com.possaas.modules.cashbook.controller;
import com.possaas.modules.cashbook.dto.*;
import com.possaas.modules.cashbook.service.*;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
@RestController @Validated @RequiredArgsConstructor
@RequestMapping("/api/v1/cashbook")
public class CashbookController {
    private final CashbookQueryService queries;
    private final CashbookExpenseCommandService commands;
    @GetMapping("/categories") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<List<CashbookCategoryResponse>> categories(@RequestParam(required=false) String type) {
        return ok(queries.categories(type));
    }
    @GetMapping("/entries") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<CashbookEnvelope<PageResponse<CashbookEntryResponse>>> entries(
        @Valid @ModelAttribute CashbookReadRequests.Filter filter,@Valid @ModelAttribute CashbookReadRequests.Page page) {
        return ok(queries.entries(filter,page));
    }
    @GetMapping("/summary") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<CashbookEnvelope<CashbookSummaryResponse>> summary(@Valid @ModelAttribute CashbookReadRequests.Filter filter) {
        return ok(queries.summary(filter));
    }
    @GetMapping("/series") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<CashbookEnvelope<List<CashbookSeriesPointResponse>>> series(@Valid @ModelAttribute CashbookReadRequests.Filter filter,
        @RequestParam(defaultValue="DAY") String granularity) {
        return ok(queries.series(filter,granularity));
    }
    @GetMapping("/expenses") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<CashbookEnvelope<PageResponse<CashbookExpenseResponse>>> expenses(
        @Valid @ModelAttribute CashbookReadRequests.Expenses filter,@Valid @ModelAttribute CashbookReadRequests.Page page) {
        return ok(queries.expenses(filter,page));
    }
    @GetMapping("/expenses/{expenseId}") @PreAuthorize("hasAuthority('CASHBOOK_VIEW')")
    public ResponseEntity<CashbookExpenseResponse> expense(@PathVariable UUID expenseId) {return ok(queries.expense(expenseId));}
    @PostMapping("/expenses") @PreAuthorize("hasAuthority('CASHBOOK_CREATE')")
    public ResponseEntity<CashbookExpenseResponse> create(@Valid @RequestBody CashbookExpenseRequests.Create request,
        @RequestHeader(name="Idempotency-Key",required=false) String key,HttpServletRequest http) {
        var result=commands.create(request,key,http.getRemoteAddr());
        return ResponseEntity.status(result.replay()?HttpStatus.OK:HttpStatus.CREATED).cacheControl(CacheControl.noStore())
            .header("Idempotency-Replayed",Boolean.toString(result.replay())).body(result.response());
    }
    @PostMapping("/expenses/{expenseId}/void") @PreAuthorize("hasAuthority('CASHBOOK_VOID')")
    public ResponseEntity<CashbookExpenseResponse> voidExpense(@PathVariable UUID expenseId,
        @Valid @RequestBody CashbookExpenseRequests.Void request,HttpServletRequest http) {
        return ok(commands.voidExpense(expenseId,request,http.getRemoteAddr()));
    }
    @GetMapping("/stock-receipts") @PreAuthorize("hasAuthority('CASHBOOK_VIEW') and hasAuthority('INVENTORY_VIEW')")
    public ResponseEntity<CashbookEnvelope<PageResponse<CashbookStockReceiptResponse>>> receipts(
        @Valid @ModelAttribute CashbookReadRequests.Receipts filter,@Valid @ModelAttribute CashbookReadRequests.Page page) {
        return ok(queries.receipts(filter,page));
    }
    private static <T> ResponseEntity<T> ok(T body) {return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);}
}
