# Triển khai Giai đoạn 5 — allocation nguyên liệu

> Phase 1 update (V37): V37 adds SNAPSHOT_RECIPE_DISABLED to the existing enum/CHECK. Catalogue changes do not rebuild allocations. Reservations, deferred history guards, append-only ledger and null RECIPE_USE unit costs retain their existing contracts. See [configuration API](recipe-application-and-cost-config-api.md) and [FE sync](recipe-application-and-cost-config-fe-sync.md).

ORDER sở hữu lifecycle và recipe snapshot. INVENTORY sở hữu allocation,
reservation, balance và ledger. MENU sở hữu ingredient. Module trao đổi qua
application port với immutable record; ORDER không truy cập entity/repository
INVENTORY và MENU hỏi usage/reservation qua port.

## Transaction và khóa

Acceptance chốt snapshot rồi lập allocation theo batch ở persist operation.
Create SUBMIT/confirm OPEN, append OPEN, append đang phục vụ và hai nhánh QR approve
đều tham gia transaction command hiện tại. Prepare/read/review không ghi dữ liệu.
Reserve/consume/release đồng bộ, transaction nội bộ yêu cầu caller transaction;
không async, AFTER_COMMIT hoặc REQUIRES_NEW.

Các điểm tích hợp cụ thể:

| Thành phần | Vai trò |
| --- | --- |
| OrderInventoryAllocationOperation | Capture snapshot, map tracked usages nguyên lượng sang OrderInventoryAllocationCommand; validate/consume/release qua port |
| OrderConfirmationOperation | Acceptance chung cho create SUBMIT, confirm OPEN, append SUBMIT vào OPEN và QR tạo mới |
| AddOrderItemsPersistHandler | Acceptance chỉ các dòng mới của append order đang phục vụ |
| OrderQrApprovalOperation | Acceptance chỉ các dòng mới của QR append |
| OrderItemTransitionService / OrderBulkItemTransitionService | Kiểm quyết định đã lưu; consume phần rời PENDING |
| OrderCommandService | Release batch cho root/line cancel, tránh release lại dòng cuối |
| OrderCompletionService | Validate trước hoàn tất, không consume bù |
| OrderInventoryAllocationAdapter | INVENTORY thực hiện eligibility, batch persistence, balance và ledger |
| InventoryAllocationQueryService | Đọc allocation với REPEATABLE_READ, kiểm quyền/feature và nhất quán, không mutation |

Timestamp nghiệp vụ dùng injected Clock của command caller. Allocation GET không
lấy catalog live để tạo lại lịch sử; regular order/KDS/QR DTO không thêm nguyên liệu.

Thứ tự khóa bắt đầu restaurant pessimistic write tới commit, rồi resource của
command và allocation/balance theo ID ổn định. Stock RECEIPT giữ ingredient lock
trước đọc physical tổng/cập nhật cost. Các command trong một restaurant dùng cùng
mutation lock; không khóa toàn hệ thống hoặc tenant khác.

Reserve đọc snapshot/usages và quyết định đã có theo batch, đánh giá eligibility,
chọn default active, gom requiredQuantity theo warehouse+ingredient trước kiểm
available, rồi tạo header/detail và tăng reserved cùng transaction. Balance chưa
có row nghĩa là physical=reserved=0; nhu cầu dương thiếu tồn, không tạo tồn ảo.
Overflow riêng và tổng bị từ chối trước NUMERIC persistence.

Consume chỉ chuyển RESERVED -> CONSUMED: physical và reserved cùng giảm đúng
reservation.quantity; một ledger RECIPE_USE/detail. Không kiểm available như lần
bán mới vì nguyên liệu đã thuộc reservation. Release chuyển RESERVED -> RELEASED
và chỉ giảm reserved. SKIPPED/CONSUMED/RELEASED là terminal. Duplicate phải khớp
identity/kho/quantity/state; không dùng ON CONFLICT DO NOTHING che dữ liệu lệch.
Thiếu allocation hoặc terminal/ledger lệch trả conflict, không tự chữa lịch sử.

Batch transition atomic: lỗi bất kỳ rollback order/items/history, balance,
allocation, ledger, audit và idempotency. Cancel-line cuối không release lại ở
root cancel. Complete chỉ kiểm nhất quán, không consume bù. Quyết định đã reserve
vẫn được hoàn tất khi entitlement/catalog/default warehouse thay đổi.

## Persistence và legacy

`V33__reserve_and_consume_order_ingredients.sql` thêm reserved_quantity NUMERIC(14,3)
vào inventory_balances, các check 0 <= reserved <= physical, allocation và
reservation tables, tenant composite FK/unique/index và bảo vệ dữ liệu bất biến.
Một allocation/order item, một reservation/recipe usage, một RECIPE_USE/reservation.
Identity, warehouse, usage và quantity không đổi; không append detail sau commit.
Không CASCADE xóa lịch sử. V1–V32 giữ nguyên.

