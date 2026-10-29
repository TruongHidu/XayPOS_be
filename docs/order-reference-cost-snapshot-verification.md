# Historical reference cost Phase 2 — verification and handoff

Verified on 2026-10-27T15:37:01.373162+00:00.

## Scope and environment

- Repository: `D:\Đồ án tốt nghiệp\Code\pos-saas-be`; baseline HEAD `0f96caa`.
- Tested staging copy: `C:\Users\Dell\OneDrive\Documents\New project\order-reference-cost-be-work-20261027`.
- Original baseline: `C:\Users\Dell\OneDrive\Documents\New project\order-reference-cost-before-20261027`.
- JDK 24 runtime / project release 21, Maven 3.9.9, Spring Boot 4.1.1.
- Task-owned PostgreSQL 17 container `pos-saas-reference-cost-test-20261027`,
  loopback port 15442 and database `pos_reference_cost_test`; no project data mounts.
- Random default schema and per-class schemas; UTC JVM, Spring test context cache size 4.
- No working/production database was used for migration or tests.

## Executed checks

- Main compilation passed.
- Full `mvn -q test`: **1257 tests, 0 failures, 0 errors, 0 skipped**,
  across 109 classes.
- Log: `C:\Users\Dell\OneDrive\Documents\New project\order-reference-cost-be-work-20261027\reference-cost-full-tests.log`.
- ORDER and tenant OpenAPI parsed with SnakeYAML 2.6, duplicate keys rejected.
- V1–V37 SQL bytes match the baseline; only V38 is added.
- Empty-schema migration and realistic V37 → V38 upgrades passed.
- PostgreSQL tests preserve old snapshots/usages/allocations/reservations/ledger,
  reject invalid monetary combinations/aggregates/NaN/linkage and mismatched ledger prices,
  and retain immutable/deferred/append-only guards.
- Old RESERVED/CONSUMED history remains consumable/releasable/valid with null prices;
  no current catalogue prices are used to reconstruct old money.

## Behaviour and access verified

- MANUAL positive/zero and unknown costs; RECIPE known/missing tracked/untracked costs.
- HALF_UP line amounts from existing CEILING required quantities; authoritative total
  independent of rounded display unit cost; fractional yields/quantity, tiny values, overflow.
- Capture only fresh SUBMIT/confirm/append/QR approval; drafts/pending/review/GET do not capture.
- Replay does not query live prices; catalogue/receipt/toggle/recipe/feature changes preserve history.
- Concurrent manual update or receipt repricing produces a consistent serialized acceptance price.
- Known/zero RECIPE_USE prices match frozen tracked usage; unknown/legacy remain null,
  including a known tracked usage in an otherwise incomplete recipe header.
- Missing prices do not prevent valid reserve/cook/serve/pay/complete operations.
- Cancel releases reservations without fake consumption or automatic physical returns.
- Failed audit/reservation/ledger/approval rolls back the whole command and committed events.
- Canonical order events/root versions keep their existing mutation boundaries.
- Cost API requires ORDER_VIEW + REPORT_DETAIL_VIEW and ORDER_MANAGEMENT + DETAIL_REPORT,
  before order/snapshot reads, with one entitlement read for this guard.
- Acceptance uses one loaded entitlement for recipe/inventory decisions; existing outer
  guards remain. DETAIL_REPORT never gates capture. Historical inventory reads no entitlement.
- The summary contains no ingredients. Ordinary order/recipe/public QR/KDS/realtime responses
  do not acquire monetary fields or broader permissions.

## API and limitations

`GET /api/v1/orders/{orderId}/items/{orderItemId}/cost-snapshot` returns a no-store summary.
COMPLETE is a full known reference amount; INCOMPLETE/UNAVAILABLE have null amounts.
Old rows return LEGACY_UNAVAILABLE and drafts NOT_SUBMITTED without synthesizing money.
Reference cost is not actual COGS/FIFO or proof of consumption. No profit dashboard,
currency/unit conversion, revaluation/backfill, frontend or new realtime messages are included.

See [API/Postman](order-reference-cost-snapshot-api.md),
[implementation](order-reference-cost-snapshot-implementation.md),
[FE synchronization](order-reference-cost-snapshot-fe-sync.md).

## Promotion and provenance

Before/after SHA256 values are recorded in
`C:\Users\Dell\OneDrive\Documents\New project\order-reference-cost-promotion-manifest-20261027.json`.
The handoff script checks every source and destination before writing and rechecks
each destination immediately before copying; changed baseline files stop promotion.
The per-file verified result is written to
`C:\Users\Dell\OneDrive\Documents\New project\order-reference-cost-promotion-result-20261027.json`.
No Git commit/reset or target_old edits are part of this task.

## Files added or changed

