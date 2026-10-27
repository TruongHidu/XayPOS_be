# Recipe configuration Phase 1 — verification and handoff

Verified on 2026-10-26T16:02:33.176500+00:00.

## Environment and scope

- Repository: `D:\Đồ án tốt nghiệp\Code\pos-saas-be`.
- Tested staging copy: `C:\Users\Dell\OneDrive\Documents\New project\recipe-config-be-work-20261026`.
- Baseline copy: `C:\Users\Dell\OneDrive\Documents\New project\recipe-config-before-20261026`; baseline repository HEAD `4036dc9`.
- Java runtime JDK 24, Maven 3.9.9, project compiler release 21, Spring Boot 4.1.1.
- PostgreSQL 17 task-owned container `pos-saas-recipe-config-test-20261026`, loopback port 15441,
  database `pos_recipe_config_test`, random default schema and per-class isolated schemas.
- `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC -Dspring.test.context.cache.maxSize=4`.
- No working/production database, H2 or project-data volume was used.

## Executed checks

- Compilation: passed.
- Full `mvn -q test`: **1189 tests, 0 failures, 0 errors,
  0 skipped**, across 104 test classes.
- Full run log: `C:\Users\Dell\OneDrive\Documents\New project\recipe-config-be-work-20261026\recipe-config-full-tests-final.log`.
- OpenAPI `tenant-api.yaml` and `order-api.yaml` parsed using SnakeYAML 2.6 with duplicate keys rejected.
- V1–V36 migration bytes match the original baseline; only V37 was added.
- V36 → V37 upgrade tests include existing positive/zero prices, active definitions, immutable
  CAPTURED headers/usages, RESERVED/CONSUMED allocations, receipt ledger and null-cost RECIPE_USE.
  Existing history remains byte-equivalent (excluding newly added nullable columns); a legacy
  reservation remains consumable after upgrade and switching the current catalogue off.
- Clean migration from an empty PostgreSQL schema passed.
- Database tests retain immutable snapshot/usage/ledger guards and reject inconsistent new
  price/application pairs, snapshot decisions and allocation skip values.
- Regression includes menu/ingredient/recipe, staff/order/QR approval, table transfer, payment,
  complete/close, inventory/documents/alerts/stock-audit, permissions and realtime.

Earlier runs exposed detached fixture versions and a SQL fixture that specified a positive
cost without its configured flag. These fixtures now express their intended state explicitly.
Production constraints and business assertions were retained.

## Behaviour verified

- Missing price differs from explicit zero; clear/confirm/keep follow one shared policy.
- Cost-only and configuration-only changes use normal audit/version/timestamp behaviour.
- A fresh menu item is recipe-disabled; an active definition does not automatically enable it.
- Enabling requires valid definition/units/ingredients and recipe entitlement; prices may be unknown.
- Disabled application ignores unapplied invalid definitions and needs no warehouse.
- SUBMIT/confirm/append and QR approval freeze application/source; drafts and guest pending do not.
- Both toggle/cancel directions preserve the original allocation; later cooking/bulk served uses
  original snapshots, and skipped allocations never acquire stock later.
- Append/replay, package feature change and concurrent toggle/SUBMIT keep consistent decisions.
- Failed decision audit rolls back order/snapshots/allocation and emits no committed realtime event.
- Successful acceptance retains one canonical event; changing MENU does not dirty ORDER version.
- Unconfigured ingredient cost returns 409; explicitly configured zero keeps its warning.
- Receipt into zero stock establishes a configured price, including zero. Positive unvalued opening
  stock stays unconfigured and creates a skipped-recalculation audit rather than a fake average.

## Promotion and provenance

The promotion manifest records before/after SHA256 for every changed file:
`C:\Users\Dell\OneDrive\Documents\New project\recipe-config-promotion-manifest-20261026.json`.
The promotion script preflights all destinations and sources, then rechecks each destination
immediately before copying. It rejects changed baseline files and migration-version drift.
The per-file result is recorded in `C:\Users\Dell\OneDrive\Documents\New project\recipe-config-promotion-result-20261026.json`.
No Git commit/reset or target_old edits are part of this change.

## Frontend and remaining phase

See [API examples](recipe-application-and-cost-config-api.md),
[FE synchronization](recipe-application-and-cost-config-fe-sync.md), and
[implementation](recipe-application-and-cost-config-implementation.md).

Phase 2 still needs immutable monetary costing/manual-cost snapshots and profit reporting.
This phase leaves RECIPE_USE.unit_cost null, does not backfill historical values and does not
add FIFO, currency/unit conversion, frontend implementation or new realtime messages.

## Files added or changed