Ledger thêm source_type mặc định STOCK_DOCUMENT cho lịch sử; không sửa dữ liệu
ledger cũ qua trigger. STOCK_DOCUMENT giữ document references và unitCost hợp lệ;
ORDER_ITEM chỉ dùng RECIPE_USE, order/item/reservation references, không có document
và unitCost=null. Query đọc cả hai nguồn bằng snapshot tương ứng, không INNER JOIN
chứng từ làm mất RECIPE_USE và không Enum.valueOf(null). Ledger vẫn append-only.

Upgrade V32 tạo SKIPPED/LEGACY_UNAVAILABLE cho mọi line đã gửi xác định bằng
sent_to_kitchen_at, kể cả snapshot CAPTURED. Không hồi tố reserve/trừ kho/ledger,
không thay snapshot, order, balance hay business audit cũ. DRAFT chưa gửi không có
allocation và đánh giá bình thường khi gửi sau nâng cấp. Sau V33, thiếu allocation
ở line đã gửi là lỗi tích hợp, không tự coi là legacy.

Cache reserved phải bằng SUM reservation detail của allocation RESERVED theo
tenant/warehouse/ingredient. ISSUE/ADJUSTMENT giảm chỉ được dùng available;
RECEIPT tăng physical mà giữ reserved. Weighted average vẫn dùng physical trước
nhập trên tất cả kho; reserve/release không đổi trọng số, consume không tự reprice.

Kho còn reservation không được disable/delete; đổi default được phép. Ingredient
còn reservation không được đổi baseUnit, bỏ tracking, deactivate/delete. Giữ guard
active recipe/nonzero stock hiện có. Đổi tên/SKU/cost/version recipe không sửa
reservation. Không tự đổi availabilityStatus món khi thiếu ingredient.

## API, audit và kiểm tra

Allocation GET chỉ đọc và no-store, cần ORDER_VIEW+RECIPE_VIEW+INVENTORY_VIEW và
ba feature quản lý tương ứng. Không thêm quyền kho cho staff order/kitchen.
Balance giữ quantity physical, thêm reserved/available; lowStock không đổi.
Transactions phân trang DB và giữ tenant isolation, thêm source/order references.

Audit ORDER_ITEM_INVENTORY_RESERVED/CONSUMED/RELEASED/SKIPPED ghi summary IDs trong
transaction. Không copy recipe, không audit giả khi rollback/migration/replay.
Các test PostgreSQL allocation bổ sung acceptance/eligibility/consume/release,
rollback, stock guards, concurrency, sparse API và permission/privacy; test migration
kiểm upgrade dữ liệu cũ và constraint.

## Kết quả kiểm thử và xác minh bàn giao

Đối chiếu ngày 21/10/2026 với báo cáo của lần chạy toàn bộ Maven ngày 20/10/2026
trên repository backend chính:

- **86 suites, 983 tests, 0 failures, 0 errors, 0 skipped**. Toàn bộ 86 báo cáo
  thuộc cùng lần chạy, không lấy lẫn báo cáo test cũ.
- Baseline Giai đoạn 4 có 888 test; Giai đoạn 5 bổ sung 95 test.
- Allocation integration: 47 test; ràng buộc PostgreSQL allocation: 11 test;
  allocation query service: 13 test; QR order: 69 test; Flyway: 16 test.
- Đã kiểm tra migration từ schema rỗng và nâng cấp V32 có dữ liệu, bao gồm dòng
  CAPTURED, dòng đã gửi thiếu snapshot, DRAFT và ledger chứng từ cũ.
- SHA256 của 79 file bàn giao khớp bản triển khai được kiểm thử; V1–V32 không đổi.
  Không sửa frontend hoặc artifact trong target_old.
- Môi trường chạy: Maven 3.9.9, JDK 24 (compiler release 21), PostgreSQL 17.11.
  Chưa chạy riêng trên runtime JDK 21 trong môi trường này.

Lệnh đã dùng để chạy toàn bộ test, tại thư mục backend (PowerShell):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
& 'D:\apache-maven-3.9.9\bin\mvn.cmd' -q `
  '-Dmaven.repo.local=C:\Users\Dell\.m2\repository' `
  '-Ddebug=false' `
  '-Dspring.test.context.cache.maxSize=2' `
  '-Dspring.datasource.hikari.maximum-pool-size=5' `
  '-Dspring.datasource.hikari.minimum-idle=0' `
  '-Dspring.jpa.show-sql=false' `
  '-Dlogging.level.org.springframework=WARN' `
  '-Dlogging.level.org.hibernate.SQL=OFF' test
