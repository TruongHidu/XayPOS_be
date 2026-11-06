# Inventory Phase 7 — thiết kế, migration và kiểm thử

## Kiến trúc

Giữ modular monolith hiện tại, không rewrite Clean Architecture. Controller chỉ
binding/security. `StockAuditCommandService` điều phối transaction, tenant lock,
header lock/version và desired-state replay; `StockAuditPolicy` là rule thuần.
`StockAuditSnapshotOperation` chốt scope; `StockAuditConfirmationOperation` đối
chiếu và bảo vệ reservation; `StockAuditAdjustmentOperation` lắp generated document.
Các operation nội bộ MANDATORY, actor/tenant/IP tường minh, không gọi HTTP hoặc
public command để lách permission. Không REQUIRES_NEW/async/AFTER_COMMIT.

`TrackedIngredientCatalog.findAllTracked` là port bổ sung, MENU adapter query toàn
bộ tracked undeleted INGREDIENT gồm inactive. Snapshot giá dùng batch lock/read
`IngredientReferenceCostWriter`, không import MENU entity/repository vào inventory.
Không sửa order Strategy/CoR, recipe snapshot hoặc allocation workflow.

Lock order: restaurant write -> audit -> ingredient reference-cost locks khi start
-> balances sorted. Mọi thay đổi giữ chỗ/posting hiện có cũng dùng tenant lock;
không giữ database transaction giữa các request. COUNT(*) ledger batch chỉ trong
tenant/kho/scope. Quantity + movement count + eligibility/unit được đối chiếu trước
posting, kể cả delta=0. Reserve/release/threshold không tính là physical movement.

Query header dùng indexed lateral aggregate trong **một SQL**, không load items
từng audit ở Java; page có count SQL riêng. Items paging đọc snapshot, không join
catalog live, không N+1. Start/confirm truy vấn catalog/balance/cost/ledger theo batch,
không COUNT ledger riêng cho từng ingredient. Không silently truncate scope ở 100.

`count_revision` nội bộ làm header dirty đúng một lần cho batch có thay đổi, ngay
cả khi Clock cố định. @Version vẫn là aggregate version duy nhất cho FE. No-op
không tăng revision/version hoặc audit. Count log chỉ ghi tối đa 100 dòng đã đổi.

## Posting và reservation

Không set balance.quantity trong audit code. Generated ADJUSTMENT đi qua
`StockPostingService` + `StockMovementStrategyRegistry`, giữ ledger source
STOCK_DOCUMENT và ADJUSTMENT_INCREASE/DECREASE, idempotency từng document line.
Chỉ các delta khác 0 được tạo line; >100 line vẫn một internal document.

Confirm khóa balance, batch sum reservation thật, kiểm tra cache và
counted >= current reserved ở **mọi** dòng. Không tự release/giảm reservation/hủy
order. Zero logical balance chỉ hợp lệ nếu actual reserved=0. Mọi thay đổi audit,
document/items, balance, ledger, alerts, audit logs commit/rollback cùng nhau.

Giá tham chiếu chốt tại start, 0 hợp lệ; giá đổi sau start không stale. Generated
ADJUSTMENT không gọi updateReferenceCosts hay moving weighted average (chỉ RECEIPT
mới tính giá đó). `StockDocumentItem` sửa làm tròn totalCost HALF_UP scale 2 để
chấp nhận quantity 3dp * unitCost 2dp; tổng có guard precision trước tạo document.
Không claim đây là actual COGS, không thay MENU_ITEM cost/salePrice.

## V35__create_stock_audits.sql

Không sửa V1–V34. V35 thêm:

- stock_audits, stock_audit_items (difference_quantity generated, counted nullable).
- Tenant warehouse/item/parent FK; unique audit code/key và ingredient trong audit.
- Partial unique COUNTING theo tenant/warehouse.
- Index header/page và ledger tenant/warehouse/item cho grouped COUNT(*).
- stock_documents.stock_audit_id nullable, tenant FK, unique association, chỉ ADJUSTMENT.
- DB guards chống sửa snapshot, thêm scope sau start, xóa history, đổi source,
  sửa generated CONFIRMED document/items. Snapshot insert chỉ trong transaction start.
- Deferred validation không cho generated DRAFT tồn tại sau commit; audit confirmed
  phải đủ counts, một adjustment khớp delta và số ledger; khớp hết thì không document.
