# Inventory Giai đoạn 6 — durable low-stock episodes

## Thiết kế

Module INVENTORY sở hữu LowStockAlert. Controller chỉ binding/security/call service.
Query service + JDBC projection trả paging/detail; command service điều phối ACK;
policy thuần quyết định physical-low/transition; reconciliation join source
transaction, Clock và AuditService. Không thêm design pattern/framework không cần.

Reconciler nhận CurrentUser/context tường minh, không đọc request context hoặc
entitlement. Projection được duy trì cho configured inventory dù gói hiện tại thiếu
alert feature; API list/detail/ack vẫn kiểm tra INVENTORY_MANAGEMENT+LOW_STOCK_ALERT.
Điều này giữ consume allocation cũ hoạt động sau downgrade và không cần tạo alert
trong GET khi upgrade. Không thay package/subscription snapshot.

## Điểm tích hợp

- StockPostingService: sau khi tất cả balance/ledger đã cập nhật, đánh giá batch
  các cặp bị ảnh hưởng.
- OrderInventoryAllocationAdapter: chỉ nhánh consume, sau số dư cuối cùng; reserve/
  release không thay physical nên không tác động lifecycle cảnh báo.
- InventoryBalanceCommandService: khi tạo/đổi threshold thực sự.
- WarehouseCommandService: disable/enable/delete thực sự; không can thiệp default.
- IngredientCommandService: tracking/unit/delete qua MENU-owned command port
  IngredientInventoryAlertLifecycle, adapter trong INVENTORY. Không dùng query port
  cho write hoặc import inventory entity/service vào MENU.

Tất cả dùng transaction nguồn, không REQUIRES_NEW/async/AFTER_COMMIT. Flush JPA trước
JDBC projection để đọc chính cập nhật trong transaction. Một lần reconcile dùng một
batch projection và một query unresolved alerts, batch flush các resolve trước khi
insert episode đơn vị mới để không vi phạm partial unique. Không query từng dòng.

## Lock, idempotency và lỗi

Caller giữ restaurant write lock; kế tiếp là aggregate/balances theo thứ tự hiện
có, rồi alert locks theo warehouse/item. ACK cũng khóa restaurant trước alert.
OPEN ACK kiểm tra expectedVersion; ACKNOWLEDGED replay no-op trước stale check;
RESOLVED không được ACK. @Version bảo vệ lifecycle, không version theo live balance.

LowStockAlertPersistence flush và translate **chỉ** constraint nhận diện được hoặc
optimistic conflict đúng entity thành CONCURRENT_LOW_STOCK_ALERT_UPDATE tại write
boundary. Vì exception đã là BusinessException, mapping hoạt động qua ORDER/QR/
MENU/stock/threshold, không chỉ controller mới. Unknown persistence lỗi được giữ
cho convention hiện có; không nuốt lỗi/tiếp tục transaction failed. Handler mới
trả stable 409 cho lock/optimistic exception tại alert HTTP entry point.

Audit TRIGGERED/ACKNOWLEDGED/RESOLVED batch qua AuditService cùng transaction.
Không ghi audit khi GET, replay, vẫn-low hoặc rollback. Metadata/quantity/threshold
trigger bất biến; changing live values không reset ACK hoặc tăng alert version.

## Migration V34

Tạo low_stock_alerts, composite tenant FKs, actor FK users(id), @Version/checks,
partial unique unresolved theo tenant/warehouse/item và indexes phục vụ list.
Trigger DB cấm sửa snapshot, delete lịch sử hoặc chuyển trạng thái ngược.
Không đổi V1–V33, balance, reservation, ledger hoặc lịch sử order.

INSERT SELECT baseline chỉ current eligible low balances. Dùng transaction_timestamp
cho thời điểm phát hiện khi migrate, không giả định breach time quá khứ; không audit
người dùng giả. Inactive tracked ingredients vẫn eligible theo contract cũ. Không
scheduler, không GET writes và không cross-product tạo logical balances.

## Kiểm thử

Test PostgreSQL dùng schema it_stock_alert_<random> và các schema test ORDER/Flyway
hiện có, kiểm tra prefix trước cleanup. Clock cố định cho test cảnh báo. Test bao
gồm policy boundaries, API/security/tenant/feature, unique/immutability, ACK replay/
race, source lifecycle, rollback, baseline upgrade, order physical consumption,
reserve/release và OpenAPI schema/reference validation.

Lần chạy cuối ngày 21/10/2026 tại repository thật
`D:\Đồ án tốt nghiệp\Code\pos-saas-be`: **1.034 test / 90 suites**, Maven exit 0,
failures=0, errors=0, skipped=0. Tất cả 90 Surefire XML được tạo mới trong lần chạy
này. Nhóm mới gồm 44 policy/API/persistence/OpenAPI test, thêm 6 ORDER integration
cases và 1 Flyway baseline case so với baseline 983 test. Full suite trên bản kiểm
thử trước đó cũng qua 1.028 test, sau đó bổ sung 6 trường hợp kiểm tra cuối.

