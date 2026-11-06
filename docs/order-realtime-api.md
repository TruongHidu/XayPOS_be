# QR ORDER, ORDER và KDS realtime

Đọc [kết nối, quyền và giới hạn dùng chung](realtime-api.md). Chỉ dành cho staff.
Không thêm REST endpoint, thay body hoặc yêu cầu Flutter/web gửi mutation qua socket.

Nhân viên bấm Gửi đơn vẫn gửi `submissionMode:SUBMIT`: một REST request tạo + xác nhận,
trả CONFIRMED; món PENDING có sentToKitchenAt. Không cần tạo draft rồi gọi confirm.
DRAFT chỉ là nhánh optional/legacy hiện có. QR của khách vẫn phải chờ nhân viên duyệt.

## QR_ORDER_REQUEST_CHANGED

Chỉ gửi `/user/queue/qr-order-requests`:

```json
{
  "eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "type":"QR_ORDER_REQUEST_CHANGED",
  "schemaVersion":1,
  "resourceType":"QR_ORDER_REQUEST",
  "resourceId":"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
  "resourceVersion":0,
  "occurredAt":"2026-10-25T08:00:00Z",
  "payload":{
    "status":"PENDING",
    "changeKind":"CREATED",
    "entryType":"TABLE",
    "tableId":"cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    "observedSessionId":null,
    "approvedOrderId":null,
    "approvedSessionId":null
  }
}
```

`changeKind`: CREATED, APPROVED, REJECTED, CANCELLED. `entryType`: TABLE hoặc RESTAURANT.
Các ID context có thể null. Status/version là của request sau persistence, không phải order.
PENDING chỉ tạo request, không mở phiên, giữ kho hoặc báo bếp. Approve thành công phát
một QR fact và một ORDER fact trong cùng transaction. Reject/cancel không tạo ORDER.

Cancel bao gồm checkout, rotate credential, invalidate bàn/phiên theo nghiệp vụ cũ.
Nhiều cancellation được enqueue theo batch tối đa 100; trên wire vẫn một envelope/request.
Expired hiện là trạng thái hiệu lực từ PENDING+expiresAt khi đọc; không thêm stored EXPIRED,
expiry scheduler hoặc event giả. FE vẫn dùng expiresAt từ REST để thể hiện hết hạn.

## ORDER_CHANGED

Gửi `/user/queue/orders`; cùng envelope gửi `/user/queue/kitchen-orders` khi liên quan bếp.

```json
{
  "eventId":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
  "type":"ORDER_CHANGED",
  "schemaVersion":1,
  "resourceType":"ORDER",
  "resourceId":"dddddddd-dddd-4ddd-8ddd-dddddddddddd",
  "resourceVersion":4,
  "occurredAt":"2026-10-25T08:01:00Z",
  "payload":{
    "orderStatus":"CONFIRMED",
    "tableSessionId":"eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    "changes":["ITEMS_ADDED"],
    "changedOrderItemIds":["ffffffff-ffff-4fff-8fff-ffffffffffff"],
    "newKitchenItemIds":["ffffffff-ffff-4fff-8fff-ffffffffffff"]
  }
}
```

`changes` là tập, không phụ thuộc thứ tự JSON. Các giá trị:
CREATED, UPDATED, SUBMITTED, ITEMS_ADDED, ITEM_UPDATED, ITEM_CANCELLED,
ITEM_STATUS_CHANGED, CANCELLED, COMPLETED.

| Mutation thực sự commit | changes | Bếp |
| --- | --- | --- |
| Tạo draft | CREATED | Không |
| Tạo SUBMIT, gồm approve QR tạo mới | CREATED + SUBMITTED | Có; chỉ món lần đầu gửi |
| Sửa header/dòng draft | UPDATED / ITEM_UPDATED | Không |
| Append draft | ITEMS_ADDED | Không |
| Confirm draft | SUBMITTED | Có; các dòng PENDING chưa gửi |
| Append SUBMIT vào draft | ITEMS_ADDED + SUBMITTED | Có; cả dòng cũ chưa gửi và dòng mới |
| Append vào order đang phục vụ, kể cả QR | ITEMS_ADDED | Có; chỉ dòng mới |
| Single/bulk chuyển trạng thái | ITEM_STATUS_CHANGED | Có; chỉ các dòng thực sự đổi |
| Hủy món cuối làm hủy order | ITEM_CANCELLED + CANCELLED | Có nếu đã submitted |
| Hủy món/đơn draft | ITEM_CANCELLED / CANCELLED | Không |
| Hủy món/đơn đã submitted | ITEM_CANCELLED / CANCELLED | Có |
| Complete | COMPLETED | Có |

`newKitchenItemIds` rỗng với chuyển trạng thái/hủy/complete. Không phát lại món cũ khi
gọi thêm. Order vẫn CONFIRMED/PREPARING cũng phát nếu có món mới. Bulk chỉ một ORDER
event, không một event/dòng. Skip-state mark-served và toàn bộ kiểm tra payment,
recipe/reservation/consume/release, served/complete/đóng phiên vẫn giữ nguyên.

No-op/replay/GET/review không phát. Complete replay chỉ sửa phiên legacy mà không đổi
order cũng không giả một ORDER event. Rollback bất kỳ bước nào không gửi socket.
Payload chỉ là invalidation hint; không có phone, guest/token/JWT, cost, công thức,
reservation, audit hoặc full OrderResponse.

## Đồng bộ app/web

| Kênh / màn hình | REST cần tải lại theo màn đang mở |
| --- | --- |
| QR backlog | GET /api/v1/qr-order-requests; GET /api/v1/qr-order-requests/{requestId} |
| Danh sách/chi tiết đơn | GET /api/v1/orders; GET /api/v1/orders/{orderId} |
| Bàn đang phục vụ | GET /api/v1/tables; GET /api/v1/table-sessions/{sessionId}/active-order |
| KDS | GET /api/v1/kitchen/items với filter/paging đang dùng |
| Kho | GET /api/v1/inventory/alerts hoặc chi tiết alert |

FE giữ một socket theo auth session, đăng ký các kênh đủ permission/feature.
Debounce refresh khoảng 200–500 ms khi burst; giữ filter, paging và trạng thái màn hình.
Sau cancel/complete phải xử lý item rời danh sách, không chỉ cập nhật row đang có.
KDS không gọi full order nếu thiếu ORDER_VIEW.

Cùng eventId có thể tới order và kitchen. Dedupe phải gắn với scope hoặc lần xử lý đầu
phải invalidate đủ mọi scope; không dùng global dedupe khiến KDS bỏ qua sự kiện đã nhận
ở order. Version từng tài nguyên không phải global delta cursor; event có thể đến lệch
thứ tự. Không tạo API delta từ lastOrderVersion. Backend quyết định version REST thật.

Âm thanh đơn/món mới chỉ dựa event CREATED của QR hoặc newKitchenItemIds khác rỗng
của ORDER, dedupe eventId để không báo hai lần. Không phát âm thanh khi tải snapshot
lịch sử/reconnect. Không coi thông báo socket là bằng chứng command đã thành công;
mutation vẫn dựa REST response và key/version hiện có.

Disconnect khi logout/background theo lifecycle app hiện có. Bỏ callback/response của
auth session cũ. Sau refresh JWT reconnect và sau reconnect/resume GET lại snapshot.
Guest realtime chưa có; guest tiếp tục dùng API receipt hiện tại. Chuyển bàn, payment
độc lập, menu availability chưa có event trong scope này; các màn đó vẫn cần refresh
qua kết quả REST/lifecycle hiện tại. Không kỳ vọng ORDER event cho mọi thay đổi TABLE.