- `README.md`
- `src/main/java/com/possaas/modules/inventory/application/port/RecipeUsageCostQuery.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryTransaction.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAllocationPolicy.java`
- `src/main/java/com/possaas/modules/inventory/service/OrderInventoryAllocationAdapter.java`
- `src/main/java/com/possaas/modules/menu/service/OrderReferenceCostAdapter.java`
- `src/main/java/com/possaas/modules/order/application/port/OrderInventoryAllocationCommand.java`
- `src/main/java/com/possaas/modules/order/application/port/OrderReferenceCostQuery.java`
- `src/main/java/com/possaas/modules/order/controller/OrderCostSnapshotController.java`
- `src/main/java/com/possaas/modules/order/dto/OrderCostSnapshotResponse.java`
- `src/main/java/com/possaas/modules/order/entity/OrderCostSnapshot.java`
- `src/main/java/com/possaas/modules/order/entity/OrderCostStatus.java`
- `src/main/java/com/possaas/modules/order/entity/OrderCostUnavailableReason.java`
- `src/main/java/com/possaas/modules/order/entity/OrderIngredientCostSnapshot.java`
- `src/main/java/com/possaas/modules/order/entity/OrderItemRecipeSnapshot.java`
- `src/main/java/com/possaas/modules/order/entity/OrderItemRecipeUsage.java`
- `src/main/java/com/possaas/modules/order/repository/OrderItemRecipeUsageRepository.java`
- `src/main/java/com/possaas/modules/order/service/cost/OrderCostSnapshotPreparation.java`
- `src/main/java/com/possaas/modules/order/service/cost/OrderReferenceCostCalculator.java`
- `src/main/java/com/possaas/modules/order/service/InventoryRecipeUsageCostAdapter.java`
- `src/main/java/com/possaas/modules/order/service/OrderCostSnapshotAccess.java`
- `src/main/java/com/possaas/modules/order/service/OrderCostSnapshotQueryService.java`
- `src/main/java/com/possaas/modules/order/service/OrderInventoryAllocationOperation.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotService.java`
- `src/main/resources/db/migration/V38__snapshot_order_item_reference_costs.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/migration/OrderReferenceCostMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAllocationPersistenceIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryFrozenCostPolicyTest.java`
- `src/test/java/com/possaas/modules/order/OrderCostSnapshotPreparationTest.java`
- `src/test/java/com/possaas/modules/order/OrderCostSnapshotQueryServiceTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationOperationTest.java`
- `src/test/java/com/possaas/modules/order/OrderOpenApiTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotServiceTest.java`
- `src/test/java/com/possaas/modules/order/OrderReferenceCostCalculatorTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java`
- `docs/inventory-api.md`
- `docs/openapi/order-api.yaml`
- `docs/openapi/tenant-api.yaml`
- `docs/order-api.md`
- `docs/order-inventory-allocation-api.md`
- `docs/order-inventory-allocation-fe-sync.md`
- `docs/order-inventory-allocation-implementation.md`
- `docs/order-realtime-implementation.md`
- `docs/order-recipe-snapshot-api.md`
- `docs/order-recipe-snapshot-implementation.md`
- `docs/order-reference-cost-snapshot-api.md`
- `docs/order-reference-cost-snapshot-fe-sync.md`
- `docs/order-reference-cost-snapshot-implementation.md`
- `docs/public-qr-order-api.md`
- `docs/recipe-application-and-cost-config-api.md`
- `docs/recipe-application-and-cost-config-fe-sync.md`
- `docs/recipe-application-and-cost-config-implementation.md`
- `docs/report-api.md`
- `docs/order-reference-cost-snapshot-verification.md`

## Full-run suite counts

