# Recipe application and reference-cost configuration — Phase 1

This phase adds catalogue configuration and immutable source decisions. It does not capture monetary consumption costs, populate RECIPE_USE.unit_cost, or add profit reports.

## Catalogue fields

Private menu responses add `recipeEnabled` and `costConfigured`. Ingredient responses add `costConfigured`. Existing `costPrice` remains numeric and non-negative.

- MENU_ITEM.costPrice is the manually entered reference cost, even when recipe application is enabled. Recipe estimates never overwrite it.
- INGREDIENT.costPrice is the current reference cost per base unit.
- `costConfigured=false` plus price 0 means unknown/unconfirmed, not free.
- `costConfigured=true` plus price 0 means an explicitly configured zero.
- MENU_ITEM.costConfigured only describes its manual price; it does not describe ingredient-cost completeness.
- New menu items start with `recipeEnabled=false`. INGREDIENT always has recipeEnabled=false internally.

## Existing endpoints extended

| Endpoint | Permission | Feature/guard |
| --- | --- | --- |
| POST /api/v1/menu/items | MENU_CREATE | Existing MENU_MANAGEMENT/tenant guards |
| PATCH /api/v1/menu/items/{id} | MENU_UPDATE | Existing guards; enabling also needs RECIPE_MANAGEMENT |
| GET /api/v1/menu/items[/{id}] | MENU_VIEW | Existing guards |
| POST /api/v1/menu/ingredients | INGREDIENT_CREATE | Existing recipe-or-inventory entitlement guard |
| PATCH /api/v1/menu/ingredients/{id} | INGREDIENT_UPDATE | Existing guards |
| GET /api/v1/menu/ingredients[/{id}] | INGREDIENT_VIEW | Existing guards |

Headers for mutations:

```http
Authorization: Bearer <access-token>
Content-Type: application/json
```

Tenant comes from JWT. PATCH retains the existing expectedVersion requirement.

Example: create a menu item without an assumed manual cost (201):

```http
POST /api/v1/menu/items
```

```json
{"name":"Phở bò","baseUnit":"tô","salePrice":50000}
```

Its response includes `costPrice: 0`, `costConfigured: false` and `recipeEnabled: false`,
alongside the existing id, version and other menu fields. GET list/detail returns the
same flags. For an ingredient, POST `/api/v1/menu/ingredients` with
`{"name":"Thịt bò","baseUnit":"g","costPrice":250,"trackInventory":true}` returns
`costPrice: 250` and `costConfigured: true` along with the existing ingredient fields.
Neither create operation creates a recipe, stock balance or accepted-order decision.

### Cost input rules

| Input | POST | PATCH |
| --- | --- | --- |
| Neither field present | 0, unconfigured | Keep both |
| costPrice present, flag omitted | Configured, including explicit 0 | Configured, including explicit 0 |
| costConfigured=true, price omitted | 400 COST_PRICE_REQUIRED | Confirm current numeric value |
| costConfigured=false, price omitted/0 | 0, unconfigured | Clear to 0, unconfigured |
| costConfigured=false, positive price | 400 INVALID_COST_CONFIGURATION | Same error |

Other negative/out-of-precision prices use existing validation errors. An actual flag-only change increments item version and is audited. A valid no-op does not create another audit/version.

### Recipe application

Create a menu item, create its recipe, then enable application:

```http
PATCH /api/v1/menu/items/{id}
```

```json
{"recipeEnabled":true,"expectedVersion":4}
```

Enabling needs a valid active recipe for the same menu item/tenant and compatible yield/ingredient units. Ingredient reference prices need not be configured. INVENTORY_MANAGEMENT and RECIPE_UPDATE are not extra requirements for this toggle.

POST with recipeEnabled=true cannot create a definition for a not-yet-created menu item; it returns 409 ACTIVE_RECIPE_REQUIRED (after the enabling feature guard).

Disable and optionally set the manual reference price atomically:

```json
{"recipeEnabled":false,"costPrice":25000,"expectedVersion":5}
```

Disabling does not require RECIPE_MANAGEMENT. It preserves recipe definitions/versions and all accepted history. It does not change sellability, sale price, item inventory tracking or existing reservations.

Creating/replacing a recipe version leaves the toggle unchanged. DELETE of an active recipe while its menu item remains enabled returns 409 RECIPE_APPLICATION_ENABLED; disable application first. Atomic creation of a replacement version still works. Existing recipe-reference protections for deletion/unit changes remain.

## Accepted order decisions

The decision is captured at SUBMIT/confirm, accepted append or QR approval, not at draft creation, guest pending request, GET or review.

The protected existing recipe-snapshot response adds nullable `recipeEnabledSnapshot` and `costSourceSnapshot` (MANUAL/RECIPE).

| Decision | snapshotStatus | Source | Inventory |
| --- | --- | --- | --- |
| Toggle off | RECIPE_DISABLED | MANUAL | SKIPPED / SNAPSHOT_RECIPE_DISABLED, no usages/reservations |
| Toggle on, feature present, valid recipe | CAPTURED | RECIPE | Existing tracked-ingredient policy |
| Toggle on, recipe feature unavailable | FEATURE_DISABLED | RECIPE | Existing skipped decision; never implicit manual fallback |
| Toggle on, feature present, no active recipe | Acceptance fails 409 ACTIVE_RECIPE_REQUIRED | No new decision | No new reservation |

Source means the selected source, not a promise that a monetary cost is available. DRAFT uses NOT_SUBMITTED without a persisted decision. Old rows retain their original status and null new metadata; historical choices are never inferred from today's menu.

Changing a toggle only affects fresh acceptance. Old reservation/consume/release uses the original snapshot. Calling the same menu item again can produce a different decision on a different order item.

## Estimate and receipt behaviour

GET /api/v1/menu/items/{menuItemId}/recipes/{recipeId}/cost-estimate retains its existing schema/security and remains read-only. Any unconfigured ingredient causes 409 INGREDIENT_COST_NOT_CONFIGURED; a configured zero is included with the existing ZERO_INGREDIENT_REFERENCE_COST warning. It can estimate a retained recipe even when application is off.

Receipt reference-cost recalculation:

- Known previous reference price: keep existing tenant-wide physical-stock weighted average.
- Zero previous physical stock: an explicit receipt price establishes a configured reference price, including zero.
- Positive previous stock with unconfigured price: receipt still succeeds, but reference price remains unconfigured. Audit INGREDIENT_COST_RECALCULATION_SKIPPED records reason UNCONFIGURED_OPENING_REFERENCE_COST. No zero-cost assumption or historic backfill.

## Migration and limits

V37 initializes existing active-recipe menu items as enabled, existing positive reference prices as configured, and legacy zero prices as unconfirmed. It never rewrites recipe snapshots, allocations or ledger. Immutable/deferred history guards remain. Public QR, KDS and ORDER_CHANGED do not gain private cost/configuration fields.
