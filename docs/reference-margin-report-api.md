# Báo cáo giá vốn và lãi gộp tham chiếu

Ba API chỉ đọc dành cho nhân viên của nhà hàng, yêu cầu đồng thời permission `ORDER_VIEW`, `REPORT_DETAIL_VIEW` và feature `ORDER_MANAGEMENT`, `DETAIL_REPORT` trong subscription đang có hiệu lực. Mọi request gửi `Authorization: Bearer <accessToken>` và `Accept: application/json`; không có body. Tenant lấy từ token. Response dùng `ReportEnvelope` và `Cache-Control: no-store`.

| API | Mục đích |
| --- | --- |
| `GET /api/v1/reports/reference-margin/summary` | Tổng quan trong khoảng ngày |
| `GET /api/v1/reports/reference-margin/series` | Chuỗi ngày hoặc tháng |
| `GET /api/v1/reports/reference-margin/orders` | Danh sách đơn đối chiếu có phân trang |

Cả ba yêu cầu `fromDate`, `toDate` dạng `yyyy-MM-dd`; nhận thêm `currencyCode`, `serviceType` (`DINE_IN`, `TAKEAWAY`) và `sourceChannel` (`CASHIER`, `WAITER`, `QR_STATIC`, `QR_TABLE`). Khoảng ngày tính theo timezone hiện tại của nhà hàng, hai đầu ngày đều được tính, tối đa 366 ngày. Timestamp được lọc `[đầu fromDate, đầu ngày sau toDate)`. Currency mặc định theo nhà hàng, có thể chọn currency lịch sử; không quy đổi hoặc gộp currency. `sourceChannel` là kênh tạo root order. `meta.dateBasis` là `COMPLETED`.

Ví dụ:

```http
GET /api/v1/reports/reference-margin/summary?fromDate=2026-10-01&toDate=2026-10-28&currencyCode=VND
Authorization: Bearer <accessToken>
Accept: application/json
```

Response mẫu (`meta` được rút gọn trong ví dụ):

```json
{
  "meta": {"fromDate":"2026-10-01","toDate":"2026-10-28","timezone":"Asia/Ho_Chi_Minh","currencyCode":"VND","generatedAt":"2026-10-28T12:00:00Z","serviceType":null,"sourceChannel":null,"granularity":null,"dateBasis":"COMPLETED"},
  "data": {
    "costBasis":"CATALOG_REFERENCE_AT_ACCEPTANCE",
    "marginScope":"FULLY_COSTED_COMPLETED_ORDERS",
    "costCoverageStatus":"PARTIAL",
    "completedOrderCount":10,"fullyCostedOrderCount":8,"excludedOrderCount":2,"orderCoveragePercent":80.00,
    "subtotalAmount":1050000.00,"discountAmount":50000.00,"taxAmount":80000.00,"serviceChargeAmount":20000.00,
    "salesAmount":1100000.00,"netSalesAmount":1000000.00,
    "coveredNetSalesAmount":800000.00,"coveredReferenceCostAmount":300000.00,
    "coveredReferenceMarginAmount":500000.00,"coveredReferenceMarginPercent":62.50,
    "costIssues":[{"code":"INCOMPLETE","orderCount":2,"itemCount":2}]
  }
}
```

Các số không có tiền tố `covered` là của toàn bộ đơn hoàn tất. Các số `covered` chỉ gồm đơn đủ giá vốn của mọi dòng không hủy. `salesAmount = subtotalAmount - discountAmount + taxAmount + serviceChargeAmount`; `netSalesAmount = subtotalAmount - discountAmount`. Giá vốn là tổng `totalCostSnapshot` lịch sử, không nhân lại unit cost đã làm tròn. `coveredReferenceMarginAmount = coveredNetSalesAmount - coveredReferenceCostAmount`. Tỷ lệ bằng margin chia net sales cùng phạm vi, nhân 100 và làm tròn HALF_UP hai chữ số; trả null khi mẫu số không dương. Margin âm là hợp lệ.

Coverage `COMPLETE` nghĩa là mọi đơn có đủ giá, `PARTIAL` có cả đơn đủ và thiếu, `UNAVAILABLE` có đơn nhưng không đơn nào đủ, `NO_DATA` không có đơn. Giá đã cấu hình bằng 0 vẫn đầy đủ; `INCOMPLETE`, `UNAVAILABLE` và `LEGACY_UNAVAILABLE` không phải giá 0. `NO_ELIGIBLE_ITEMS` chỉ đơn không có dòng được xét. Một đơn có thể có nhiều issue, do đó tổng `orderCount` của từng issue có thể lớn hơn `excludedOrderCount`. `orderCoveragePercent` là null khi không có đơn. Nếu không có đơn đủ giá, các tổng covered bằng 0 vì tập rỗng, và tỷ lệ margin là null.

`series` nhận thêm `granularity=DAY|MONTH` (mặc định `DAY`). Mỗi phần tử có `bucketStartDate` và cùng metric với summary. Bucket trống có `NO_DATA`, count/amount bằng 0, percentage null. Tháng đầu và cuối được giới hạn theo ngày request. Tổng count/amount qua buckets đối chiếu được summary; không cộng hoặc lấy trung bình các percentage.

`orders` nhận `page` (mặc định 0), `size` (mặc định 20, tối đa 100), `costCoverage=ALL|COMPLETE|EXCLUDED` (mặc định `ALL`), `sortBy=completedAt|netSalesAmount|referenceCostAmount|referenceMarginAmount` (mặc định `completedAt`) và `direction=asc|desc` (mặc định `desc`). `data` là `PageResponse` chứa `content`, `page`, `size`, `totalElements`, `totalPages`. Một row có `orderId`, `orderCode`, `completedAt`, `serviceType`, `sourceChannel`, các amount của đơn, `eligibleItemCount`, `fullyCostedItemCount`, `costCoverageStatus`, `costIssues`, `referenceCostAmount`, `referenceMarginAmount`, `referenceMarginPercent`. Các trường reference amount/percent của đơn không đủ giá là null. Sort cost/margin đặt null cuối danh sách.

Lỗi chính: 400 `INVALID_REPORT_DATE_RANGE`, `REPORT_RANGE_TOO_LARGE`, `INVALID_REPORT_CURRENCY`, `INVALID_REPORT_SORT`, `VALIDATION_ERROR`; 401 `UNAUTHORIZED`; 403 `FORBIDDEN`, `TENANT_ACCESS_DENIED`, `FEATURE_NOT_ENTITLED` hoặc lỗi trạng thái subscription; 409 `INVALID_REPORT_TIMEZONE`, `REPORT_COST_DATA_CONFLICT` cho snapshot bất nhất. Thiếu giá hợp lệ trả 200 kèm coverage.

Giá chốt tại acceptance là giá tham chiếu lịch sử. Báo cáo chưa phản ánh đầy đủ hao hụt, refund hoặc chi phí vận hành; không phải actual COGS hay lợi nhuận ròng.