| Class | Tests | Failures | Errors | Skipped |
| --- | ---: | ---: | ---: | ---: |
| com.possaas.admin.AdminManagementIntegrationTest | 6 | 0 | 0 | 0 |
| com.possaas.AuthIntegrationTest | 1 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.InventoryRealtimeBroadcasterTest | 3 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.InventoryRealtimeInboundTest | 7 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.InventoryRealtimeWebSocketTest | 6 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.RealtimeAccessTest | 10 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.RealtimeExtensionTest | 6 | 0 | 0 | 0 |
| com.possaas.infrastructure.realtime.RealtimeSessionsTest | 5 | 0 | 0 | 0 |
| com.possaas.migration.FlywayCleanMigrationIntegrationTest | 20 | 0 | 0 | 0 |
| com.possaas.migration.MenuAvailabilityPermissionMigrationIntegrationTest | 2 | 0 | 0 | 0 |
| com.possaas.migration.OrderReferenceCostMigrationIntegrationTest | 4 | 0 | 0 | 0 |
| com.possaas.migration.PackageTierMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| com.possaas.migration.QrOrderMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| com.possaas.migration.QrPackageFeaturesMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| com.possaas.migration.ReportIndexesMigrationIntegrationTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.admin.service.AdminDashboardQueryServiceTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.audit.service.AuditDataRedactorTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.inventory.controller.StockAuditConflictHandlerTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryAllocationOpenApiTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryAllocationPersistenceIntegrationTest | 11 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryAllocationQueryServiceTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryAvailabilityPolicyTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryFrozenCostPolicyTest | 8 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryIntegrationTest | 19 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.inventory.InventoryPolicyTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertEventPublisherTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertIntegrationTest | 23 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertPersistenceTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertPolicyTest | 16 | 0 | 0 | 0 |
| com.possaas.modules.inventory.LowStockAlertRealtimeWiringTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.inventory.ReceiptReferenceCostServiceTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.inventory.StockAuditIntegrationTest | 36 | 0 | 0 | 0 |
| com.possaas.modules.inventory.StockAuditOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.inventory.StockAuditPolicyTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.inventory.StockMovementStrategyTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.inventory.WeightedAverageCostPolicyTest | 18 | 0 | 0 | 0 |
| com.possaas.modules.menu.IngredientCostPriceProviderTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.menu.IngredientIntegrationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.menu.IngredientPolicyTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.menu.MenuIntegrationTest | 21 | 0 | 0 | 0 |
| com.possaas.modules.menu.MenuOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.menu.MenuPolicyTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.menu.OrderRecipeQueryAdapterTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicQrMenuIntegrationTest | 27 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicQrMenuOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicQrMenuPolicyTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicRestaurantMenuIntegrationTest | 29 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicRestaurantMenuOpenApiTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.menu.PublicRestaurantMenuPolicyTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.menu.RecipeCostCalculatorTest | 21 | 0 | 0 | 0 |
| com.possaas.modules.menu.RecipeCostEstimateIntegrationTest | 17 | 0 | 0 | 0 |
| com.possaas.modules.menu.RecipeCostEstimateQueryServiceTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.menu.RecipeIntegrationTest | 8 | 0 | 0 | 0 |
| com.possaas.modules.menu.ReferenceCostConfigurationPolicyTest | 14 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderBulkItemTransitionIntegrationTest | 51 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderBulkItemTransitionOpenApiTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderBulkItemTransitionPolicyTest | 10 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderChainTest | 10 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCompletionPolicyTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCompletionServiceTest | 14 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCompletionSessionIntegrationTest | 44 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCompletionSessionOpenApiTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCostSnapshotPreparationTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderCostSnapshotQueryServiceTest | 10 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderIntegrationTest | 121 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryAllocationIntegrationTest | 90 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryAllocationOperationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryReplayValidationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderOpenApiTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderPolicyTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderQrApprovalOperationTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderRecipeSnapshotIntegrationTest | 32 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderRecipeSnapshotServiceTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderReferenceCostCalculatorTest | 20 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderTablePolicyTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.order.RecipeUsageQuantityCalculatorTest | 19 | 0 | 0 | 0 |
| com.possaas.modules.payment.PaymentPolicyTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.controller.QrStaffSnapshotConflictTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrGuestPolicyTest | 9 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrLifecycleServiceTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrOrderBodyLimitFilterTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrOrderIntegrationTest | 80 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrOrderOpenApiTest | 9 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrRateLimiterTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrRequestDtoContractTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrScopePolicyTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrStaffApprovalPolicyTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.report.ReportIntegrationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.report.ReportRangePolicyTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.subscription.controller.SubscriptionConflictHandlerTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.subscription.domain.SubscriptionFeatureSnapshotTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.subscription.domain.SubscriptionValidityPolicyTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.subscription.PackageSelectionIntegrationTest | 30 | 0 | 0 | 0 |
| com.possaas.modules.subscription.service.FeatureSnapshotFactoryTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.subscription.service.SubscriptionCommandServiceClockTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.subscription.service.SubscriptionExpirationServiceClockTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.subscription.SubscriptionModuleIntegrationTest | 24 | 0 | 0 | 0 |
| com.possaas.modules.table.TableIntegrationTest | 22 | 0 | 0 | 0 |
| com.possaas.modules.table.TableOpenApiTest | 1 | 0 | 0 | 0 |
| com.possaas.modules.table.TablePolicyTest | 8 | 0 | 0 | 0 |
| com.possaas.modules.table.TableSessionTransferIntegrationTest | 25 | 0 | 0 | 0 |
| com.possaas.modules.table.TableSessionTransferPolicyTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.user.AdminRestaurantUserIntegrationTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.user.StaffConcurrencyIntegrationTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.user.StaffPolicyTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.user.TenantPhaseOneIntegrationTest | 13 | 0 | 0 | 0 |
| com.possaas.PosSaasBeApplicationTests | 1 | 0 | 0 | 0 |
