# DIRECT stock — implementation and verification

Completed implementation for tracked MENU_ITEM sales using existing trackInventory/recipeEnabled configuration.

- Menu create/update supports DIRECT with mutual-exclusion and lifecycle guards.
- Shared tracked stock catalog, receipt weighted average, balances, ledger, alerts and stocktaking support MENU_ITEM.
- Acceptance freezes DIRECT; reservation/consume/release reuse existing ORDER/INVENTORY transaction and locks.
- DIRECT_USE consumes its frozen non-recipe monetary cost without synthetic recipe usages.
- New GET /api/v1/inventory/stock-items; stock document itemId aliases preserve legacy hashes.
- Response itemId/itemType identify direct stock; legacy ingredientId stays valid for ingredients and is null for MENU_ITEM.
- DIRECT allocation reads (including unsent DIRECT drafts) require no recipe entitlement; recipe access remains protected.
- V40 preserves V1–V39 and immutable history; adds an independent ledger reservation FK for nullable recipe usage.

## Verification

Full Maven test: **1342 tests / 127 suites, 0 failures, 0 errors, 0 skipped**.
Runtime JDK 24; compiler release 21. PostgreSQL 17.11 in a dedicated test container on 127.0.0.1:15445.
Test JVM timezone UTC; Hikari max pool 4, minimum idle 1; Spring context cache size 4.
Full command: mvn -DargLine="-Duser.timezone=UTC -Dspring.test.context.cache.maxSize=4" test.
The test database is separate from the user's database on port 5432.

DIRECT integration: 23 scenarios including mixed DIRECT/RECIPE/NONE, submit/confirm/append,
insufficient-stock rollback including a new table session, concurrent oversell prevention,
cancel/replay, frozen/unknown/zero cost, alerts, stocktaking and historical decisions after feature changes.
QR integration includes DIRECT table and takeaway approvals without RECIPE_MANAGEMENT.
Flyway tests cover clean migration and upgrade to V40 with nonempty historical snapshots/reservations/ledger.
OpenAPI strict parsing rejects duplicate keys; DTO schemas and references pass contract tests.

Updated existing tests follow the requested contract: menu trackInventory is now public to tenant management;
SQL rejects simultaneous tracking+recipe rather than all tracked menus; recipe permission is conditional
on DIRECT/non-DIRECT; realtime alert payload adds itemId/itemType.

## API and FE

See [complete FE sync and API examples](direct-stock-menu-items-fe-sync.md).
Create a Coca MENU_ITEM with trackInventory=true, recipeEnabled=false. Receive it using itemId,
then submit/approve orders normally. Receiving 100 and serving 3 leaves physical quantity 97.

No unit conversion, automatic OUT_OF_STOCK, automatic migration of old 1:1 recipes, FIFO or new payment behavior.
Existing 1:1 recipes continue operating. Receipt confirmation does not automatically create a cash expense.

## Changed files (82)

