# PAYMENT MVP — ghi nhận thủ công

API này chỉ dành cho nhân viên đã đăng nhập. `CASH` nghĩa là nhân viên đã nhận tiền mặt; `TRANSFER` nghĩa là nhân viên **tự kiểm tra tài khoản ngân hàng và xác nhận đã nhận tiền**. Không có SePay, QR thanh toán hoặc kiểm chứng giao dịch ngân hàng tự động ở giai đoạn này. Xem [OpenAPI](openapi/payment-api.yaml).

## Điều kiện chung

- Tenant lấy từ JWT, không nhận `restaurantId` trong body. Mỗi request cần subscription đang hiệu lực có feature `PAYMENT_MANAGEMENT`.
- Thanh toán đủ một lần cho order `CONFIRMED`, `PREPARING`, `READY` hoặc `SERVED`, `paymentStatus=UNPAID`, `paidAmount=0`, `totalAmount>0`. Món chưa served vẫn có thể thanh toán. Từ chối `OPEN`, `CANCELLED`, `COMPLETED`.
- `amount` do client gửi chỉ để đối chiếu. Server so với `order.totalAmount` bằng `BigDecimal.compareTo`, không làm tròn hoặc nhận partial payment. `expectedOrderVersion` là version từ GET order ngay trước khi thanh toán.
- Ghi payment `SUCCESS`, `order.paidAmount=totalAmount`, `order.paymentStatus=PAID` và audit `PAYMENT_RECORDED` trong cùng transaction. Không thay đổi `order.status`, trạng thái món, phiên bàn, kho hoặc bếp. Bàn chỉ rảnh sau nghiệp vụ kết thúc phục vụ/đóng phiên riêng.
- Không hỗ trợ split payment, refund, bill 0 đồng, trả tiền thừa, online payment, SePay hoặc subscription billing.

## QR requests và checkout

V25 không đổi payment header/body/response/permission và vẫn cho payment trước hoặc sau phục vụ. [QR PENDING requests](public-qr-order-api.md) chưa là order/financial obligation, không khóa thanh toán toàn nhà hàng.

Khi ghi nhận payment TABLE thành công, cùng transaction cancel PENDING QR requests đã quan sát đúng order.tableSessionId và current table credential/epoch, reason `CHECKOUT_STARTED`. Request observedSessionId null không tự bị ghép vào phiên chỉ vì cùng bàn. Failure/rollback payment giữ cancellation rollback tương ứng; retry payment không audit cancellation trùng. FE nên cảnh báo các requests chưa duyệt thuộc scope này trước checkout. Khi có active payment attempt/nghĩa vụ thanh toán, QR không submit/append vào session đó; payment fail/cancel không hồi sinh requests đã CANCELLED.

Payment TAKEAWAY chỉ tác động order được chọn, không cancel request khác cùng guest/name/phone/restaurant và không block một intent QR mang đi mới. Approval replay vẫn trả original linkage sau order PAID/COMPLETED. MVP vẫn ghi payment SUCCESS thủ công; phần này không thêm gateway, webhook hoặc endpoint PENDING payment intent mới.

## POST /api/v1/orders/{orderId}/payments

