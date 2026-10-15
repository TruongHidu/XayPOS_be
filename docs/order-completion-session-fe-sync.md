# Đồng bộ app/web: hoàn tất đơn và đóng phiên

Backend giữ nguyên `POST /api/v1/orders/{orderId}/complete`, body và OrderResponse.
Từ V24, complete DINE_IN đóng đúng `order.tableSessionId` trong cùng transaction;
TAKEAWAY chỉ hoàn tất order. FE không cần request close thứ hai cho luồng DINE_IN.

## Contract và quyền

```http
POST /api/v1/orders/{orderId}/complete
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json
```

```json
{
  "expectedVersion": 9
}
```

expectedVersion bắt buộc, không âm, lấy từ **Order.version** gần nhất. Không đổi sang
expectedOrderVersion (field đó dành cho item transitions/bulk). Không gửi tableId,
sessionId, expectedSessionVersion, paymentStatus hoặc amount. Không cần Idempotency-Key.

| Loại đơn | Permission hiệu lực | Feature snapshot |
| --- | --- | --- |
| DINE_IN | ORDER_COMPLETE + TABLE_CLOSE | ORDER_MANAGEMENT + TABLE_MANAGEMENT |
| TAKEAWAY | ORDER_COMPLETE | ORDER_MANAGEMENT |

V24 (`V24__grant_order_completion_table_close.sql`) thêm TABLE_CLOSE cho global
system CASHIER/MANAGER. TABLE_CLOSE cũng cho phép manual close/cancel theo business
rule hiện có, không bỏ qua guard. Custom roles/user GRANT/DENY và feature snapshot
không đổi; DENY vẫn thắng. Login/refresh để JWT có authority mới. KITCHEN không được
cấp ORDER_COMPLETE bởi migration này. UI kiểm effective permission/feature, không
hard-code role hoặc tên package.

## Hành vi nút “Hoàn tất”

Chỉ cho gửi intent khi order còn phục vụ, paymentStatus PAID, paidAmount bằng
totalAmount > 0, có ít nhất một dòng khác CANCELLED và mọi dòng đó SERVED. UI dùng
điều kiện này để hiển thị/disable; backend vẫn kiểm dữ liệu tại thời điểm xử lý.
Không tự set PENDING/COOKING/READY thành SERVED để đạt điều kiện complete.

1. Đọc version từ OrderResponse hiện tại và tạo body như trên.
2. Disable nút khi request đang chạy.
3. Sau 200, thay order state bằng OrderResponse trả về, có status COMPLETED.
4. Với DINE_IN, invalidation/reload query danh sách bàn và đúng session bằng các
   API đọc/quyền đọc hiện có. 200 bảo đảm đúng session đã CLOSED; response complete
   không thêm session object hoặc session version.
5. TAKEAWAY không cần query TABLE hoặc thao tác phiên.
6. Khi 409, reload order/session liên quan để hiển thị trạng thái mới và để người
   dùng quyết định; không tự sửa dữ liệu hoặc gọi close để che lỗi complete.

Backend giữ restaurant lock tới commit, kiểm toàn bộ order của phiên trước khi
đóng. Nếu còn order đang phục vụ/nghĩa vụ tài chính, hoặc closure/audit/persistence
thất bại, complete mới rollback cả order và session. FE không giả lập CLOSED hoặc
COMPLETED khi nhận lỗi. Payment và bulk mark-served vẫn không tự complete/close.

Manual `POST /api/v1/table-sessions/{sessionId}/close` còn dùng được trong luồng
riêng. Body của API đó dùng **session version**; gọi lại sau auto-close là no-op.
Không gọi API này nối tiếp trong luồng complete DINE_IN mới.

## Retry và dữ liệu cũ

| Backend state | Retry complete |
| --- | --- |
| TAKEAWAY COMPLETED | 200 no-op, chấp nhận version cũ sau access guards |
| DINE_IN COMPLETED + đúng session CLOSED | 200 no-op; không đổi version/timestamp/audit |
| Legacy DINE_IN COMPLETED + đúng session OPEN | Kiểm lại PAID/full amount, mọi món hiệu lực SERVED và tất cả order của phiên; nếu hợp lệ chỉ đóng session, giữ nguyên order version/completedAt |
| Legacy phiên OPEN có order khác chưa kết thúc | 409 TABLE_SESSION_NOT_READY_TO_CLOSE; không tự complete/cancel order khác |
| Order chưa COMPLETED nhưng session CLOSED, hoặc session CANCELLED | 409 TABLE_SESSION_NOT_OPEN |

Retry sau timeout giữ orderId và body cũ. Request đầu đã hoàn tất thì backend no-op;
request chưa commit thì version cũ còn hợp lệ hoặc trả conflict để FE reload. Không
chuyển expectedVersion cũ sang version vừa đọc rồi tự gửi lại mutation mù quáng.

Nếu FE cũ còn gọi close sau complete, API close vẫn idempotent khi có đủ quyền/
feature. App/web mới bỏ request nối tiếp để tránh hiển thị lỗi của một thao tác
thừa sau completion đã thành công.

Backend chỉ đóng session được lưu trong order, không tìm session mới nhất theo
tableId. Transfer giữ sessionId; closure phản ánh bàn hiện tại của session. Retry
đơn cũ sau khi bàn có lượt khách mới không ảnh hưởng phiên mới. QR/configuration/
history giữ nguyên; bàn hết OPEN session cũ, occupancy được query lại. Bàn/area
inactive sau khi mở không chặn kết thúc phiên hợp lệ.

## Xử lý lỗi

| HTTP/code | Cách hiển thị/xử lý |
| --- | --- |
| 400 VALIDATION_ERROR / INVALID_REQUEST_BODY | Kiểm DTO expectedVersion và serialization |
| 401 | Dùng auth refresh/login flow hiện có |
| 403 FORBIDDEN / FEATURE_NOT_ENTITLED và guards khác | Không retry mù; cập nhật quyền/feature, không tự bỏ qua TABLE gate |
| 404 ORDER_NOT_FOUND / TABLE_SESSION_NOT_FOUND | Reload/đóng màn resource đã không còn truy cập được; không đoán tenant/session |
| 409 ORDER_NOT_PAID | Đơn chưa thanh toán đủ; tải lại tiền và payment state |
| 409 ORDER_NOT_READY_FOR_COMPLETION | Món chưa SERVED hoặc order không đủ điều kiện |
| 409 TABLE_SESSION_NOT_READY_TO_CLOSE | Phiên còn order/tài chính cần giải quyết |
| 409 TABLE_SESSION_NOT_OPEN / INVALID_ORDER_SEATING | Reload; dữ liệu seating không phù hợp completion |
| 409 CONCURRENT_ORDER_UPDATE / CONCURRENT_TABLE_UPDATE | Reload, để người dùng kiểm tra intent trước thao tác mới |
| 500 INTERNAL_ERROR | Hiển thị lỗi theo convention; backend rollback, reload khi trạng thái client chưa chắc chắn |

Đọc code/HTTP từ error envelope hiện tại; không quyết định theo chuỗi message.
Các query reload dùng quyền đọc của API đó, không suy quyền đọc từ ORDER_COMPLETE.

Ví dụ response đầy đủ, error envelope và Postman ở
[kitchen-completion-api.md](kitchen-completion-api.md#hoàn-tất-đơn-và-đóng-phiên-tự-động).
Schema OpenAPI ở [kitchen-lifecycle-api.yaml](openapi/kitchen-lifecycle-api.yaml).
