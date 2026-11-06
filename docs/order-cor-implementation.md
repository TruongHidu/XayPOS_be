# ORDER CoR và single serving order — 09/10/2026

## Đã triển khai

- OrderSessionServingPolicy: OPEN/CONFIRMED/PREPARING/READY/SERVED chiếm serving
  slot của DINE_IN/session; CANCELLED/COMPLETED giữ lịch sử, TAKEAWAY độc lập.
- V19 partial unique + duplicate preflight. Không thay V1–V18/target_old và không
  tự delete/merge/cancel dữ liệu cũ. Precheck và constraint cùng 409
  TABLE_SESSION_HAS_SERVING_ORDER.
- API mutation/body/header/default DRAFT/hash legacy/append namespace giữ nguyên.
  Replay create A đã cancel trả A, dù B hiện đang serving trong cùng phiên.
- GET /api/v1/table-sessions/{sessionId}/active-order: ORDER_VIEW +
  ORDER_MANAGEMENT, 200 OrderResponse/204 no body, 404 ownership, no mutation.
  Inconsistent multiple rows fail 409, không chọn latest/first.
- Ba chain type-safe riêng trong modules/order/service/chain. Strategy
  DINE_IN/TAKEAWAY vẫn giữ, không biến query seating thành command.

## Các chain

Bổ sung tiếp theo: POST /orders nhận optional tableId + SUBMIT để chỉ mở phiên
khi gửi đơn. Xem [triển khai deferred seating](order-table-submit-implementation.md).
Legacy tableSessionId/hash và ba chain vẫn giữ; không thêm /orders/from-table.

OrderChainConfiguration lắp thứ tự tường minh; không global @Order, reflection,
service locator, Object context hay singleton setNext. Mỗi invocation có context
và continuation guard riêng. next.proceed hoặc next.complete đúng một lần, phải
trả result đó; implicit skip/null/foreign result/double-next bị chặn.

| Bean | Context | Handler order | Short-circuit |
| --- | --- | --- | --- |
| createOrderChain | CreateOrderContext | CreateOrderReplayHandler → CreateOrderPrepareHandler → CreateOrderPersistHandler → mapper terminal | Auth/features/normalize/hash rồi replay; chưa kiểm live seating/serving slot/menu |
| addOrderItemsChain | AddOrderItemsContext | AddOrderItemsReplayHandler → AddOrderItemsPrepareHandler → AddOrderItemsPersistHandler → mapper terminal | SUBMIT ledger replay trước mutable lifecycle/version/currency/seating/menu |
| updateOrderItemChain | UpdateOrderItemContext | UpdateOrderItemGuardHandler → UpdateOrderItemPrepareHandler → UpdateOrderItemPersistHandler → mapper terminal | No-op sau auth/root version/OPEN/unpaid/line PENDING guards |

Facades OrderCreationService/OrderAppendService/OrderItemUpdateService giữ
transaction và restaurant lock tới commit, Clock một lần/micro precision. Context
request-local, toString redacted. OrderCommandService chỉ delegate updateItem,
metadata/cancel/legacy confirm giữ nguyên.

Prepare create chỉ xây transient aggregate; append/update dùng typed plans và
OrderMoneyCalculator.calculate không dirty managed root/old lines. Apply/persist
mới tăng mutationSequence, flush rồi map. Defensive confirmation recheck vẫn
đồng bộ trong transaction. Audit/history/confirmation/ledger cùng transaction;
không async/REQUIRES_NEW/per-handler commit.

OPEN+SUBMIT append kiểm mọi live line trước writer, unavailable old menu không
để lại added lines/audit/history/ledger. CONFIRMED append không re-confirm hoặc
reset snapshots/confirmedAt. Update item dùng giá snapshot; catalog inactive không
nới hoặc làm hẹp rule legacy; no-op không bump version/audit/history.

### Create từ tableId

Replay normalize/shape/security/features/hash trước live seating. DineIn Strategy
trả OrderCreationPlan typed: NO_SEATING / EXISTING_SESSION / NEW_SESSION với
constructor invariants. TABLE query chỉ đọc, trả Existing/Unoccupied; mở mới cần
TABLE_OPEN, dùng phiên cũ không cần quyền này. Prepare kiểm món/money/confirmation
trước mọi writer, không query serving slot bằng sessionId=null.

Persist gọi OrderSeatingCommand của TABLE, operation MANDATORY tạo phiên/audit
trong transaction đang có, dùng thời gian command. UUID thật được gán trước khi
save order. Manual-open cùng tái sử dụng operation, giữ table expectedVersion.
ORDER không import TABLE repository/entity, không gọi controller hay REQUIRES_NEW.
TABLE_SESSION_OPENED, ORDER_CREATED, ORDER_CONFIRMED rollback cùng nhau.

tableId absent/null giữ hash LegacyCreate/create-submit-v1; table intent dùng
create-table-submit-v1 với tableId gốc, không hash resolved session/live values.
Replay trả order gốc ngay cả khi phiên/bàn/món thay đổi, không mở phiên hoặc audit mới.

## Migration và dữ liệu ứng dụng

Xem [SQL chỉ đọc / hướng dẫn upgrade](order-serving-migration.md).
Read-only kiểm tra schema ứng dụng public tại thời điểm triển khai: 0 duplicate
serving groups. Không migrate hoặc sửa dữ liệu public/dev trong nhiệm vụ này.
Kết quả audit tại một thời điểm không thay thế preflight V19 khi startup.
Nếu live data thay đổi và migration fail, operator phải lấy quyết định xử lý riêng.

## Frontend handoff

