# KDS, hoàn tất order và đóng phiên bàn

Luồng phục vụ là **SUBMIT → COOKING → READY → SERVED → COMPLETED → CLOSED**. `PAID` là trạng thái thanh toán riêng: có thể thanh toán khi món vẫn `PENDING/COOKING/READY`; bếp tiếp tục làm sau khi đã PAID. Thanh toán không tự complete order, complete không tự close session. TAKEAWAY cũng đánh dấu `SERVED` khi đã giao món cho khách, nhưng không có session để đóng.

Tất cả API dùng `Authorization: Bearer <accessToken>`, tenant từ JWT, `Cache-Control: no-store`. Mutation JSON cần `Content-Type: application/json`. Dùng quyền hiệu lực (gồm GRANT/DENY), không hard-code role. KDS cần snapshot `KITCHEN_DISPLAY`; mark-served/complete cần `ORDER_MANAGEMENT`; close cần `TABLE_MANAGEMENT`.

| API | Permission | Body | Thành công |
| --- | --- | --- | --- |
| `GET /api/v1/kitchen/items?status=PENDING&page=0&size=20` | `KITCHEN_VIEW` | Không | `200 PageResponse<KitchenQueueItemResponse>` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/start-cooking` | `KITCHEN_START_COOKING` | `{"expectedOrderVersion":3}` | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/mark-ready` | `KITCHEN_MARK_READY` | Như trên | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/mark-served` | `ORDER_MARK_SERVED` | Như trên | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/complete` | `ORDER_COMPLETE` | `{"expectedVersion":7}` | `200 OrderResponse` |
| `POST /api/v1/table-sessions/{sessionId}/close` | `TABLE_CLOSE` | `{"expectedVersion":4}` | `200 TableSessionResponse` |

KDS chỉ trả item của order CONFIRMED/PREPARING/READY/SERVED, kể cả PAID; không trả draft OPEN, COMPLETED/CANCELLED, giá vốn, số điện thoại khách hoặc QR token. `status` chỉ nhận PENDING/COOKING/READY (mặc định PENDING), `size` 1–100. `sentToKitchenAt` được set một lần khi SUBMIT/confirm; hàng cũ có NULL vẫn được xếp hàng theo `createdAt`. Tên/mã bàn lấy từ session hiện tại, nên phản ánh bàn mới sau transfer. Ví dụ response:

```json
{"content":[{"orderId":"11111111-1111-4111-8111-111111111111","orderCode":"ORD-01","orderVersion":3,"orderItemId":"22222222-2222-4222-8222-222222222222","itemName":"Phở bò","quantity":2.000,"unit":"bowl","note":"Không hành","itemStatus":"PENDING","serviceType":"DINE_IN","createdAt":"2026-10-13T01:00:00Z","sentToKitchenAt":"2026-10-13T01:01:00Z","sessionId":"33333333-3333-4333-8333-333333333333","tableId":"44444444-4444-4444-8444-444444444444","tableCode":"B01","tableName":"Bàn 01"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

Từng món chỉ chuyển `PENDING → COOKING → READY → SERVED`, không nhảy/lùi bước. Gọi lại đúng bước đã đạt là no-op (không tăng version, audit hoặc history); gọi lại bước đã vượt qua trả `409 INVALID_ORDER_ITEM_TRANSITION`. `expectedOrderVersion` phải là version từ response mới nhất khi thực sự đổi trạng thái. Mỗi bước ghi đúng một history append-only và một audit `ORDER_ITEM_COOKING/READY/SERVED`. Trạng thái order được tổng hợp từ các dòng chưa CANCELLED: tất cả PENDING → CONFIRMED; còn PENDING/COOKING và đã có tiến triển → PREPARING; không còn PENDING/COOKING nhưng có READY → READY; tất cả SERVED → SERVED.

Order chưa PAID có thể gọi thêm bằng `POST /api/v1/orders/{orderId}/items` với `submissionMode: "SUBMIT"`, `Idempotency-Key` và `expectedVersion` khi root đã CONFIRMED/PREPARING/READY/SERVED. Dòng mới PENDING với snapshot giá/tên/đơn vị mới; root READY/SERVED có thể về PREPARING. Không thêm DRAFT khi root đã được gửi bếp. Chỉ dòng PENDING chưa thanh toán mới được hủy; không nới điều kiện hủy toàn order. Đã PAID thì không gọi thêm, chỉnh giá/số lượng hoặc hủy order.

Complete chỉ thành công khi có ít nhất một dòng chưa CANCELLED, mọi dòng đó đều SERVED, `paymentStatus=PAID`, `paidAmount=totalAmount>0`. Response là OrderResponse có `status=COMPLETED`, `completedAt`, version mới. Retry order đã COMPLETED trả bản hiện tại, không audit trùng. Close chỉ thành công với session OPEN có ít nhất một order COMPLETED/PAID/đủ tiền và mọi order liên quan đều terminal, không có nghĩa vụ tài chính tồn đọng. Session rỗng hoặc chỉ có order CANCELLED phải dùng cancel session. Close trả session `CLOSED` với `closedAt/closedBy`; bàn rảnh nếu cấu hình bàn AVAILABLE. Session CLOSED không được update/transfer/cancel/mở lại. `CASHIER` mặc định không có `TABLE_CLOSE`; FE chỉ hiện nút khi quyền hiệu lực có quyền đó.

Lỗi dùng format `{"success":false,"code":"...","message":"...","fieldErrors":{},"timestamp":"..."}`. `400` body sai, `401` chưa đăng nhập, `403` thiếu permission/feature, `404` ID khác tenant/không tồn tại, `409` trạng thái hoặc version xung đột. Các code chính: `INVALID_ORDER_ITEM_TRANSITION`, `ORDER_NOT_READY_FOR_COMPLETION`, `ORDER_NOT_PAID`, `TABLE_SESSION_NOT_READY_TO_CLOSE`, `TABLE_SESSION_NOT_OPEN`, `CONCURRENT_ORDER_UPDATE`, `CONCURRENT_TABLE_UPDATE`, `FEATURE_NOT_ENTITLED`.

Chưa triển khai WebSocket/realtime, QR khách tự đặt món, SePay, refund, recipe/inventory deduction hoặc in phiếu bếp. Xem [OpenAPI](openapi/kitchen-lifecycle-api.yaml), [order-api.md](order-api.md), [payment-api.md](payment-api.md).
