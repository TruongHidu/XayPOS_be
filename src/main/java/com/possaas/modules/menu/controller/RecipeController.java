package com.possaas.modules.menu.controller;

import com.possaas.modules.menu.dto.RecipeRequests;
import com.possaas.modules.menu.dto.RecipeCostEstimateResponse;
import com.possaas.modules.menu.dto.RecipeResponse;
import com.possaas.modules.menu.dto.RecipeSearch;
import com.possaas.modules.menu.dto.RecipeSummaryResponse;
import com.possaas.modules.menu.service.RecipeCommandService;
import com.possaas.modules.menu.service.RecipeCostEstimateQueryService;
import com.possaas.modules.menu.service.RecipeQueryService;
import com.possaas.modules.subscription.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
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
@RequestMapping("/api/v1/menu/items/{menuItemId}/recipes")
public class RecipeController {
    private final RecipeQueryService queryService;
    private final RecipeCommandService commandService;
    private final RecipeCostEstimateQueryService costEstimates;

    @GetMapping("/{recipeId}/cost-estimate")
    @PreAuthorize("hasAuthority('RECIPE_VIEW')")
    public ResponseEntity<RecipeCostEstimateResponse> costEstimate(@PathVariable UUID menuItemId,
            @PathVariable UUID recipeId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(costEstimates.estimate(menuItemId, recipeId));
    }

    @GetMapping("/active")
    @PreAuthorize("hasAuthority('RECIPE_VIEW')")
    public RecipeResponse active(@PathVariable UUID menuItemId) {
        return queryService.active(menuItemId);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('RECIPE_VIEW')")
    public PageResponse<RecipeSummaryResponse> history(@PathVariable UUID menuItemId,
            @Valid @ModelAttribute RecipeSearch search) {
        return queryService.history(menuItemId, search);
    }

    @GetMapping("/{recipeId}")
    @PreAuthorize("hasAuthority('RECIPE_VIEW')")
    public RecipeResponse detail(@PathVariable UUID menuItemId, @PathVariable UUID recipeId) {
        return queryService.detail(menuItemId, recipeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RECIPE_CREATE')")
    public RecipeResponse create(@PathVariable UUID menuItemId,
            @Valid @RequestBody RecipeRequests.Create request, HttpServletRequest httpRequest) {
        return commandService.create(menuItemId, request, httpRequest.getRemoteAddr());
    }

    @PostMapping("/{activeRecipeId}/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('RECIPE_UPDATE')")
    public RecipeResponse createVersion(@PathVariable UUID menuItemId, @PathVariable UUID activeRecipeId,
            @Valid @RequestBody RecipeRequests.CreateVersion request, HttpServletRequest httpRequest) {
        return commandService.createVersion(menuItemId, activeRecipeId, request, httpRequest.getRemoteAddr());
    }

    @DeleteMapping("/{recipeId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('RECIPE_DELETE')")
    public void deactivate(@PathVariable UUID menuItemId, @PathVariable UUID recipeId,
            @RequestParam @Min(0) long expectedVersion, HttpServletRequest httpRequest) {
        commandService.deactivate(menuItemId, recipeId, expectedVersion, httpRequest.getRemoteAddr());
    }
}