- src/main/java/com/possaas/modules/inventory/application/port/RecipeUsageCostQuery.java
- src/main/java/com/possaas/modules/inventory/application/port/TrackedIngredientCatalog.java
- src/main/java/com/possaas/modules/inventory/application/port/TrackedStockItemCatalog.java
- src/main/java/com/possaas/modules/inventory/controller/InventoryAllocationController.java
- src/main/java/com/possaas/modules/inventory/controller/StockItemController.java
- src/main/java/com/possaas/modules/inventory/dto/InventoryAllocationResponse.java
- src/main/java/com/possaas/modules/inventory/dto/InventoryBalanceResponse.java
- src/main/java/com/possaas/modules/inventory/dto/InventoryTransactionResponse.java
- src/main/java/com/possaas/modules/inventory/dto/LowStockAlertResponse.java
- src/main/java/com/possaas/modules/inventory/dto/StockAuditItemResponse.java
- src/main/java/com/possaas/modules/inventory/dto/StockDocumentItemResponse.java
- src/main/java/com/possaas/modules/inventory/dto/StockDocumentRequests.java
- src/main/java/com/possaas/modules/inventory/entity/InventoryReservation.java
- src/main/java/com/possaas/modules/inventory/entity/InventoryTransaction.java
- src/main/java/com/possaas/modules/inventory/entity/InventoryTransactionType.java
- src/main/java/com/possaas/modules/inventory/entity/LowStockAlert.java
- src/main/java/com/possaas/modules/inventory/entity/OrderItemInventoryAllocation.java
- src/main/java/com/possaas/modules/inventory/entity/StockAuditItem.java
- src/main/java/com/possaas/modules/inventory/entity/StockDocumentItem.java
- src/main/java/com/possaas/modules/inventory/repository/InventoryAllocationQueryRepository.java
- src/main/java/com/possaas/modules/inventory/repository/InventoryBalanceQueryRepository.java
- src/main/java/com/possaas/modules/inventory/repository/InventoryTransactionQueryRepository.java
- src/main/java/com/possaas/modules/inventory/repository/LowStockAlertBalanceQuery.java
- src/main/java/com/possaas/modules/inventory/repository/LowStockAlertQueryRepository.java
- src/main/java/com/possaas/modules/inventory/repository/StockAuditQueryRepository.java
- src/main/java/com/possaas/modules/inventory/service/InventoryAllocationAudit.java
- src/main/java/com/possaas/modules/inventory/service/InventoryAllocationPolicy.java
- src/main/java/com/possaas/modules/inventory/service/InventoryAllocationQueryService.java
- src/main/java/com/possaas/modules/inventory/service/InventoryBalanceResponseMapper.java
- src/main/java/com/possaas/modules/inventory/service/InventoryStockUsageQueryAdapter.java
- src/main/java/com/possaas/modules/inventory/service/LowStockAlertEventPublisher.java
- src/main/java/com/possaas/modules/inventory/service/LowStockAlertReconciliationService.java
- src/main/java/com/possaas/modules/inventory/service/OrderInventoryAllocationAdapter.java
- src/main/java/com/possaas/modules/inventory/service/ReceiptReferenceCostService.java
- src/main/java/com/possaas/modules/inventory/service/StockAuditAdjustmentOperation.java
- src/main/java/com/possaas/modules/inventory/service/StockDocumentCommandService.java
- src/main/java/com/possaas/modules/inventory/service/StockDocumentItemFactory.java
- src/main/java/com/possaas/modules/inventory/service/StockDocumentRequestHasher.java
- src/main/java/com/possaas/modules/inventory/service/StockDocumentResponseMapper.java
- src/main/java/com/possaas/modules/inventory/service/StockItemQueryService.java
- src/main/java/com/possaas/modules/menu/application/port/InventoryStockUsageQuery.java
- src/main/java/com/possaas/modules/menu/dto/MenuItemResponse.java
- src/main/java/com/possaas/modules/menu/dto/MenuRequests.java
- src/main/java/com/possaas/modules/menu/entity/StockTrackingMode.java
- src/main/java/com/possaas/modules/menu/repository/ItemRepository.java
- src/main/java/com/possaas/modules/menu/service/InventoryIngredientCatalogAdapter.java
- src/main/java/com/possaas/modules/menu/service/InventoryIngredientCostAdapter.java
- src/main/java/com/possaas/modules/menu/service/InventoryStockItemCatalogAdapter.java
- src/main/java/com/possaas/modules/menu/service/MenuAudit.java
- src/main/java/com/possaas/modules/menu/service/MenuItemCommandService.java
- src/main/java/com/possaas/modules/menu/service/MenuStockTrackingPolicy.java
- src/main/java/com/possaas/modules/menu/service/OrderRecipeQueryAdapter.java
- src/main/java/com/possaas/modules/menu/service/StockTrackingModeResolver.java
- src/main/java/com/possaas/modules/menu/service/strategy/MenuItemCreationStrategy.java
- src/main/java/com/possaas/modules/order/application/port/ActiveOrderRecipeQuery.java
- src/main/java/com/possaas/modules/order/application/port/OrderInventoryAllocationCommand.java
- src/main/java/com/possaas/modules/order/entity/OrderItemRecipeSnapshot.java
- src/main/java/com/possaas/modules/order/repository/OrderItemRecipeSnapshotRepository.java
- src/main/java/com/possaas/modules/order/service/InventoryRecipeUsageCostAdapter.java
- src/main/java/com/possaas/modules/order/service/OrderInventoryAllocationOperation.java
- src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotService.java
- src/main/resources/db/migration/V40__support_direct_stock_menu_items.sql
- src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java
- src/test/java/com/possaas/modules/inventory/InventoryAllocationQueryServiceTest.java
- src/test/java/com/possaas/modules/inventory/LowStockAlertEventPublisherTest.java
- src/test/java/com/possaas/modules/menu/DirectStockContractTest.java
- src/test/java/com/possaas/modules/menu/InventoryStockItemCatalogAdapterTest.java
- src/test/java/com/possaas/modules/menu/MenuIntegrationTest.java
- src/test/java/com/possaas/modules/menu/StockTrackingModeResolverTest.java
- src/test/java/com/possaas/modules/order/DirectStockIntegrationTest.java
- src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java
- docs/direct-stock-implementation-progress.md
- docs/direct-stock-menu-items-fe-sync.md
- docs/inventory-alert-realtime.md
- docs/inventory-api.md
- docs/low-stock-alert-api.md
- docs/openapi/tenant-api.yaml
- docs/order-inventory-allocation-api.md
- docs/order-inventory-allocation-implementation.md
- docs/order-reference-cost-snapshot-api.md
- docs/stock-audit-api.md
- docs/tenant-api.md
