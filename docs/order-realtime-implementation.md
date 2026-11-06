# Implementation: staff QR / ORDER / KDS realtime

Giữ modular monolith Controller → Service → Repository. Controller và REST contract
không thay đổi. Strategy và CoR vẫn quyết định nghiệp vụ; realtime chỉ quan sát mutation
đã thành công. Không migration, permission/feature mới hoặc sửa V1–V36.

V38 reference-cost capture vẫn nằm trong transaction acceptance trước boundary
cuối này. Không thêm monetary fields vào ORDER_CHANGED, event mới hoặc tăng root
version thêm. Capture/audit/inventory rollback không phát event committed.
Summary giá vốn được GET bằng [quyền riêng](order-reference-cost-snapshot-api.md).

## Ranh giới phát sự kiện

| Command | Publication boundary sau flush/audit cuối |
| --- | --- |
| Staff create | CreateOrderPersistHandler, sau shared confirmation nếu SUBMIT |
| Staff append | AddOrderItemsPersistHandler, sau confirmation/idempotency ledger |
| Sửa dòng | UpdateOrderItemPersistHandler; nhánh no-op kết thúc trước handler |
| Header / confirm / cancel item / cancel order | OrderCommandService tại nhánh mutation thật |
| QR tạo/append order | OrderQrApprovalOperation.persist, không phát trong prepare |
| Single transition | OrderItemTransitionService sau history/audit/flush |
| Bulk transition | OrderBulkItemTransitionService sau batch history/audit/flush |
| Complete | OrderCompletionService sau close session/audit/flush |
| QR submit | QrGuestSubmissionService.persist sau items/audit/flush |
| QR approve | QrApprovalPersistHandler sau linkage/request/audit/flush |
| QR reject | QrStaffDecisionService sau update/audit/flush |
| QR lifecycle cancel | QrLifecycleService sau flush; chỉ những request đổi trạng thái |

OrderConfirmationOperation không phát; outer boundary phát đúng một OrderChanged
cuối cho create+SUBMIT/append+SUBMIT. QR approval chỉ sửa request/linkage sau order
operation, không làm dirty lại order. Event ORDER và QR cùng nằm trong transaction;
lỗi sau publication vẫn rollback, listener AFTER_COMMIT không chạy.

OrderEventPublisher/QrRequestEventPublisher chỉ chụp immutable IDs/version/timestamp
và thông tin tối thiểu, không query, không tăng version. OrderChanged và
QrOrderRequestChanged thuộc business module, không import transport. QR lifecycle
chia QrOrderRequestsChanged thành batch tối đa 100 để giới hạn enqueue và lặp quyền.
Không entity listener, AuditService hook, save AOP hoặc transaction accumulator.

## Hạ tầng

- InventoryRealtimeAccess/Principal/Sessions/InboundInterceptor/HandshakeGuard/ErrorHandler
  đổi thành Realtime* và dùng chung một broker/registry.
- RealtimeChannel tách policy bốn kênh khỏi CONNECT. Policy role kho cũ được giữ riêng;
  khác REST kho được ghi rõ trong realtime-api.md. Order/KDS không phụ thuộc role allowlist đó.
- RealtimeAccess đọc identity/effective permission bằng service hiện có trong REQUIRES_NEW
  read-only, lấy entitlement một lần/tenant/batch và permission một lần/user+role/batch.
  Không HTTP SecurityContext trong worker, không quét toàn bộ user nhà hàng.
- Registry lưu subscriptionId → channel + allowed, giới hạn kết nối/subscription, timer
  handshake/expiry dùng scheduler chung. Fence không xóa những subscription khác.
- RealtimeDispatcher dùng executor hữu hạn riêng; recipient routing theo physical session.
  Serialize một lần/envelope thành JSON bytes. Queue đầy hoặc socket lỗi không ảnh hưởng DB.
- RealtimeOutboundInterceptor là ExecutorChannelInterceptor, kiểm lại sau dequeue để
  không gửi cho session đã hết hạn, unsubscribe/fence hoặc disconnect.
- OrderRealtimeAdapter chuyển business facts thành envelope sau commit. Kho giữ
  InventoryRealtimeBroadcaster làm adapter nhỏ gọi cùng dispatcher, giữ wire contract.
- RealtimeEventEnvelope copy sâu List/Map, giữ nullable payload của kho, từ chối object mutable.

Phần đọc quyền là snapshot của từng đợt dispatch. Thay đổi quyền được áp dụng ở lần
SUBSCRIBE/dispatch kế tiếp; đây không phải hệ thống push permission-change mới. Expiry
được kiểm thêm khi outbound task thực sự chạy. FE vẫn xử lý REST 401/403 và reconnect.

## File liên quan và kiểm chứng

Các production file thêm/sửa nằm trong:
- config/RealtimeWebSocketConfig, RealtimeDispatchConfig.
- infrastructure/realtime: transport chung, access/channel, dispatcher, adapter, envelope.
- modules/order/event, OrderEventPublisher và các boundary trong bảng trên.
- modules/qrorder/event, QrRequestEventPublisher và các boundary trong bảng trên.

Test gồm unit policy/immutability/failure isolation, native TCP STOMP, và PostgreSQL
ORDER/QR integration với after-commit collector. Các test nghiệp vụ cũ giữ assertion;
chỉ cập nhật constructor khi thêm publisher. Test kho cũ chạy qua dispatcher dùng chung.
Kết quả full Maven: 1.154 test, 0 failure/error/skip. Xem
[báo cáo kiểm chứng và danh sách file](order-realtime-verification.md).

Triển khai trong bản sao `order-realtime-be-work-20261025`; chỉ đồng bộ file thay đổi
về repo gốc sau khi đối chiếu hash baseline. Không sửa target_old, FE hoặc DB làm việc.
Scope còn lại: staff-only, single-instance, best-effort; chưa guest realtime, payment/
table realtime toàn diện, outbox, push hoặc notification inbox.
