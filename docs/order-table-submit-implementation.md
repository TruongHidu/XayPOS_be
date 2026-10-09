# Tạo order từ bàn — deferred seating, 09/10/2026

## Contract và nghiệp vụ

POST `/api/v1/orders` nhận optional `tableId`; không thêm `/orders/from-table`.
DINE_IN nhận đúng một `tableId` hoặc `tableSessionId`. `tableId` chỉ SUBMIT;
absent/null mode vẫn DRAFT và bị từ chối cho luồng này. Legacy session DRAFT/SUBMIT
và TAKEAWAY không seating giữ nguyên. Constructor Java Create 8/9 tham số vẫn dùng được.

Khi gửi từ bàn, backend dưới restaurant lock dùng OPEN session hoặc chuẩn bị mở mới.
OPEN/CONFIRMED/PREPARING/READY/SERVED chặn create mới bằng
TABLE_SESSION_HAS_SERVING_ORDER, không auto-append. Terminal history được giữ.
Response giữ OrderResponse, 201 mới/200 replay, Idempotency-Replayed và no-store;
tableSessionId thực tế luôn được gán trước insert DINE_IN order.

SUBMIT cần ORDER_CREATE + ORDER_UPDATE, ORDER_MANAGEMENT + TABLE_MANAGEMENT.
Chỉ mở mới cần TABLE_OPEN; dùng phiên cũ và replay không cần quyền mở phiên.
Manual-open vẫn kiểm expectedVersion của bàn; create từ tableId kiểm current
eligibility dưới lock, không giả hoặc thêm version đầu vào. Không thay role grants.

## Strategy, CoR và module boundary

- Registry/Factory tiếp tục chọn DINE_IN/TAKEAWAY, không thêm strategy trùng lặp.
- OrderCreationPlan dùng Kind NO_SEATING/EXISTING_SESSION/NEW_SESSION, constructor
  kiểm tính nhất quán target và guestCount. TABLE query trả sealed Existing/Unoccupied.
- Replay -> Prepare -> Persist -> mapper vẫn là một create chain. Append/update
  và các endpoint khác không đổi. Prepare không ghi DB, không query serving slot null.
- TABLE sở hữu OrderSeatingCommand và TableSessionOpeningOperation; writer MANDATORY
  tham gia transaction ORDER, tự kiểm permission/tenant/feature/eligibility/occupancy,
  sinh UUID bằng JPA và ghi TABLE_SESSION_OPENED. ORDER không import TABLE repository/entity.
- Manual-open cùng dùng operation, không gọi controller/HTTP DTO từ ORDER.
- Writer dùng Clock instant đã lấy cho command; không REQUIRES_NEW/async/Instant.now.
- Phiên mới guestCount mặc định 1, order override ở phiên cũ không sửa session.
  Không tự sao chép order.note thành session.note; QR và table configuration/version không đổi.
- Validation món/snapshot/money/confirmation trước writer. Session/order/lines/history/
  TABLE_SESSION_OPENED/ORDER_CREATED/ORDER_CONFIRMED rollback cùng transaction.
- Constraint ux_table_session_open qua ORDER handler trả TABLE_ALREADY_OCCUPIED;
  ux_order_serving_session vẫn trả TABLE_SESSION_HAS_SERVING_ORDER.

## Idempotency

tableId absent/null giữ exact eight-field LegacyCreate DRAFT và SubmittedCreate
`create-submit-v1`. Nhánh mới dùng `create-table-submit-v1`, fingerprint actor +
tableId gốc + normalized client payload, không dùng resolved/generated session,
Clock, giá live hoặc default guestCount từ DB. Append namespace/hash không đổi.

Current ORDER permission/features vẫn gate replay; live seating/menu/serving checks
chạy sau replay. Retry trả current representation của order gốc ngay cả khi đã hủy,
phiên đã kết thúc và bàn có order khác. Không tạo phiên, reprice, audit/history/version
mới. Key đổi actor/table/payload hoặc chuyển sang session intent bị 409.

## Database và phạm vi

Không thêm migration hoặc cột tableId. V1–V19 và hai partial indexes giữ nguyên.
Không sửa dữ liệu application public/dev, Flutter/Web, target_old hoặc MENU baseline.
Không triển khai QR ordering, KDS/payment/close, discount/recipe/inventory, chuyển bàn
hay gộp/tách bill. FE cần đồng bộ theo [order-submit-fe-sync.md](order-submit-fe-sync.md).

## Kiểm chứng

Java 21.0.1, Spring Boot 4.1.1, PostgreSQL 17.11. Main compilation và JPA schema
validation thành công. Staging và source thực tế đều chạy lại toàn bộ nhóm liên quan:

