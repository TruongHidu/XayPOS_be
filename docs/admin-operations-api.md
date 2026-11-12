# Super admin: đối soát thanh toán, doanh thu và hỗ trợ nhà hàng

Mọi endpoint yêu cầu tài khoản `SUPER_ADMIN` hệ thống (`restaurantId = null`) và quyền ghi bên dưới. Mọi thao tác ghi đều được lưu vào `audit_logs`.

## Đơn mua gói — `SUBSCRIPTION_VIEW` (đọc), `SUBSCRIPTION_PAYMENT_REVIEW` (xử lý)

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/v1/admin/subscription-purchases?status&restaurantId&q&page&size` | Danh sách đơn mua của mọi nhà hàng |
| GET | `/api/v1/admin/subscription-purchases/{id}` | Chi tiết + các lần tạo QR + giao dịch liên quan |
| POST | `/api/v1/admin/subscription-purchases/{id}/close` `{note}` | Đóng đơn `REVIEW_REQUIRED` (giải phóng slot để mua lại; hoàn tiền làm ngoài hệ thống) |
| POST | `/api/v1/admin/subscription-purchases/{id}/resume-activation` | Đơn đã nhận tiền nhưng bị chặn (xung đột gói, nhà hàng bị khoá…) → `PAID` và kích hoạt lại |

## Giao dịch chuyển khoản — `SUBSCRIPTION_PAYMENT_REVIEW`

| Method | Path | Mô tả |
|---|---|---|
| GET | `/api/v1/admin/payment-events?state&reviewReason&restaurantId&q&page&size` | Sao kê nhận từ SePay |
| GET | `/api/v1/admin/payment-events/{id}` | Chi tiết một giao dịch |
| POST | `/api/v1/admin/payment-events/{id}/accept` `{note, purchaseId?}` | Xác nhận giao dịch là tiền của đơn (bắt buộc `purchaseId` nếu giao dịch chưa gắn đơn). Đơn → `PAID`, giao dịch → `CLOSED`, sau đó kích hoạt gói |
| POST | `/api/v1/admin/payment-events/{id}/retry` | Trả giao dịch về worker (`RETRY_PENDING`); nếu đơn đang chờ vì chính giao dịch này thì kích hoạt lại đơn |
| POST | `/api/v1/admin/payment-events/{id}/close` `{note ≥10, resolutionReference ≥3}` | Đóng giao dịch (đã hoàn tiền / không liên quan) |
| GET | `/api/v1/admin/payment-reviews/counts` | Số giao dịch và đơn đang chờ xử lý (badge sidebar) |

Quy tắc xác nhận thủ công nằm trong `ManualPaymentAcceptancePolicy`: giao dịch phải ở `REVIEW_REQUIRED`, là tiền vào, đơn chưa có tiền và đang `PENDING_PAYMENT`/`EXPIRED`/`REVIEW_REQUIRED`; đơn `EXPIRED` không được có đơn mở khác. Mã lỗi 409: `PURCHASE_REQUIRED`, `PAYMENT_EVENT_NOT_IN_REVIEW`, `PAYMENT_EVENT_NOT_INCOMING`, `PAYMENT_EVENT_LINKED_ELSEWHERE`, `PURCHASE_ALREADY_PAID`, `PURCHASE_NOT_PAYABLE`, `OPEN_PURCHASE_EXISTS`, `PURCHASE_HAS_NO_ATTEMPT`, `PURCHASE_NOT_IN_REVIEW`, `PURCHASE_NOT_RESUMABLE`.

## Doanh thu — `ADMIN_DASHBOARD_VIEW`

`GET /api/v1/admin/dashboard/revenue?months=12` (3–24). Doanh thu = giá gói của subscription, ghi nhận theo tháng kích hoạt (múi giờ `Asia/Ho_Chi_Minh`); "online" là subscription sinh ra từ đơn mua đã thanh toán, phần còn lại do admin tạo. MRR quy đổi giá mỗi gói đang hiệu lực về 30,44 ngày. Kèm phân bổ theo gói, số nhà hàng rời bỏ 30 ngày và tối đa 10 gói hết hạn trong 14 ngày.

## Hỗ trợ nhà hàng

| Method | Path | Quyền | Mô tả |
|---|---|---|---|
| GET | `/api/v1/admin/restaurants/{rid}/usage` | `RESTAURANT_VIEW` | Mức sử dụng 30 ngày so với gói hiệu lực |
| GET/POST | `/api/v1/admin/restaurants/{rid}/notes` | VIEW / `RESTAURANT_MANAGE` | Ghi chú nội bộ (V46 `restaurant_admin_notes`) |
| DELETE | `/api/v1/admin/restaurants/{rid}/notes/{noteId}` | `RESTAURANT_MANAGE` | Xoá mềm ghi chú |
| PATCH | `/api/v1/admin/restaurants/{rid}/users/{uid}/status` `{active, reason}` | `RESTAURANT_MANAGE` | Khoá/mở tài khoản; khoá sẽ thu hồi phiên |
| POST | `/api/v1/admin/restaurants/{rid}/users/{uid}/temporary-password` `{reason}` | `RESTAURANT_MANAGE` | Cấp mật khẩu tạm 12 ký tự (trả về một lần, `Cache-Control: no-store`), thu hồi phiên; mật khẩu không được ghi vào audit |

## Kiến trúc

Mỗi module tách đọc/ghi: controller → service (quy tắc nghiệp vụ, transaction, audit) → port (`application/port`) → adapter JDBC (`query/`). Thứ tự khoá khi ghi giống luồng đối soát tự động: restaurant → purchase → attempts → event.
