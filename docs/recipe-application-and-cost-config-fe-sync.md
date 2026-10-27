# FE synchronization — recipe application and configured costs

Use existing authenticated menu/ingredient APIs. No socket or new mutation endpoint is needed.

- Add a menu switch labelled “Sử dụng công thức”. New items default off. After creating a recipe, explicitly PATCH recipeEnabled=true with the latest expectedVersion.
- Keep definition activation/versioning separate. Toggle off retains the recipe. Before archiving an active definition, disable application; handle RECIPE_APPLICATION_ENABLED.
- Hide/disable enabling without RECIPE_MANAGEMENT; allow disabling through the existing menu permission after a package change. The server remains authoritative.
- `costPrice` on menu responses always means its manual reference price. Do not replace it with costPerYieldUnit from an estimate.
- `costConfigured=false` displays “Chưa cấu hình giá vốn”, not “Miễn phí”. Explicit zero is configured only when entered/confirmed. For a recipe-enabled menu item, the flag describes its manual backup price, not ingredient-price completeness.
- Send costPrice only when the user edits/confirms it. Do not serialize a placeholder 0 for an untouched field: that would explicitly configure zero.
- To clear a price, PATCH costConfigured=false. To confirm a stored zero, PATCH costConfigured=true. Both retain expectedVersion.
- Keep costPrice/flag on unrelated edits. A cost-only or metadata-only change can increase item version; always use the response version.
- Estimate endpoint can now return INGREDIENT_COST_NOT_CONFIGURED. Ask the user to configure ingredient reference prices. It must not fall back to the manual menu price.
- Migration marks legacy zero reference prices unconfirmed; owner review may be needed. Receipt with positive unknown opening stock keeps that state and logs UNCONFIGURED_OPENING_REFERENCE_COST rather than guessing.
- Display the protected snapshot decision when needed. Old source fields may be null. Do not derive historical MANUAL/RECIPE from the current menu toggle.
- A selected cart or pending guest QR has not frozen application. SUBMIT/approval does. Once accepted, toggling menu configuration never changes old reservation/cancel/consume behaviour.
- Do not create local stock adjustments on toggle/cancel. Use existing REST mutation responses, conflicts, idempotency and refresh logic.

The phase does not add historical cost amounts, gross profit, new public fields or new realtime events. Keep public QR/KDS contracts private-data-free.