- `README.md`
- `src/main/java/com/possaas/modules/inventory/application/port/IngredientReferenceCostWriter.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryAllocationSkipReason.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAllocationPolicy.java`
- `src/main/java/com/possaas/modules/inventory/service/ReceiptReferenceCostService.java`
- `src/main/java/com/possaas/modules/menu/dto/IngredientRequests.java`
- `src/main/java/com/possaas/modules/menu/dto/IngredientResponse.java`
- `src/main/java/com/possaas/modules/menu/dto/MenuItemResponse.java`
- `src/main/java/com/possaas/modules/menu/dto/MenuRequests.java`
- `src/main/java/com/possaas/modules/menu/entity/Item.java`
- `src/main/java/com/possaas/modules/menu/repository/ItemRepository.java`
- `src/main/java/com/possaas/modules/menu/service/cost/IngredientCostPriceProvider.java`
- `src/main/java/com/possaas/modules/menu/service/cost/IngredientCostProvider.java`
- `src/main/java/com/possaas/modules/menu/service/cost/RecipeCostCalculator.java`
- `src/main/java/com/possaas/modules/menu/service/IngredientAudit.java`
- `src/main/java/com/possaas/modules/menu/service/IngredientCommandService.java`
- `src/main/java/com/possaas/modules/menu/service/IngredientResponseMapper.java`
- `src/main/java/com/possaas/modules/menu/service/InventoryIngredientCostAdapter.java`
- `src/main/java/com/possaas/modules/menu/service/MenuAudit.java`
- `src/main/java/com/possaas/modules/menu/service/MenuItemCommandService.java`
- `src/main/java/com/possaas/modules/menu/service/OrderRecipeQueryAdapter.java`
- `src/main/java/com/possaas/modules/menu/service/RecipeApplicationPolicy.java`
- `src/main/java/com/possaas/modules/menu/service/RecipeCommandService.java`
- `src/main/java/com/possaas/modules/menu/service/ReferenceCostConfigurationPolicy.java`
- `src/main/java/com/possaas/modules/menu/service/strategy/IngredientCreationStrategy.java`
- `src/main/java/com/possaas/modules/menu/service/strategy/ItemCreationContext.java`
- `src/main/java/com/possaas/modules/menu/service/strategy/MenuItemCreationStrategy.java`
- `src/main/java/com/possaas/modules/order/application/port/ActiveOrderRecipeQuery.java`
- `src/main/java/com/possaas/modules/order/dto/OrderRecipeSnapshotResponse.java`
- `src/main/java/com/possaas/modules/order/entity/OrderCostSource.java`
- `src/main/java/com/possaas/modules/order/entity/OrderItemRecipeSnapshot.java`
- `src/main/java/com/possaas/modules/order/entity/RecipeSnapshotStatus.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotQueryService.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotService.java`
- `src/main/resources/db/migration/V37__configure_recipe_application_and_reference_costs.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAllocationPersistenceIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/LowStockAlertIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/ReceiptReferenceCostServiceTest.java`
- `src/test/java/com/possaas/modules/inventory/StockAuditIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/IngredientCostPriceProviderTest.java`
- `src/test/java/com/possaas/modules/menu/IngredientIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/IngredientPolicyTest.java`
- `src/test/java/com/possaas/modules/menu/MenuPolicyTest.java`
- `src/test/java/com/possaas/modules/menu/OrderRecipeQueryAdapterTest.java`
- `src/test/java/com/possaas/modules/menu/PublicQrMenuIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/PublicRestaurantMenuIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/RecipeCostEstimateIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/RecipeIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/ReferenceCostConfigurationPolicyTest.java`
- `src/test/java/com/possaas/modules/order/OrderBulkItemTransitionIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionSessionIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotServiceTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/table/TableSessionTransferIntegrationTest.java`
- `docs/ingredient-api.md`
- `docs/openapi/order-api.yaml`
- `docs/openapi/tenant-api.yaml`
- `docs/order-inventory-allocation-api.md`
- `docs/order-inventory-allocation-implementation.md`
- `docs/order-recipe-snapshot-api.md`
- `docs/order-recipe-snapshot-implementation.md`
- `docs/recipe-api.md`
- `docs/recipe-application-and-cost-config-api.md`
- `docs/recipe-application-and-cost-config-fe-sync.md`
- `docs/recipe-application-and-cost-config-implementation.md`
- `docs/tenant-api.md`
- `docs/recipe-application-and-cost-config-verification.md`

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
| com.possaas.modules.order.OrderIntegrationTest | 121 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryAllocationIntegrationTest | 75 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryAllocationOperationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderInventoryReplayValidationTest | 6 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderOpenApiTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderPolicyTest | 13 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderQrApprovalOperationTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderRecipeSnapshotIntegrationTest | 32 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderRecipeSnapshotServiceTest | 5 | 0 | 0 | 0 |
| com.possaas.modules.order.OrderTablePolicyTest | 7 | 0 | 0 | 0 |
| com.possaas.modules.order.RecipeUsageQuantityCalculatorTest | 19 | 0 | 0 | 0 |
| com.possaas.modules.payment.PaymentPolicyTest | 3 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.controller.QrStaffSnapshotConflictTest | 2 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrGuestPolicyTest | 9 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrLifecycleServiceTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrOrderBodyLimitFilterTest | 4 | 0 | 0 | 0 |
| com.possaas.modules.qrorder.QrOrderIntegrationTest | 77 | 0 | 0 | 0 |
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
