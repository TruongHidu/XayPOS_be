# Staff realtime contract

Backend cung cấp native WebSocket + STOMP 1.2 tại `GET /ws`. Không dùng SockJS,
Socket.IO hoặc WebSocket cho mutation. REST vẫn là nguồn dữ liệu chính.

## Kết nối

Web dùng `wss://<api-host>/ws` khi HTTPS; local dùng `ws://<api-host>:8080/ws`.
HTTP upgrade chỉ mở đường truyền, chưa xác thực. STOMP CONNECT:

```text
CONNECT
accept-version:1.2
host:<api-host>
heart-beat:10000,10000
Authorization:Bearer <staff-access-token>

<NUL>
```

`<NUL>` biểu diễn byte kết thúc STOMP, không phải chuỗi cần gửi nguyên văn.
Không token trong query/cookie, không gửi refresh token. Guest opaque token không dùng được.
Bearer header bị xóa khỏi message sau CONNECT để tránh xuất hiện trong lỗi framework.
Authorization phải đúng một header; các biến thể khác hoa/thường gây nhập nhằng bị từ chối.
Không gửi header tự khai user/tenant hoặc STOMP login/passcode.

CONNECT kiểm tra JWT bằng decoder hiện có, expiry bằng Clock, tài khoản/nhà hàng/role
trong DB và subscription effective. Không yêu cầu quyền kho. System SUPER_ADMIN không
được nghe dữ liệu tenant. Role hiện tại phải khớp token; quyền nhận lấy từ PermissionService
hiện tại, kể cả GRANT/DENY. Không suy feature từ tên package.

Origin dùng nguyên cấu hình `app.cors.allowed-origins`; native không có Origin vẫn được
chấp nhận. Không nới wildcard. Reverse proxy cần chuyển tiếp WebSocket upgrade.

## Kênh

| Destination | Permission đọc | Feature trong subscription effective |
| --- | --- | --- |
| `/user/queue/inventory-alerts` | INVENTORY_VIEW | INVENTORY_MANAGEMENT, LOW_STOCK_ALERT |
| `/user/queue/qr-order-requests` | ORDER_VIEW | ORDER_MANAGEMENT |
| `/user/queue/orders` | ORDER_VIEW | ORDER_MANAGEMENT |
| `/user/queue/kitchen-orders` | KITCHEN_VIEW | KITCHEN_DISPLAY |

Kho tiếp tục giữ allowlist OWNER/MANAGER/WAITER/KITCHEN của socket cũ. REST cảnh báo
kho không có allowlist tương ứng; lần này giữ giới hạn socket cũ, không âm thầm mở rộng
cho CASHIER/custom role. Giới hạn này không áp dụng lên CONNECT hoặc ba kênh mới.
KITCHEN-only không cần ORDER_VIEW/INVENTORY_VIEW. Đọc backlog QR không đòi feature
QR_TABLE_ORDER/QR_STATIC_ORDER hoặc quyền approve, giống REST hiện có.

```text
SUBSCRIBE
id:orders
destination:/user/queue/orders
ack:auto

<NUL>
```

Một kết nối có thể nghe nhiều kênh. Mỗi ID phải riêng, khớp `[A-Za-z0-9._:-]{1,100}`;
mỗi destination chỉ một subscription/session. UNSUBSCRIBE với `id` chỉ gỡ đúng kênh đó.
Destination phải khớp tuyệt đối. Không `/topic/**`, raw `/queue/**`, wildcard, selector,
destination encoded hoặc `/user/<someone>/...`. SEND/ACK/NACK từ client bị từ chối.
Tenant và routing physical session do server xác định, không do client chọn.

Quyền/feature được kiểm tra tại SUBSCRIBE và mỗi đợt dispatch trong transaction đọc mới.
Các session cùng user dùng chung kết quả quyền trong đợt đó, nhưng expiry/subscription/
physical connection được kiểm tra riêng. Kênh bị thu hồi quyền bị fence, không nhận nữa;
các kênh khác còn quyền tiếp tục hoạt động. Fence không tự gửi một notification mới.
FE cập nhật `/api/v1/auth/me`/entitlement qua luồng auth hiện có, reconnect hoặc
UNSUBSCRIBE rồi SUBSCRIBE lại kênh còn quyền khi quyền thay đổi. Không tự retry subscribe
kênh bị 403 liên tục. Tài khoản/nhà hàng/subscription không còn hợp lệ khiến socket đóng.

Có timer đóng socket chưa CONNECT sau 15 giây và socket đã hết hạn JWT, kể cả idle.
Outbound kiểm tra lại expiry, subscription và kết nối sau executor dequeue trước gửi frame.
Refresh bằng REST rồi reconnect; logout client phải disconnect. Không bổ sung JWT denylist
hoặc thay đổi hiệu lực access token sau logout.

## Envelope

JSON object chứa `eventId`, `type`, `schemaVersion:1`, `resourceType`, `resourceId`,
`resourceVersion`, `occurredAt` (ISO UTC) và `payload`. Không base64 JSON.
`resourceVersion` là @Version đã flush của đúng tài nguyên, không phải mutationSequence
hay cursor toàn hệ thống. Cùng event fan-out giữ nguyên eventId.

Cảnh báo kho giữ nguyên type `INVENTORY_LOW_STOCK_ALERT_CHANGED`, resourceType
`LOW_STOCK_ALERT` và payload hiện có, gồm `resolutionReason` có thể null.
Xem [contract kho](inventory-alert-realtime.md) và [ORDER/QR](order-realtime-api.md).

## Lỗi và giới hạn

ERROR dùng JSON `{ "code":"FORBIDDEN", "message":"Realtime request rejected" }` và
header `code`. Có thể socket đóng trước khi client nhận ERROR. Các mã:
UNAUTHORIZED, TOKEN_EXPIRED, FORBIDDEN, ACCOUNT_INACTIVE, RESTAURANT_INACTIVE,
SUBSCRIPTION_NOT_ACTIVE, FEATURE_NOT_ENTITLED, REALTIME_PROTOCOL_VIOLATION,
REALTIME_CONNECTION_LIMIT, REALTIME_SUBSCRIPTION_LIMIT. Shutdown đóng REALTIME_SHUTDOWN.
Không có token, SQL hoặc stack trace trong payload lỗi.

| Cấu hình | Mặc định |
| --- | --- |
| app.realtime.max-connections | 10000 |
| app.realtime.max-connections-per-user | 8 |
| app.realtime.max-subscriptions-per-session | 4 (tối đa cấu hình 16) |
| app.realtime.dispatch-threads | 2 (1..16) |
| app.realtime.dispatch-queue-capacity | 256 (1..10000) |

Executor dispatch hữu hạn, AbortPolicy, không CallerRuns. Outbound pool 2..4, queue 512.
Message 64 KiB, socket send buffer 128 KiB, send timeout 10 giây, heartbeat 10 giây.
Queue đầy/serialization/transport lỗi có thể làm mất thông báo; không làm REST đã commit
thành HTTP 500. Bộ nhớ hữu hạn, không retry vô hạn. Chỉ single-instance, best-effort,
không replay/exactly-once/outbox/broker ngoài. App đóng không nhận push hệ điều hành.
Sau reconnect/resume luôn tải lại snapshot REST, giữ filter và paging của màn hình.
