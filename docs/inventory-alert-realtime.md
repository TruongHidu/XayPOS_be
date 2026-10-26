# Realtime cảnh báo tồn thấp

Triển khai ngày 24/10/2026. REST tiếp tục là nguồn đọc danh sách, chi tiết và ACK.
Native WebSocket/STOMP bổ sung thông báo theo sự kiện khi transaction thay đổi
cảnh báo đã commit; không có lịch kiểm tra tồn kho mỗi 20 giây.

## Luồng phát hiện

Điều kiện hiện có: `minQuantity > 0 && physicalQuantity <= minQuantity`.
Khi nhập/xuất kho, tiêu thụ nguyên liệu hoặc sửa ngưỡng qua nghiệp vụ application,
reconciliation đánh giá số dư và tạo đợt OPEN mới nếu cần. Sau khi lưu/flush để có
ID và version, service phát snapshot bất biến qua ApplicationEventPublisher.
Listener AFTER_COMMIT enqueue qua dispatcher hữu hạn dùng chung, rồi gửi đến các socket đủ quyền của đúng restaurant.
Rollback không gửi sự kiện. SQL sửa trực tiếp không chạy reconciliation.

Một đợt đang OPEN hoặc ACKNOWLEDGED không được tạo lại chỉ vì tồn tiếp tục thấp.
Tồn phục hồi thì RESOLVED; thấp lại tạo đợt OPEN mới với ID mới.
ACK thực sự và RESOLVED cũng phát event để thiết bị khác đồng bộ UI; replay ACK
hoặc mutation không đổi đợt cảnh báo không phát event trùng.
Reserve/release không đổi physical nên không tự tạo cảnh báo chỉ do available thấp.

## Kết nối

- HTTP upgrade `GET /ws`, native WebSocket; subprotocol `v12.stomp`. Không SockJS.
- STOMP CONNECT dùng `accept-version:1.2`, `heart-beat:10000,10000` và
  `Authorization:Bearer <accessToken>`; không token trong URL/query/cookie.
- Subscribe một lần: `id:inventory-alerts`,
  `destination:/user/queue/inventory-alerts`, `ack:auto`.
- Không gửi SEND/ACK/NACK, raw queue, topic, selector hoặc wildcard.
- JWT được kiểm tra bằng decoder hiện có. Tenant lấy từ JWT, không từ client.
  Tài khoản/nhà hàng/role phải active và có subscription effective để CONNECT.
  Riêng SUBSCRIBE/nhận cảnh báo cần INVENTORY_VIEW và hai feature
  INVENTORY_MANAGEMENT, LOW_STOCK_ALERT. Không cần quyền ACK để nhận sự kiện.
  Policy socket kho vẫn giữ allowlist OWNER/MANAGER/WAITER/KITCHEN cũ;
  các kênh ORDER/KDS không chịu giới hạn role kho này.
  Kiểm tra lại quyền tại SUBSCRIBE và mỗi đợt dispatch; kiểm expiry sau outbound dequeue.
- Bearer header được loại bỏ trước khi framework xử lý lỗi/ghi thông tin message.
  Kết nối hết hạn token đóng bằng code ổn định TOKEN_EXPIRED.

HTTP upgrade được permit riêng đúng GET `/ws`; điều này không xác thực STOMP.
CONNECT thiếu token bị từ chối; quyền của kênh được kiểm tại SUBSCRIBE.
Một socket có thể nghe cả kho và ORDER/KDS nếu đủ quyền, không cần kết nối riêng.
Xem [contract realtime dùng chung](realtime-api.md) và [ORDER/QR realtime](order-realtime-api.md).
Origin web dùng `app.cors.allowed-origins` hiện có, hỗ trợ pattern như REST và phân tách bằng dấu phẩy;
mặc định `http://localhost:3000`. Native client không gửi Origin vẫn kết nối được.
Reverse proxy phải chuyển tiếp WebSocket upgrade; HTTPS bên ngoài dùng WSS.