Môi trường: JDK 24 biên dịch release 21 theo POM, Maven 3.9.9, PostgreSQL 17.11.
Log cuối: `target/phase6-final-tests.log`; XML: `target/surefire-reports`.
Schema mặc định cho các test cũ được đặt riêng là
`it_phase6_final_84244f9347c64b13831c6459f9f472e6`; test có DynamicPropertySource
tiếp tục dùng schema ngẫu nhiên riêng của nó. Không chạy lại trên public.

Ví dụ chạy toàn bộ test an toàn với schema riêng (PowerShell, sửa database nếu cần):

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-24'
$verificationSchema = 'it_phase6_final_' + [guid]::NewGuid().ToString('N')
$env:DB_URL = 'jdbc:postgresql://localhost:5432/kiot_tay_db?currentSchema=' + $verificationSchema
mvn '-Dmaven.repo.local=C:\Users\Dell\.m2\repository' `
  ('-Dspring.flyway.schemas=' + $verificationSchema) `
  ('-Dspring.flyway.default-schema=' + $verificationSchema) `
  ('-Dspring.jpa.properties.hibernate.default_schema=' + $verificationSchema) `
  '-Dspring.test.context.cache.maxSize=2' `
  '-Dspring.datasource.hikari.maximum-pool-size=5' `
  '-Dspring.datasource.hikari.minimum-idle=0' test
```

Lưu ý kiểm thử: lần full đầu phát hiện test cũ dùng schema public và đã áp dụng
V34 vào database local. Không rollback migration/dữ liệu. Lần full thành công đã
đặt DB_URL/currentSchema và Flyway/Hibernate schema riêng it_phase6_full_* cho cả
test cũ, không dùng public. Không xem các SQL warning trong negative constraint
tests như test failure; kết quả lấy từ Maven exit code và Surefire XML.

## File thuộc thay đổi Giai đoạn 6 (38)

- `README.md`
- `docs/inventory-api.md`
- `docs/low-stock-alert-api.md`
- `docs/low-stock-alert-fe-sync.md`
- `docs/low-stock-alert-implementation.md`
- `docs/openapi/tenant-api.yaml`
- `src/main/java/com/possaas/modules/inventory/controller/LowStockAlertConflictHandler.java`
- `src/main/java/com/possaas/modules/inventory/controller/LowStockAlertController.java`
- `src/main/java/com/possaas/modules/inventory/dto/LowStockAlertRequests.java`
- `src/main/java/com/possaas/modules/inventory/dto/LowStockAlertResponse.java`
- `src/main/java/com/possaas/modules/inventory/dto/LowStockAlertSearch.java`
- `src/main/java/com/possaas/modules/inventory/entity/LowStockAlert.java`
- `src/main/java/com/possaas/modules/inventory/entity/LowStockAlertResolutionReason.java`
- `src/main/java/com/possaas/modules/inventory/entity/LowStockAlertStatus.java`
- `src/main/java/com/possaas/modules/inventory/repository/LowStockAlertBalanceQuery.java`
- `src/main/java/com/possaas/modules/inventory/repository/LowStockAlertQueryRepository.java`
- `src/main/java/com/possaas/modules/inventory/repository/LowStockAlertRepository.java`
- `src/main/java/com/possaas/modules/inventory/service/IngredientInventoryAlertLifecycleAdapter.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryAccessService.java`
- `src/main/java/com/possaas/modules/inventory/service/InventoryBalanceCommandService.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertAudit.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertCommandService.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertPersistence.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertPolicy.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertQueryService.java`
- `src/main/java/com/possaas/modules/inventory/service/LowStockAlertReconciliationService.java`
- `src/main/java/com/possaas/modules/inventory/service/OrderInventoryAllocationAdapter.java`
- `src/main/java/com/possaas/modules/inventory/service/StockPostingService.java`
- `src/main/java/com/possaas/modules/inventory/service/WarehouseCommandService.java`
- `src/main/java/com/possaas/modules/menu/application/port/IngredientInventoryAlertLifecycle.java`
- `src/main/java/com/possaas/modules/menu/service/IngredientCommandService.java`
- `src/main/resources/db/migration/V34__create_low_stock_alerts.sql`
- `src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/LowStockAlertIntegrationTest.java`
- `src/test/java/com/possaas/modules/inventory/LowStockAlertOpenApiTest.java`
- `src/test/java/com/possaas/modules/inventory/LowStockAlertPersistenceTest.java`
- `src/test/java/com/possaas/modules/inventory/LowStockAlertPolicyTest.java`
- `src/test/java/com/possaas/modules/order/OrderInventoryAllocationIntegrationTest.java`

## Giới hạn

Persistent inbox lưu lịch sử; [WebSocket/STOMP](inventory-alert-realtime.md) bổ sung
kênh gửi thay đổi sau commit tới client foreground. Không email/push hệ điều hành,
outbox/replay, unit conversion, available-based alerts, tự đặt mua, kiểm kê, FIFO
hoặc actual order COGS. Thao tác SQL ngoài application mutation không tự reconcile;
không cung cấp public API sửa hoặc rebuild lịch sử.

API/Postman: [low-stock-alert-api.md](low-stock-alert-api.md).
FE: [low-stock-alert-fe-sync.md](low-stock-alert-fe-sync.md).
