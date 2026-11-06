package com.possaas.modules.inventory.controller;

import com.possaas.modules.inventory.dto.InventoryTransactionResponse;
import com.possaas.modules.inventory.dto.InventoryTransactionSearch;
import com.possaas.modules.inventory.dto.StockDocumentDetailResponse;
import com.possaas.modules.inventory.dto.StockDocumentRequests;
import com.possaas.modules.inventory.dto.StockDocumentSearch;
import com.possaas.modules.inventory.dto.StockDocumentSummaryResponse;
import com.possaas.modules.inventory.service.StockDocumentCommandService;
import com.possaas.modules.inventory.service.StockDocumentQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/inventory")
public class StockDocumentController {
    private static final String MUTATE = "hasAnyAuthority('INVENTORY_RECEIVE','INVENTORY_ISSUE','INVENTORY_ADJUST')";
    private final StockDocumentQueryService queries;
    private final StockDocumentCommandService commands;

    @GetMapping("/stock-documents")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<StockDocumentSummaryResponse> search(@Valid @ModelAttribute StockDocumentSearch search) {
        return queries.search(search);
    }

    @GetMapping("/stock-documents/{documentId}")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public StockDocumentDetailResponse detail(@PathVariable UUID documentId) {
        return queries.detail(documentId);
    }

    @PostMapping("/stock-documents")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse create(
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey,
            @Valid @RequestBody StockDocumentRequests.Create request, HttpServletRequest httpRequest) {
        return commands.create(idempotencyKey, request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/stock-documents/{documentId}")
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse update(@PathVariable UUID documentId,
            @Valid @RequestBody StockDocumentRequests.Update request, HttpServletRequest httpRequest) {
        return commands.update(documentId, request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/stock-documents/{documentId}/items")
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse addItem(@PathVariable UUID documentId,
            @Valid @RequestBody StockDocumentRequests.AddItem request, HttpServletRequest httpRequest) {
        return commands.addItem(documentId, request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/stock-documents/{documentId}/items/{documentItemId}")
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse updateItem(@PathVariable UUID documentId,
            @PathVariable UUID documentItemId, @Valid @RequestBody StockDocumentRequests.UpdateItem request,
            HttpServletRequest httpRequest) {
        return commands.updateItem(documentId, documentItemId, request, httpRequest.getRemoteAddr());
    }

    @DeleteMapping("/stock-documents/{documentId}/items/{documentItemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize(MUTATE)
    public void removeItem(@PathVariable UUID documentId, @PathVariable UUID documentItemId,
            @RequestParam @Min(0) long expectedVersion, HttpServletRequest httpRequest) {
        commands.removeItem(documentId, documentItemId, expectedVersion, httpRequest.getRemoteAddr());
    }

    @PostMapping("/stock-documents/{documentId}/confirm")
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse confirm(@PathVariable UUID documentId,
            @Valid @RequestBody StockDocumentRequests.Transition request, HttpServletRequest httpRequest) {
        return commands.confirm(documentId, request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/stock-documents/{documentId}/cancel")
    @PreAuthorize(MUTATE)
    public StockDocumentDetailResponse cancel(@PathVariable UUID documentId,
            @Valid @RequestBody StockDocumentRequests.Transition request, HttpServletRequest httpRequest) {
        return commands.cancel(documentId, request, httpRequest.getRemoteAddr());
    }

    @GetMapping("/transactions")
    @PreAuthorize("hasAuthority('INVENTORY_VIEW')")
    public PageResponse<InventoryTransactionResponse> transactions(
            @Valid @ModelAttribute InventoryTransactionSearch search) {
        return queries.transactions(search);
    }
}