| Nhóm | Kết quả |
| --- | --- |
| ORDER integration | 100 PASS |
| ORDER CoR | 10 PASS |
| ORDER policy | 11 PASS |
| ORDER table policy/hash | 7 PASS |
| ORDER OpenAPI | 4 PASS |
| TABLE | 31 PASS |
| Flyway | 6 PASS, database rỗng tới V19 và duplicate preflight |
| Tổng focused | 169 PASS, 0 failures/errors/skips |

34 test mới: 24 integration, 7 policy/hash, 2 CoR, 1 OpenAPI. Bao gồm race cùng/
khác key, manual open/cancel/table/menu mutation, rollback sau khi writer đã lưu
session, original replay sau replacement và TABLE_OPEN bị thu hồi, legacy/hash/
constructor/null tableId compatibility, mandatory transaction/runtime authorization
và native partial unique constraint mapping.

Full standard Maven trước sửa: 348 tests, 2 MENU assertion failures và 4 MENU
runtime compilation errors do cached IDE classes. Fresh `mvnw.cmd test -q` ở
staging và sau cập nhật repository dừng tại testCompile:
`src/test/java/com/possaas/modules/menu/MenuPolicyTest.java:18` thiếu costPrice
argument của ItemCreationContext. Hai assertion baseline ở MenuIntegrationTest
liên quan costPrice input/response. Không sửa MENU hoặc loại test trong POM repository
để báo xanh; full suite chưa pass.

POM focused ngoài repository tại
`C:\Users\Dell\OneDrive\Documents\New project\.order-table-verification\pom.xml`
chỉ compile/test ORDER, TABLE và Flyway; không phải full-suite verification.
Command kiểm chứng source thực tế dùng `-Dtask.sourceRoot=D:/Đồ án tốt nghiệp/Code/pos-saas-be`
và `-Dtest=Order*Test,Table*Test,FlywayCleanMigrationIntegrationTest`.
Actual reports: `target/focused-verification/surefire-reports`.
Logs ngoài repo: `order-table-submit-work-20261009/baseline-standard.log`,
`staging-full-test.log`, `focused-final.log`, `actual-full-test.log`, `actual-focused.log`.

Không có migration mới; V1–V19 byte-identical với baseline. Không chỉnh repository
pom.xml hoặc file ngoài manifest. Source/test/docs được đối chiếu hash trước/sau promotion.
Các schema it_order_*, it_table_*, it_flyway_* tự cleanup theo guard của integration tests.
Ba schema it_table_submit_baseline/focus/actual_20261009 được dọn riêng sau verification
bằng exact whitelist + Flyway-version guard; chỉ dữ liệu test tái tạo được bị xóa.

## File thay đổi

31 file của lượt này, không bao gồm dirty changes từ các lượt trước:

- src/main/java/com/possaas/modules/order/controller/OrderConflictHandler.java
- src/main/java/com/possaas/modules/order/dto/OrderRequests.java
- src/main/java/com/possaas/modules/order/service/OrderIdempotencyService.java
- src/main/java/com/possaas/modules/order/service/OrderRequestNormalizer.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderContext.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderPersistHandler.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderPrepareHandler.java
- src/main/java/com/possaas/modules/order/service/strategy/DineInOrderCreationStrategy.java
- src/main/java/com/possaas/modules/order/service/strategy/OrderCreationContext.java
- src/main/java/com/possaas/modules/order/service/strategy/OrderCreationPlan.java
- src/main/java/com/possaas/modules/order/service/strategy/TakeawayOrderCreationStrategy.java
- src/main/java/com/possaas/modules/table/application/port/OrderSeatingCommand.java
- src/main/java/com/possaas/modules/table/application/port/OrderSeatingQuery.java
- src/main/java/com/possaas/modules/table/repository/TableSessionRepository.java
- src/main/java/com/possaas/modules/table/service/OrderSeatingCommandAdapter.java
- src/main/java/com/possaas/modules/table/service/OrderSeatingQueryAdapter.java
- src/main/java/com/possaas/modules/table/service/TableSessionCommandService.java
- src/main/java/com/possaas/modules/table/service/TableSessionOpeningOperation.java
- src/main/java/com/possaas/modules/table/service/TableSessionOpeningPolicy.java
- src/test/java/com/possaas/modules/order/OrderChainTest.java
- src/test/java/com/possaas/modules/order/OrderIntegrationTest.java
- src/test/java/com/possaas/modules/order/OrderOpenApiTest.java
- src/test/java/com/possaas/modules/order/OrderPolicyTest.java
- src/test/java/com/possaas/modules/order/OrderTablePolicyTest.java
- docs/order-api.md
- docs/order-cor-implementation.md
- docs/order-submit-fe-sync.md
- docs/order-table-submit-implementation.md
- docs/tenant-api.md
- docs/openapi/order-api.yaml
- README.md