```

Các tham số context/connection/logging chỉ áp dụng cho lần chạy test, không đổi
cấu hình production. Báo cáo nằm ở `target/surefire-reports`. Các test allocation
và migration dùng schema kiểm thử riêng, có kiểm tra tên schema trước khi dọn dẹp.

Không triển khai giá vốn order/FIFO, chuyển kho/partial allocation, unit conversion,
trả món đã nấu, refund stock, job sửa dữ liệu hoặc tự expire reservation.

API và Postman: [order-inventory-allocation-api.md](order-inventory-allocation-api.md).
FE: [order-inventory-allocation-fe-sync.md](order-inventory-allocation-fe-sync.md).

## Danh sách file thay đổi (79)

- `src/main/java/com/possaas/modules/inventory/application/port/OrderAllocationItemQuery.java`
- `src/main/java/com/possaas/modules/inventory/controller/InventoryAllocationController.java`
- `src/main/java/com/possaas/modules/inventory/controller/InventoryAllocationErrorHandler.java`
- `src/main/java/com/possaas/modules/inventory/dto/InventoryAllocationResponse.java`
- `src/main/java/com/possaas/modules/inventory/dto/InventoryBalanceResponse.java`
- `src/main/java/com/possaas/modules/inventory/dto/InventoryTransactionResponse.java`
- `src/main/java/com/possaas/modules/inventory/dto/InventoryTransactionSearch.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryAllocationSkipReason.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryAllocationStatus.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryBalance.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryReservation.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventorySourceType.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryTransaction.java`
- `src/main/java/com/possaas/modules/inventory/entity/InventoryTransactionType.java`
- `src/main/java/com/possaas/modules/inventory/entity/OrderItemInventoryAllocation.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryAllocationQueryRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryBalanceQueryRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryBalanceRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryReservationRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryTransactionQueryRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/InventoryTransactionWriteRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/OrderItemInventoryAllocationRepository.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAllocationAudit.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAllocationPolicy.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAllocationQueryService.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAvailabilityPolicy.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryBalanceResponseMapper.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryConflictTranslator.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryStockUsageQueryAdapter.java`
- `src/main/java/com/possaas/modules/inventory/service/OrderInventoryAllocationAdapter.java`
- `src/main/java/com/possaas/modules/inventory/service/StockPostingService.java`
- `src/main/java/com/possaas/modules/inventory/service/WarehouseLifecyclePolicy.java`
- `src/main/java/com/possaas/modules/menu/application/port/InventoryStockUsageQuery.java`
- `src/main/java/com/possaas/modules/menu/service/IngredientCommandService.java`
- `src/main/java/com/possaas/modules/order/application/port/OrderInventoryAllocationCommand.java`
- `src/main/java/com/possaas/modules/order/controller/OrderConflictHandler.java`
- `src/main/java/com/possaas/modules/order/repository/OrderItemRecipeUsageRepository.java`
- `src/main/java/com/possaas/modules/order/service/OrderAllocationItemQueryAdapter.java`
- `src/main/java/com/possaas/modules/order/service/OrderBulkItemTransitionService.java`
- `src/main/java/com/possaas/modules/order/service/OrderCommandService.java`
- `src/main/java/com/possaas/modules/order/service/OrderCompletionService.java`
- `src/main/java/com/possaas/modules/order/service/OrderConfirmationOperation.java`
- `src/main/java/com/possaas/modules/order/service/OrderInventoryAllocationOperation.java`
- `src/main/java/com/possaas/modules/order/service/OrderInventoryPersistenceConflict.java`
- `src/main/java/com/possaas/modules/order/service/OrderItemTransitionService.java`
- `src/main/java/com/possaas/modules/order/service/OrderQrApprovalOperation.java`
- `src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsPersistHandler.java`
- `src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsReplayHandler.java`
- `src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderReplayHandler.java`
- `src/main/java/com/possaas/modules/qrorder/controller/QrStaffErrorHandler.java`
- `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalReplayHandler.java`
- `src/main/resources/db/migration/V33__reserve_and_consume_order_ingredients.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAllocationOpenApiTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAllocationPersistenceIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAllocationQueryServiceTest.java`
- `src/test/java/com/possaas/modules/inventory/InventoryAvailabilityPolicyTest.java`
- `src/test/java/com/possaas/modules/order/OrderCompletionServiceTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationOperationTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryReplayValidationTest.java`
- `src/test/java/com/possaas/modules/order/OrderQrApprovalOperationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotIntegrationTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrStaffApprovalPolicyTest.java`
- `docs/ingredient-api.md`
- `docs/inventory-api.md`
- `docs/kitchen-completion-api.md`
- `docs/order-api.md`
- `docs/order-inventory-allocation-api.md`
- `docs/order-inventory-allocation-fe-sync.md`
- `docs/order-inventory-allocation-implementation.md`
- `docs/order-recipe-snapshot-api.md`
- `docs/public-qr-order-api.md`
- `docs/openapi/kitchen-lifecycle-api.yaml`
- `docs/openapi/order-api.yaml`
- `docs/openapi/public-qr-order-api.yaml`
- `docs/openapi/tenant-api.yaml`
- `README.md`