Xem [contract/prompt FE](order-submit-fe-sync.md): bỏ action tạo thêm order khi
session đã có serving order, dùng active-order để mở detail và append vào đúng id;
không tự retry/create-or-append. Frozen key/version/payload và cancel boundary
confirmedAt giữ nguyên. App/Web chưa sửa trong lượt này.

## Kiểm chứng

Baseline standard Maven với cached test classes: 328 tests, 2 MENU assertion
failures và 4 MENU runtime compilation errors (costPrice constructor mismatch).
MENU implementation/tests không sửa để làm suite xanh.

Focused staging: 135 tests pass (98 ORDER, 31 TABLE, 6 Flyway), không failures/errors.
Main source và JPA validate với Java 21/PostgreSQL 17 pass. Test migration trùng
fail như thiết kế và giữ dữ liệu, clean database rỗng tới V19.

Actual-source verification cuối:

| Check | Result |
| --- | --- |
| Main compilation / JPA validate | PASS, Java 21/PostgreSQL 17 |
| ORDER | 98 PASS: 76 integration + 8 CoR + 11 policy + 3 OpenAPI |
| TABLE | 31 PASS: 22 integration + 8 policy + 1 OpenAPI |
| Flyway | 6 PASS, empty schema tới V19 và duplicate preflight giữ nguyên dữ liệu |
| Full diagnostic Maven clean test | 342 tests: 340 passed, 2 baseline MENU assertions, 0 errors/skips |
| Standard mvnw.cmd test -q | BLOCKED testCompile tại MenuPolicyTest.java:18 thiếu costPrice argument |

POM diagnostic ngoài repo chỉ loại **/menu/MenuPolicyTest.java từ test compilation;
không sửa repository pom.xml và không coi exclusion đó là fix baseline. Actual-source
command dùng C:\Users\Dell\OneDrive\Documents\New project\.order-verification\pom.xml.
Staging POM ở .order-cor-verification/pom.xml. Logs bên ngoài repo:
order-cor-be-work-20261009/baseline-standard.log, focused-retry.log,
actual-standard.log, actual-regression.log. Ba schema it_cor_baseline_20261009,
it_cor_focus_20261009, it_cor_regression_20261009 đã được dọn sau verification,
có whitelist và Flyway-version guard; chỉ dữ liệu test tái tạo được bị xóa.
Dev/public chỉ được audit read-only, không migrate/update/delete.

## File của lượt này

43 file (không bao gồm dirty worktree từ các lượt trước):

- src/main/java/com/possaas/modules/order/controller/OrderConflictHandler.java
- src/main/java/com/possaas/modules/order/controller/OrderSessionQueryController.java
- src/main/java/com/possaas/modules/order/repository/OrderRepository.java
- src/main/java/com/possaas/modules/order/service/OrderAppendService.java
- src/main/java/com/possaas/modules/order/service/OrderCommandService.java
- src/main/java/com/possaas/modules/order/service/OrderConfirmationOperation.java
- src/main/java/com/possaas/modules/order/service/OrderCreationService.java
- src/main/java/com/possaas/modules/order/service/OrderItemUpdateService.java
- src/main/java/com/possaas/modules/order/service/OrderMoneyCalculator.java
- src/main/java/com/possaas/modules/order/service/OrderQueryService.java
- src/main/java/com/possaas/modules/order/service/OrderSessionServingPolicy.java
- src/main/java/com/possaas/modules/order/service/OrderSubmissionPolicy.java
- src/main/java/com/possaas/modules/order/service/chain/OrderChain.java
- src/main/java/com/possaas/modules/order/service/chain/OrderChainConfiguration.java
- src/main/java/com/possaas/modules/order/service/chain/OrderCommandFacts.java
- src/main/java/com/possaas/modules/order/service/chain/OrderHandler.java
- src/main/java/com/possaas/modules/order/service/chain/OrderNext.java
- src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsContext.java
- src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsPersistHandler.java
- src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsPrepareHandler.java
- src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsReplayHandler.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderContext.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderPersistHandler.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderPrepareHandler.java
- src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderReplayHandler.java
- src/main/java/com/possaas/modules/order/service/chain/update/UpdateOrderItemContext.java
- src/main/java/com/possaas/modules/order/service/chain/update/UpdateOrderItemGuardHandler.java
- src/main/java/com/possaas/modules/order/service/chain/update/UpdateOrderItemPersistHandler.java
- src/main/java/com/possaas/modules/order/service/chain/update/UpdateOrderItemPrepareHandler.java
- src/main/java/com/possaas/modules/table/application/port/OrderSessionQuery.java
- src/main/java/com/possaas/modules/table/service/OrderSessionQueryAdapter.java
- src/main/resources/db/migration/V19__single_serving_order_per_session.sql
- src/test/java/com/possaas/migration/FlywayCleanMigrationIntegrationTest.java
- src/test/java/com/possaas/modules/order/OrderChainTest.java
- src/test/java/com/possaas/modules/order/OrderIntegrationTest.java
- src/test/java/com/possaas/modules/order/OrderOpenApiTest.java
- docs/order-api.md
- docs/order-cor-implementation.md
- docs/order-implementation.md
- docs/order-serving-migration.md
- docs/order-submit-fe-sync.md
- docs/openapi/order-api.yaml
- README.md

## Giới hạn còn lại

Không /orders/from-table; dùng POST /orders + tableId + SUBMIT để mở session khi gửi.
Legacy tableSessionId vẫn cần OPEN session thật, không tự mở phiên thay thế.
Không payment/KDS/close/stock/recipe. Status reserved không có mutation mới.
Đây là refactor + cardinality rule; không triển khai luồng đóng phiên sau thanh toán.