## Event

```json
{
  "eventId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "type": "INVENTORY_LOW_STOCK_ALERT_CHANGED",
  "schemaVersion": 1,
  "resourceType": "LOW_STOCK_ALERT",
  "resourceId": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "resourceVersion": 0,
  "occurredAt": "2026-10-24T08:00:00Z",
  "payload": {
    "warehouseId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "ingredientId": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    "status": "OPEN",
    "resolutionReason": null
  }
}
```

Version là optimistic-lock version đã flush của alert. Event chỉ chứa metadata
để invalidate REST, không thay thế DTO chi tiết và không chứa restaurantId.
Đường gửi được phân tách theo physical session và tenant phía server.
ERROR frame chỉ có mã ổn định và thông điệp chung; không lộ chi tiết exception.

## Flutter và cách thử

Flutter mặc định `REALTIME_TRANSPORT=websocket`; bỏ cấu hình `polling` cũ nếu có.
Khởi động lại backend và app, vào **Kho → Cảnh báo → Cập nhật tự động**, bật và
đợi **Đang tự động cập nhật cảnh báo.** API_BASE_URL phải truy cập được từ thiết bị.
App suy ra `/ws` từ REST base; REALTIME_URL chỉ cần khi proxy khác đường dẫn.

Ở môi trường test, ví dụ physical=1000 g, threshold=200 g, xác nhận xuất 800 g
qua API chứng từ hiện có. Sau commit, thiết bị cùng tenant đang kết nối nhận OPEN;
Flutter gom batch 400 ms rồi hiện thông báo nhỏ, tự ẩn sau 5 giây và có nút Xem.
App đang ở màn khác vẫn nhận nếu foreground và đủ quyền.
Cảnh báo đã tồn tại trước khi kết nối không tự hiện lại popup do tải REST.
Đừng giảm tiếp một nguyên liệu đã có đợt OPEN/ACKNOWLEDGED rồi kỳ vọng popup mới:
cần thử một đợt mới, hoặc phục hồi tồn rồi làm chạm ngưỡng lại bằng nghiệp vụ hợp lệ.

ACK/RESOLVED đồng bộ danh sách/chi tiết, không tạo popup mới. Tắt công tắc,
background hoặc logout dừng nhận và dọn thông báo. Khi kết nối lại phải REST resync.

## Giới hạn và kiểm thử

Broker đơn tiến trình, in-memory, best effort, không replay/outbox/push hệ điều hành.
Nếu nhiều backend instance, cần broker/fan-out liên instance trước khi dùng quy mô đó.
Không bổ sung database migration hoặc thay REST contract.
Giới hạn mặc định 10000 socket tổng và 8 mỗi user, cấu hình bằng
`app.realtime.max-connections` và `app.realtime.max-connections-per-user`.
Heartbeat 10 giây dùng kiểm tra kết nối, không phải chu kỳ kiểm tra ngưỡng.

Nhóm test riêng gồm publisher/wiring, quyền, inbound, registry và broadcaster.
Native WebSocket integration khởi chạy server cổng ngẫu nhiên, không datasource;
kiểm tra CONNECT/SUBSCRIBE, JSON MESSAGE sau commit, rollback, cô lập tenant,
từ chối anonymous/SEND/raw queue và dọn subscription sau socket close.
Các kiểm thử này không thay thế thử hai thiết bị thật trong môi trường triển khai.

Xác minh ngày 24/10/2026: 35 unit/wiring test đạt; 4 native WebSocket integration
test đạt. Nhóm 38 test (35 + 3 integration ban đầu) chạy chung thành công; sau khi
đồng nhất Origin pattern, cả 4 integration test chạy lại thành công. Toàn bộ
945 kiểm thử Flutter đạt và phân tích Dart không có lỗi.

Tham khảo chính thức:
[Spring STOMP token authentication](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authentication-token-based.html),
[STOMP authorization](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/authorization.html).
