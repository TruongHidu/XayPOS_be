# Implementation — recipe application and reference-cost configuration

## Ownership and boundaries

MENU owns `Item.recipeEnabled` and `Item.costConfigured`. ReferenceCostConfigurationPolicy normalizes create/patch semantics without persistence. RecipeApplicationPolicy validates enabling against the existing feature, recipe and ingredient rules. Controllers and existing mutation permissions remain unchanged.

MenuAudit includes costPrice, costConfigured and recipeEnabled; IngredientAudit includes configured state. This fixes the old cost-only menu update comparison that could bypass the intended timestamp/audit path.

ActiveOrderRecipeQuery adds one tenant-scoped batch configuration read. Its MENU adapter returns a setting even when no recipe should be applied. OrderRecipeSnapshotService reads only fresh acceptance, then loads/validates definitions for the enabled subset. Disabled items ignore retained, unapplied invalid definitions.

ORDER persists nullable decision fields alongside its existing immutable recipe snapshot. RECIPE_DISABLED is distinct from unavailable feature/legacy/no-recipe. Existing captured definitions and quantities remain authoritative; no lookup of today's switch occurs during replay, consume or release.

INVENTORY maps RECIPE_DISABLED first to SNAPSHOT_RECIPE_DISABLED. Its existing SKIPPED handling avoids warehouse/reservation/stock writes. Tracked usages of CAPTURED decisions use the original reservation pipeline. Turning application off never releases old reservations by itself.

## Reference cost writers/readers

IngredientCostProvider conveys configured state to the estimate calculator. The existing success shape stays numeric; unknown ingredient costs produce INGREDIENT_COST_NOT_CONFIGURED rather than a fake complete zero estimate.

IngredientReferenceCostWriter exposes a typed reference-state batch read for receipts. Its existing numeric read remains available for the current physical stock-audit snapshot contract; this phase does not turn that contract into a monetary profit/valuation report. Unknown reference-price metadata must not be interpreted as historical cost in future reporting.

ReceiptReferenceCostService preserves the weighted policy when previous value is known. Unknown opening stock causes a truthful skipped-recalculation audit while the stock receipt commits. A zero receipt into zero stock confirms a genuine reference zero even if the numeric price does not change.

## Transaction/version/realtime

Catalogue mutations, receipt updates and order acceptance retain the restaurant pessimistic lock and their current transactions. There is no async/REQUIRES_NEW decision write, no root-order dirtying for a catalogue toggle, and no additional realtime publication. Existing final ORDER_CHANGED boundaries still publish only after successful mutations and deliver after commit.

## Database

V37 adds catalogue flags, validates ingredient/application and unknown-price pairs, classifies current catalogue data, expands snapshot/allocation decision enums and adds nullable legacy-compatible source metadata. New non-legacy decision inserts require metadata. Existing update/delete guards cover the new snapshot columns too.

No old immutable rows are updated, no ledger trigger is disabled, and RECIPE_USE.unit_cost remains null. Migration V1–V36 bytes are preserved. Historical monetary costing remains Phase 2.

## Verification

Tests cover explicit zero/omitted/clear, cost-only audit, both toggle/cancel directions, consume after toggle, draft/append/replay, QR approval timing, feature recovery, concurrent toggle/submit, receipt ambiguity, migration and database guards. Full Maven run: 1189 tests passed, zero failures/errors/skips. See [verification and changed-file inventory](recipe-application-and-cost-config-verification.md).
