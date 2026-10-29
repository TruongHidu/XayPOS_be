# Đồng bộ FE — snapshot giá vốn tham chiếu lịch sử

FE đọc summary qua `GET /api/v1/orders/{orderId}/items/{orderItemId}/cost-snapshot`
bằng Bearer JWT. Chỉ hiển thị khi có cả ORDER_VIEW/REPORT_DETAIL_VIEW và feature
ORDER_MANAGEMENT/DETAIL_REPORT. API có `Cache-Control: no-store`. Không dùng role
hoặc tên gói để thay grant; không yêu cầu feature recipe/inventory cho summary.

## Hiển thị dữ liệu

| costStatus | Gợi ý nhãn / cách xử lý |
| --- | --- |
| COMPLETE | “Giá vốn tham chiếu đã chốt”; hiện unitCost/totalCost theo currencyCode |
| INCOMPLETE | “Chưa đủ giá tham chiếu”; hiện lý do, giữ hai số tiền trống |
| UNAVAILABLE | “Không khả dụng tại lúc gửi món”; reason RECIPE_FEATURE_DISABLED |
| LEGACY_UNAVAILABLE | “Chưa có giá vốn lịch sử”; không lấy giá catalogue hiện tại để điền |
| NOT_SUBMITTED | “Chưa tiếp nhận”; chưa có nguồn và số tiền đã chốt |

Unknown khác configured zero: COMPLETE với 0.00 là số tiền hợp lệ; null luôn là
chưa có số tiền đầy đủ. Không `value ?? 0`, không coi tổng phần đã biết là total
đầy đủ và không cộng các dòng khác currency. Dùng decimal-safe formatting; số
NUMERIC(38,2) có thể vượt khả năng chính xác của JavaScript Number.

`costBasis=CATALOG_REFERENCE_AT_ACCEPTANCE` chỉ cho version 1. Legacy/draft có
version, basis và costCapturedAt null. Legacy costSource giữ MANUAL/RECIPE nếu đã
lưu thực sự, có thể null; không suy từ recipeEnabled hiện tại. CAPTURED của recipe
không chứng minh COMPLETE của tiền. `costCapturedAt` là acceptance, không phải
thời điểm tiêu thụ/ghi nhận chi phí.

Với recipe, hiển thị totalCost server trả làm tổng authoritative. Không nhân
unitCost × orderQuantity để thay total: quantity=3, total=0.01 có thể unit=0.00.
Cost-estimate live có semantics batch/yield khác snapshot lượng kho đã CEILING;
không dùng estimate để ghi đè số tiền lịch sử. Không đổi đơn vị/currency trên client.

## Luồng làm việc

- Giữ request/Idempotency-Key/version của create/confirm/append/QR hiện có; client
  không gửi cost snapshot lên server. DRAFT/cart/QR PENDING/review chưa chốt giá.
  SUBMIT/confirm/approve dùng giá tại acceptance mới.
- Gọi thêm giữ dòng mới riêng, không gộp dòng cùng itemId làm mất hai lần chốt giá.
  Đổi giá/recipe/toggle/package sau đó không đổi dòng cũ.
- INCOMPLETE/UNAVAILABLE không chặn cooking, serve, payment hoặc complete trên UI.
  Giữ xử lý business conflicts hiện có; không cần người bán có quyền đọc giá vốn.
- Không tự sửa historical amounts khi hủy/release/refund. Snapshot vẫn tồn tại
  để kiểm toán; tiêu thụ kho đi theo allocation/ledger riêng.
- 403: kiểm lại grant, không tự gọi recipe/allocation detail để thay thế summary.
  404 theo tenant/line ownership. 409 snapshot conflict cần reload/kiểm backend,
  không tự capture hoặc lấy giá live chữa lịch sử. UI dùng error code.
- Không có event mới. ORDER_CHANGED giữ payload cũ; màn hình summary có thể GET
  lại theo mutation/refresh hiện có khi còn quyền. Không thêm cost vào public
  QR/menu, KDS hoặc ordinary OrderResponse, local public caches hay socket payload.

## Inventory transactions

RECIPE_USE.unitCost mới bằng giá frozen của tracked usage: known giữ nguyên giá,
configured zero hiển thị 0.00, unknown/legacy hiển thị “Chưa có giá tham chiếu”.
Document fields vẫn null cho ORDER_ITEM. Header INCOMPLETE không buộc mọi ledger
unitCost null vì tracked ingredient riêng có thể đã biết giá.

Không suy tổng giá vốn món từ ledger: MANUAL và untracked usages không tạo
RECIPE_USE; snapshot cũng không chứng minh món đã consume. Không gắn nhãn “actual
COGS”, “lợi nhuận ròng” hoặc báo cáo lợi nhuận hoàn chỉnh cho các số tiền này.

[API và ví dụ](order-reference-cost-snapshot-api.md) ·
[Thiết kế](order-reference-cost-snapshot-implementation.md)