- STOCK_AUDIT_MANAGE seed global system OWNER/SUPER_ADMIN, không sửa entitlement.

Migration preflight namespace SYS_STOCK_AUDIT: trim/case-insensitive, fail rõ nếu
manual key cũ xung đột, không tự đổi key/xóa dữ liệu. Không backfill kiểm kê giả,
không thay stock/reservation/ledger/cost/alerts/order snapshots cũ.

Generated source authoritative chỉ stock_documents.stock_audit_id. API read thêm
field nullable; DTO mutation không nhận source. Guard requireManual được gọi ở
tất cả public mutation, kể cả trước confirm replay. Public key validation trước
idempotent lookup. DB là lớp bảo vệ cuối, không thay business policy/application lock.

## Kiểm thử

Test mới: StockAuditPolicyTest, StockAuditIntegrationTest, StockAuditOpenApiTest,
StockAuditConflictHandlerTest; mở rộng OrderInventoryAllocationIntegrationTest và
FlywayCleanMigrationIntegrationTest. Bao phủ lifecycle/count null-vs-zero/no-op,
concurrency/partial unique, logical zero/inactive/scope >100, stale/net-zero movement,
reservation hiện tại/cache mismatch, consume, generated mutation/source guards,
namespace preflight, cost precision/zero, rollback posting/ledger/alert/audit,
security/feature/subscription/tenant, paging và migration data preservation.

Môi trường thực thi đợt này: bản làm việc
`C:\Users\Dell\OneDrive\Documents\New project\inventory-phase7-be-work-20261021`.
PostgreSQL dự án cổng 5432 đang dừng, nên dùng container postgres:17-alpine test riêng
`pos-saas-phase7-test-20261021`, loopback 15437, không mount volume dữ liệu dự án.
Mỗi integration test tự tạo/drop schema có prefix đã kiểm tra. Full suite còn đặt
default schema ngẫu nhiên để bảo vệ các test cũ chưa có DynamicPropertySource.

Ví dụ chạy full suite (DB_USERNAME/DB_PASSWORD của database test phải được cấu hình
trong môi trường, không đặt credential trong repository):

```powershell
$verificationSchema = 'it_phase7_full_' + [guid]::NewGuid().ToString('N')
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:15437/kiot_tay_db?currentSchema=' + $verificationSchema
mvn '-Dmaven.repo.local=C:\Users\Dell\.m2\repository' `
  ('-Dspring.flyway.schemas=' + $verificationSchema) `
  ('-Dspring.flyway.default-schema=' + $verificationSchema) `
  ('-Dspring.jpa.properties.hibernate.default_schema=' + $verificationSchema) `
  '-Dspring.test.context.cache.maxSize=2' `
  '-Dspring.datasource.hikari.maximum-pool-size=5' `
  '-Dspring.datasource.hikari.minimum-idle=0' test
```

Không chạy `mvn test` trần vào DB làm việc; không drop/reset public. Container test
không chứa dữ liệu người dùng và chỉ được dọn sau khi xác minh đúng tên/label.
Full suite ở bản làm việc: Maven exit 0; **1087 tests / 94 suites**, failures=0,
errors=0, skipped=0 (Surefire XML). Default schema
`it_phase7_full_1a15f097da2196fcd7814cb58` trên database test 15437; các test có
DynamicPropertySource tiếp tục dùng schema riêng. Log:
`inventory-phase7-be-work-20261021/target/phase7-full-tests.log`.
JDK 24 biên dịch release 21; dependency dùng cache Maven local.

Full suite chạy lại **tại repository thật**
`D:\Đồ án tốt nghiệp\Code\pos-saas-be`: Maven exit 0; **1087 tests / 94 suites**,
failures=0, errors=0, skipped=0. Default schema
`it_phase7_repo_1a15f0ed1cbc65c5caca3b58`, PostgreSQL **17.11** test riêng cổng
15437. Log tại `target/phase7-full-tests.log`, chi tiết XML tại `target/surefire-reports`.
Đối chiếu 911 file src/docs/pom/README giữa stage và repository: không khác biệt.
`git diff --check` không có lỗi whitespace. Schema public của database test không
có bảng nghiệp vụ (count=0). Không migrate database làm việc cổng 5432.

Sau xác minh đã dọn đúng container test và volume vô danh riêng do lượt test tạo,
kiểm tra ID/label và volume không dùng chung trước xóa. Chỉ fixtures test bị xóa,
log/bản làm việc/baseline được giữ lại. Muốn chạy lại cần cấu hình PostgreSQL test
riêng trước; không dùng URL cổng 15437 nếu container test chưa được tạo lại.