Yêu cầu cả `PAYMENT_CREATE` và `PAYMENT_CONFIRM`. Headers:

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: 5f8c4a9e-2c67-45a8-b455-872bb587747d
```

Body CASH:

```json
{"expectedOrderVersion":3,"paymentMethod":"CASH","amount":150000.00}
```

Body chuyển khoản đã được nhân viên đối soát thủ công:

```json
{"expectedOrderVersion":3,"paymentMethod":"TRANSFER","amount":150000.00,"referenceNo":"BANK-TXN-123456"}
```

Tạo mới: `201 Created`, header `Idempotency-Replayed: false`. Ví dụ response:

```json
{"id":"fcb7771b-cce8-4f03-a266-d970d769d42f","orderId":"53af34fd-3320-4d8c-a22b-4016552bf105","paymentCode":"PAY-B062AF714E4E4D05A547F8534A87CE43","amount":150000.00,"currencyCode":"VND","paymentMethod":"TRANSFER","confirmationSource":"STAFF_MANUAL","provider":null,"status":"SUCCESS","referenceNo":"BANK-TXN-123456","requestedAt":"2026-10-12T09:00:00Z","paidAt":"2026-10-12T09:00:00Z","createdBy":"44c64a56-a344-451b-8e0a-5c9b7d9ce96b"}
```

Cùng tenant/key/actor/payload đã thành công: `200 OK`, header `Idempotency-Replayed: true`, body payment cũ. FE giữ nguyên key khi retry do timeout; không phát key mới. Cùng key nhưng khác payload/order/actor trả `409 PAYMENT_IDEMPOTENCY_KEY_REUSED`. Replay được xét trước version/trạng thái hiện tại, nhưng sau kiểm tra auth/permission/feature. Sau thành công, GET lại order để lấy `version`, `paymentStatus` và `paidAmount` mới.

## GET /api/v1/orders/{orderId}/payments

Yêu cầu `PAYMENT_VIEW`; headers `Authorization: Bearer <accessToken>`. Query `page=0&size=20` (`0<=page`, `1<=size<=100`). `200 OK`:

```json
{"content":[{"id":"fcb7771b-cce8-4f03-a266-d970d769d42f","orderId":"53af34fd-3320-4d8c-a22b-4016552bf105","paymentCode":"PAY-B062AF714E4E4D05A547F8534A87CE43","amount":150000.00,"currencyCode":"VND","paymentMethod":"CASH","confirmationSource":"STAFF_MANUAL","provider":null,"status":"SUCCESS","referenceNo":null,"requestedAt":"2026-10-12T09:00:00Z","paidAt":"2026-10-12T09:00:00Z","createdBy":"44c64a56-a344-451b-8e0a-5c9b7d9ce96b"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

## GET /api/v1/orders/{orderId}/payments/{paymentId}

Yêu cầu `PAYMENT_VIEW`; headers `Authorization: Bearer <accessToken>`. `200 OK` với một PaymentResponse như ở trên. Payment không thuộc order/tenant trả `404 PAYMENT_NOT_FOUND` hoặc `404 ORDER_NOT_FOUND`.

## Lỗi thường gặp

Body lỗi dùng format chung `{"success":false,"code":"...","message":"...","fieldErrors":{},"timestamp":"..."}`. `400`: `INVALID_IDEMPOTENCY_KEY`, `INVALID_PAYMENT_AMOUNT`, `UNSUPPORTED_PAYMENT_METHOD`, `TRANSFER_REFERENCE_REQUIRED`, `VALIDATION_ERROR`. `403`: `FORBIDDEN` hoặc `FEATURE_NOT_ENTITLED`. `404`: `ORDER_NOT_FOUND`, `PAYMENT_NOT_FOUND`. `409`: `ORDER_NOT_PAYABLE`, `ORDER_ALREADY_PAID`, `PAYMENT_AMOUNT_MISMATCH`, `PAYMENT_IDEMPOTENCY_KEY_REUSED`, `CONCURRENT_PAYMENT_UPDATE`, `PAYMENT_CODE_CONFLICT`. Request bị từ chối không tạo audit/payment và không tiêu thụ key.

## Hướng mở rộng SePay

`restaurantId` → tài khoản ngân hàng đã liên kết của nhà hàng → `PaymentIntent`/attempt `PENDING` chứa amount snapshot, mã chuyển khoản duy nhất và expiry → trạng thái checkout/chốt bill riêng để khóa gọi thêm → QR động → IPN/webhook đã xác thực → đối soát đúng tài khoản/mã/số tiền → gọi settlement chung để chuyển `SUCCESS`. Webhook là endpoint server-to-server riêng, không dùng JWT nhân viên. Chỉ sau khi xác thực nguồn webhook mới định danh restaurant từ tài khoản ngân hàng đã liên kết. Dedupe theo `(provider,providerTransactionId)` (partial unique toàn hệ thống, cả tenant), không dùng `Idempotency-Key` của nhân viên. Sai mã/tài khoản/số tiền hoặc intent hết hạn đưa vào đối soát thủ công, không tự đánh dấu `PAID`; cần job đối soát dự phòng cho webhook chậm/thất lạc. Không trộn dòng tiền subscription của nhà hàng với payment đơn món của khách.
