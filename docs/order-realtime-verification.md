# Kiểm chứng ORDER / QR ORDER / KDS realtime — 2026-10-25

## Kết quả

Full Maven test: **PASS — 1.154 tests, 103 suites, 0 failures, 0 errors, 0 skipped**. Maven exit code 0.
Compile, unit/component, PostgreSQL integration và native WebSocket/STOMP đều đã chạy. Không dùng H2 hoặc bỏ assertion.

| Nhóm tiêu biểu trong full run | Test | Kết quả |
| --- | ---: | --- |
| InventoryRealtimeBroadcasterTest | 3 | PASS |
| InventoryRealtimeInboundTest | 7 | PASS |
| InventoryRealtimeWebSocketTest | 6 | PASS |
| RealtimeAccessTest | 10 | PASS |
| RealtimeExtensionTest | 6 | PASS |
| RealtimeSessionsTest | 5 | PASS |
| FlywayCleanMigrationIntegrationTest | 19 | PASS |
| LowStockAlertEventPublisherTest | 5 | PASS |
| LowStockAlertRealtimeWiringTest | 7 | PASS |
| OrderBulkItemTransitionIntegrationTest | 51 | PASS |
| OrderCompletionSessionIntegrationTest | 44 | PASS |
| OrderIntegrationTest | 121 | PASS |
| OrderInventoryAllocationIntegrationTest | 59 | PASS |
| OrderRecipeSnapshotIntegrationTest | 32 | PASS |
| QrOrderIntegrationTest | 75 | PASS |

Các nhóm trên là một phần của tổng full run, không cộng thêm vào 1.154 test.

## Môi trường chạy

- Bản chạy: `C:/Users/Dell/OneDrive/Documents/New project/order-realtime-be-work-20261025`.
- JDK 24 biên dịch release 21; Maven 3.9.9 theo `pom.xml` hiện có.
- PostgreSQL 17 riêng: container `pos-saas-order-realtime-test-20261025`, database `pos_realtime_test`, cổng loopback 15440. Không mount volume dự án.
- `DB_URL` và `SPRING_DATASOURCE_URL` cùng trỏ database test, query `currentSchema` là schema ngẫu nhiên bên dưới. Flyway schemas/default-schema và Hibernate default_schema cùng giá trị.
- Schema mặc định: `it_realtime_full_e227412dc2f2432786dded9e54bb02e3`. Các test DynamicPropertySource tiếp tục dùng schema riêng.
- Chỉ sau khi đặt đầy đủ biến DB test mới chạy `mvn -q test`.
- `JAVA_TOOL_OPTIONS=-Duser.timezone=UTC -Dspring.test.context.cache.maxSize=4` chỉ áp dụng tiến trình test. UTC tránh alias Windows Asia/Saigon không được image PostgreSQL này chấp nhận; cache giới hạn tránh giữ quá nhiều connection pool đồng thời.
- Lượt full đầu tiên gặp PostgreSQL `too many clients`; chạy lại toàn bộ với giới hạn context cache đã thành công. Không đổi cấu hình production để che lỗi.
- Log cuối: `realtime-full-tests-bounded-context.log`; thống kê máy đọc: `realtime-full-results.json`, cùng thư mục bản chạy.

## Những điều đã chứng minh

- CONNECT độc lập với kho; CASHIER nghe order/QR, KITCHEN-only nghe bếp bằng quyền/feature hiện tại.
- Cùng socket nghe nhiều kênh; tenant isolation, unsubscribe độc lập, raw queue/SEND bị chặn, từng physical session có expiry riêng và outbound kiểm lại sau dequeue.
- Native socket với JWT và DB thực: QR pending chỉ đến staff; approve phát QR và ORDER; ORDER fan-out giữ cùng eventId/version; thu hồi feature bếp không chặn kênh order; permission DENY/account disable ngừng nhận.
- DRAFT không báo bếp; SUBMIT một bước chỉ một canonical event; gọi thêm vào đơn cũ vẫn báo các dòng mới dù status không đổi.
- Single/bulk transition, cancel và complete lấy root @Version cuối. No-op/replay không phát lại.
- Lỗi audit, snapshot, reservation hoặc đóng phiên rollback không có committed event; native rollback không có frame.
- Cảnh báo kho giữ contract; dispatcher queue rejection, lỗi recipient/mapping không thoát ra command đã commit.

## Phạm vi file

Danh sách dưới đây so với baseline lúc bắt đầu; không so với HEAD vì repo đã có chỉnh sửa từ trước. A = thêm, M = sửa, D = bỏ tên cũ sau refactor. Sáu file InventoryRealtime cũ được thay bằng Realtime tương ứng.

