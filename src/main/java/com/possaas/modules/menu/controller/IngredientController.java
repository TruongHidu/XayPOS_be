package com.possaas.modules.menu.controller;

import com.possaas.modules.menu.dto.IngredientRequests;
import com.possaas.modules.menu.dto.IngredientResponse;
import com.possaas.modules.menu.dto.IngredientSearch;
import com.possaas.modules.menu.service.IngredientCommandService;
import com.possaas.modules.menu.service.IngredientQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/api/v1/menu/ingredients")
public class IngredientController {
    private final IngredientQueryService queryService;
    private final IngredientCommandService commandService;

    @GetMapping
    @PreAuthorize("hasAuthority('INGREDIENT_VIEW')")
    public PageResponse<IngredientResponse> search(@Valid @ModelAttribute IngredientSearch search) {
        return queryService.search(search);
    }

    @GetMapping("/{ingredientId}")
    @PreAuthorize("hasAuthority('INGREDIENT_VIEW')")
    public IngredientResponse detail(@PathVariable UUID ingredientId) {
        return queryService.detail(ingredientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INGREDIENT_CREATE')")
    public IngredientResponse create(@Valid @RequestBody IngredientRequests.Create request,
            HttpServletRequest httpRequest) {
        return commandService.create(request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/{ingredientId}")
    @PreAuthorize("hasAuthority('INGREDIENT_UPDATE')")
    public IngredientResponse update(@PathVariable UUID ingredientId,
            @Valid @RequestBody IngredientRequests.Update request, HttpServletRequest httpRequest) {
        return commandService.update(ingredientId, request, httpRequest.getRemoteAddr());
    }

    @PatchMapping("/{ingredientId}/status")
    @PreAuthorize("hasAuthority('INGREDIENT_UPDATE')")
    public IngredientResponse status(@PathVariable UUID ingredientId,
            @Valid @RequestBody IngredientRequests.Status request, HttpServletRequest httpRequest) {
        return commandService.status(ingredientId, request, httpRequest.getRemoteAddr());
    }

    @DeleteMapping("/{ingredientId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('INGREDIENT_DELETE')")
    public void delete(@PathVariable UUID ingredientId, @RequestParam @Min(0) long expectedVersion,
            HttpServletRequest httpRequest) {
        commandService.delete(ingredientId, expectedVersion, httpRequest.getRemoteAddr());
    }
}