Đồng bộ theo manifest SHA256, chỉ 48 file Giai đoạn 7. Đối chiếu destination với
bản chụp trước sửa, từ chối nếu có thay đổi mới của người dùng; không reset git,
không đụng target_old, không thay V1–V34 và giữ nguyên worktree Giai đoạn 6.

## Giới hạn

Không freeze/rebase snapshot, partial confirm, unit conversion, lot/expiry, approval
nhiều cấp, tự hủy reservation, reversal, xóa ledger/history, FIFO/full valuation,
actual COGS/profit report, WebSocket/push/email hoặc sửa package/subscription snapshot.
Online counting có physical movement thì cần cancel và kiểm kê lại lúc ít giao dịch.

## File thêm/sửa (48)

- `src/main/java/com/possaas/modules/inventory/application/port/TrackedIngredientCatalog.java` — sửa
- `src/main/java/com/possaas/modules/inventory/controller/StockAuditConflictHandler.java` — thêm
- `src/main/java/com/possaas/modules/inventory/controller/StockAuditController.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditDetailResponse.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditItemResponse.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditItemSearch.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditRequests.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditSearch.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockAuditSummaryResponse.java` — thêm
- `src/main/java/com/possaas/modules/inventory/dto/StockDocumentDetailResponse.java` — sửa
- `src/main/java/com/possaas/modules/inventory/dto/StockDocumentSummaryResponse.java` — sửa
- `src/main/java/com/possaas/modules/inventory/entity/StockAudit.java` — thêm
- `src/main/java/com/possaas/modules/inventory/entity/StockAuditItem.java` — thêm
- `src/main/java/com/possaas/modules/inventory/entity/StockAuditStatus.java` — thêm
- `src/main/java/com/possaas/modules/inventory/entity/StockDocument.java` — sửa
- `src/main/java/com/possaas/modules/inventory/entity/StockDocumentItem.java` — sửa
- `src/main/java/com/possaas/modules/inventory/repository/StockAuditItemRepository.java` — thêm
- `src/main/java/com/possaas/modules/inventory/repository/StockAuditMovementQuery.java` — thêm
- `src/main/java/com/possaas/modules/inventory/repository/StockAuditQueryRepository.java` — thêm
- `src/main/java/com/possaas/modules/inventory/repository/StockAuditRepository.java` — thêm
- `src/main/java/com/possaas/modules/inventory/repository/StockDocumentQueryRepository.java` — sửa
- `src/main/java/com/possaas/modules/inventory/service/StockAuditAccessService.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditAdjustmentOperation.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditAudit.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditCommandService.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditConfirmationOperation.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditPolicy.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditQueryService.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockAuditSnapshotOperation.java` — thêm
- `src/main/java/com/possaas/modules/inventory/service/StockDocumentCommandService.java` — sửa
- `src/main/java/com/possaas/modules/inventory/service/StockDocumentLifecyclePolicy.java` — sửa
- `src/main/java/com/possaas/modules/inventory/service/StockDocumentResponseMapper.java` — sửa
- `src/main/java/com/possaas/modules/inventory/service/StockDocumentValidationPolicy.java` — sửa
- `src/main/java/com/possaas/modules/menu/repository/ItemRepository.java` — sửa
- `src/main/java/com/possaas/modules/menu/service/InventoryIngredientCatalogAdapter.java` — sửa
- `src/main/resources/db/migration/V35__create_stock_audits.sql` — thêm
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java` — sửa
- `src/test/java/com/possaas/modules/inventory/StockAuditIntegrationTest.java` — thêm
- `src/test/java/com/possaas/modules/inventory/StockAuditOpenApiTest.java` — thêm
- `src/test/java/com/possaas/modules/inventory/StockAuditPolicyTest.java` — thêm
- `src/test/java/com/possaas/modules/inventory/controller/StockAuditConflictHandlerTest.java` — thêm
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationIntegrationTest.java` — sửa
- `docs/inventory-api.md` — sửa
- `docs/stock-audit-api.md` — thêm
- `docs/stock-audit-fe-sync.md` — thêm
- `docs/stock-audit-implementation.md` — thêm
- `docs/openapi/tenant-api.yaml` — sửa
- `README.md` — sửa

