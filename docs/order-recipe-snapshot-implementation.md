# Triển khai ORDER recipe snapshot — Giai đoạn 4

> V38 extends the same immutable header/usages with historical reference costs; old monetary fields stay null. Preparation computes quantities and money before header INSERT. Replay uses frozen facts and never prices history from the current catalogue. This recipe response remains free of cost fields. See [monetary implementation](order-reference-cost-snapshot-implementation.md) and [summary API](order-reference-cost-snapshot-api.md).

## Thiết kế và transaction

- ORDER sở hữu hai bảng header/usage, entity bất biến và API đọc riêng.
- MENU adapter triển khai ActiveOrderRecipeQuery của ORDER, trả immutable records.
- Một batch active recipes + recipe lines + ingredients, không đọc từng món trong vòng lặp.
- RecipeUsageQuantityCalculator tính tử số chính xác, chia cuối CEILING scale 3.
- OrderRecipeSnapshotService dùng transaction MANDATORY và restaurant mutation lock của caller.
- Confirm dùng operation chung cho create SUBMIT/confirm/append OPEN/QR create.
- Append đã gửi và QR append chỉ capture added; các chain/Strategy và hash cũ giữ nguyên.
- AuditService.recordAll lưu summary bền vững; V38 thêm monetary decision trong cùng action, không copy ingredient detail hoặc thêm audit trùng.
- Query chỉ đọc snapshot, không gọi MENU và không làm thay đổi order version.

## Migration và dữ liệu cũ

V32 tạo header/usage, tenant composite FK, unique và immutability triggers.
Header CAPTURED phải có usage ở commit (deferred constraint); usage chỉ INSERT trong
transaction tạo header để không thể lén thêm nguyên liệu vào snapshot đã commit.
Dòng đã có sent_to_kitchen_at được marker LEGACY_UNAVAILABLE, recipe/capturedAt null,
không đoán recipe và không sửa dữ liệu ORDER/MENU/inventory/audit cũ.

V1–V31 giữ nguyên. Không có reservation, inventory deduction hoặc warehouse requirement.

## API và kiểm thử

Xem [API/Postman](order-recipe-snapshot-api.md) và [OpenAPI](openapi/order-api.yaml).
Tests bao gồm DRAFT/SUBMIT/append/QR, freeze/no-recipe/feature-disabled/legacy,
permission/tenant/privacy, quantity precision/overflow, rollback audit, concurrency
và migration từ rỗng/nâng cấp V31.

Kết quả kiểm thử ngày 20/10/2026 trên repository backend chính:

- Toàn bộ Maven tests: **79 suites, 888 tests, 0 failures, 0 errors, 0 skipped**;
  Maven trả exit code 0. Có thêm 71 test so với baseline 817 test.
- Integration snapshot: 32 test; QR order: 63 test; Flyway migration: 15 test.
- Đã kiểm tra migration từ rỗng và nâng cấp V31 có dữ liệu; checksum V1–V31
  không thay đổi. PostgreSQL xác thực tenant FK, unique và immutability triggers.
- Môi trường chạy: Maven 3.9.9, JDK 24 (compiler release 21), PostgreSQL 17.11.
  Chưa chạy riêng trên runtime JDK 21 trong môi trường này.

Lệnh chạy toàn bộ test (PowerShell, tại thư mục backend):

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

Các tham số giới hạn connection/context chỉ dùng cho lần chạy test, không sửa
cấu hình production. Báo cáo chi tiết ở `target/surefire-reports`.

## Danh sách file thay đổi (39)

- `README.md`
- `docs/inventory-api.md`
- `docs/openapi/order-api.yaml`
- `docs/order-api.md`
- `docs/order-recipe-snapshot-api.md`
- `docs/order-recipe-snapshot-implementation.md`
- `docs/public-qr-order-api.md`
- `docs/recipe-api.md`
- `docs/tenant-api.md`
- `src/main/java/com/possaas/modules/menu/repository/RecipeItemRepository.java`
- `src/main/java/com/possaas/modules/menu/repository/RecipeRepository.java`
- `src/main/java/com/possaas/modules/menu/service/OrderRecipeQueryAdapter.java`
- `src/main/java/com/possaas/modules/order/application/port/ActiveOrderRecipeQuery.java`
- `src/main/java/com/possaas/modules/order/controller/OrderConflictHandler.java`
- `src/main/java/com/possaas/modules/order/controller/OrderRecipeSnapshotController.java`
- `src/main/java/com/possaas/modules/order/dto/OrderRecipeSnapshotResponse.java`
- `src/main/java/com/possaas/modules/order/entity/OrderItemRecipeSnapshot.java`
- `src/main/java/com/possaas/modules/order/entity/OrderItemRecipeUsage.java`
- `src/main/java/com/possaas/modules/order/entity/RecipeSnapshotStatus.java`
- `src/main/java/com/possaas/modules/order/repository/OrderItemRecipeSnapshotRepository.java`
- `src/main/java/com/possaas/modules/order/repository/OrderItemRecipeUsageRepository.java`
- `src/main/java/com/possaas/modules/order/service/OrderConfirmationOperation.java`
- `src/main/java/com/possaas/modules/order/service/OrderQrApprovalOperation.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotPolicy.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotQueryService.java`
- `src/main/java/com/possaas/modules/order/service/OrderRecipeSnapshotService.java`
- `src/main/java/com/possaas/modules/order/service/RecipeUsageQuantityCalculator.java`
- `src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsPersistHandler.java`
- `src/main/java/com/possaas/modules/qrorder/controller/QrStaffErrorHandler.java`
- `src/main/resources/db/migration/V32__snapshot_order_item_recipes.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/menu/OrderRecipeQueryAdapterTest.java`
- `src/test/java/com/possaas/modules/order/OrderOpenApiTest.java`
- `src/test/java/com/possaas/modules/order/OrderQrApprovalOperationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotIntegrationTest.java`
- `src/test/java/com/possaas/modules/order/OrderRecipeSnapshotServiceTest.java`
- `src/test/java/com/possaas/modules/order/RecipeUsageQuantityCalculatorTest.java`
- `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java`
- `src/test/java/com/possaas/modules/qrorder/controller/QrStaffSnapshotConflictTest.java`

## Giới hạn

Snapshot chỉ chốt định lượng, không phải xác nhận đủ tồn hoặc chi phí thực tế.
Giá bình quân ingredient có thể thay đổi độc lập. Dữ liệu legacy không có công thức
lịch sử thật; không dùng recipe hôm nay để trừ kho ngược các line legacy.