| Thao tác | File |
| --- | --- |
| M | `docs/inventory-alert-realtime.md` |
| M | `docs/order-api.md` |
| A | `docs/order-realtime-api.md` |
| A | `docs/order-realtime-implementation.md` |
| M | `docs/public-qr-order-api.md` |
| A | `docs/realtime-api.md` |
| M | `README.md` |
| A | `src/main/java/com/possaas/config/RealtimeDispatchConfig.java` |
| M | `src/main/java/com/possaas/config/RealtimeWebSocketConfig.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeAccess.java` |
| M | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeBroadcaster.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeErrorHandler.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeHandshakeGuard.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeInboundInterceptor.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimePrincipal.java` |
| D | `src/main/java/com/possaas/infrastructure/realtime/InventoryRealtimeSessions.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/OrderRealtimeAdapter.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeAccess.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeChannel.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeDispatcher.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeErrorHandler.java` |
| M | `src/main/java/com/possaas/infrastructure/realtime/RealtimeEventEnvelope.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeHandshakeGuard.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeInboundInterceptor.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeOutboundInterceptor.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimePrincipal.java` |
| A | `src/main/java/com/possaas/infrastructure/realtime/RealtimeSessions.java` |
| A | `src/main/java/com/possaas/modules/order/event/OrderChanged.java` |
| M | `src/main/java/com/possaas/modules/order/service/chain/append/AddOrderItemsPersistHandler.java` |
| M | `src/main/java/com/possaas/modules/order/service/chain/create/CreateOrderPersistHandler.java` |
| M | `src/main/java/com/possaas/modules/order/service/chain/update/UpdateOrderItemPersistHandler.java` |
| M | `src/main/java/com/possaas/modules/order/service/OrderBulkItemTransitionService.java` |
| M | `src/main/java/com/possaas/modules/order/service/OrderCommandService.java` |
| M | `src/main/java/com/possaas/modules/order/service/OrderCompletionService.java` |
| A | `src/main/java/com/possaas/modules/order/service/OrderEventPublisher.java` |
| M | `src/main/java/com/possaas/modules/order/service/OrderItemTransitionService.java` |
| M | `src/main/java/com/possaas/modules/order/service/OrderQrApprovalOperation.java` |
| A | `src/main/java/com/possaas/modules/qrorder/event/QrOrderRequestChanged.java` |
| A | `src/main/java/com/possaas/modules/qrorder/event/QrOrderRequestsChanged.java` |
| M | `src/main/java/com/possaas/modules/qrorder/service/chain/QrApprovalPersistHandler.java` |
| M | `src/main/java/com/possaas/modules/qrorder/service/QrGuestSubmissionService.java` |
| M | `src/main/java/com/possaas/modules/qrorder/service/QrLifecycleService.java` |
| A | `src/main/java/com/possaas/modules/qrorder/service/QrRequestEventPublisher.java` |
| M | `src/main/java/com/possaas/modules/qrorder/service/QrStaffDecisionService.java` |
| M | `src/test/java/com/possaas/infrastructure/realtime/InventoryRealtimeAccessTest.java` |
| M | `src/test/java/com/possaas/infrastructure/realtime/InventoryRealtimeBroadcasterTest.java` |
| M | `src/test/java/com/possaas/infrastructure/realtime/InventoryRealtimeInboundTest.java` |
| M | `src/test/java/com/possaas/infrastructure/realtime/InventoryRealtimeSessionsTest.java` |
| M | `src/test/java/com/possaas/infrastructure/realtime/InventoryRealtimeWebSocketTest.java` |
| A | `src/test/java/com/possaas/infrastructure/realtime/RealtimeExtensionTest.java` |
| M | `src/test/java/com/possaas/modules/order/OrderChainTest.java` |
| M | `src/test/java/com/possaas/modules/order/OrderCompletionServiceTest.java` |
| M | `src/test/java/com/possaas/modules/order/OrderCompletionSessionIntegrationTest.java` |
| M | `src/test/java/com/possaas/modules/order/OrderIntegrationTest.java` |
| M | `src/test/java/com/possaas/modules/order/OrderQrApprovalOperationTest.java` |
| M | `src/test/java/com/possaas/modules/qrorder/QrLifecycleServiceTest.java` |
| M | `src/test/java/com/possaas/modules/qrorder/QrOrderIntegrationTest.java` |
| M | `src/test/java/com/possaas/modules/qrorder/QrStaffApprovalPolicyTest.java` |
| A | `src/test/java/com/possaas/support/CommittedRealtimeEvents.java` |
| A | `src/test/java/com/possaas/support/RealtimeSocketProbe.java` |
| A | `docs/order-realtime-verification.md` |

Không đổi migration V1–V36, pom/dependency, controller/REST DTO, Strategy, quy tắc CoR, payment/stock/session, Flutter/web hoặc target_old. Không commit/reset Git.

Đồng bộ về `D:/Đồ án tốt nghiệp/Code/pos-saas-be` dùng SHA256 baseline của từng file, kiểm toàn bộ trước khi ghi và kiểm lại sau khi ghi; dừng nếu phát hiện chỉnh sửa mới.

## Điều kiện dùng và giới hạn

Ứng dụng chạy bình thường; client dùng native STOMP 1.2 tại `/ws`, JWT staff hợp lệ, restaurant/subscription active và permission/feature đúng kênh. Web Origin phải có trong `app.cors.allowed-origins`; reverse proxy chuyển tiếp upgrade.
Xem [contract chung](realtime-api.md), [ORDER/QR và đồng bộ FE](order-realtime-api.md), [publication boundaries](order-realtime-implementation.md).

Staff-only, single-instance, best-effort. Chưa guest realtime, outbox/replay, push notification, payment/table realtime toàn diện. FE phải refetch REST sau reconnect/resume. Không hứa exactly-once.
