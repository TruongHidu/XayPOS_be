# CASHBOOK — Sổ thu chi

CASHBOOK đọc tiền thực thu từ payment `SUCCESS` và tiền thực chi từ phiếu chi `POSTED`.
`netCashFlowAmount = totalIncomeAmount - totalExpenseAmount` là chênh lệch thu chi;
không phải lợi nhuận hay số dư quỹ. Công thức, inventory consumption và snapshot giá vốn
không tham gia phép tính này. Không có bản sao payment trong bảng cashbook.

## Truy cập

Mọi API dùng staff JWT, tenant từ token, subscription effective, tài khoản và nhà hàng
active. Không truyền `restaurantId`. Response có `Cache-Control: no-store`.

| API/hành động | Permission | Feature |
|---|---|---|
| Đọc danh mục/giao dịch/phiếu chi/tổng hợp/series | `CASHBOOK_VIEW` | `DETAIL_REPORT` |
| Tạo chi không liên kết | `CASHBOOK_CREATE` | `DETAIL_REPORT` |
| Tạo chi liên kết phiếu nhập | `CASHBOOK_CREATE`, `INVENTORY_VIEW` | `DETAIL_REPORT`, `INVENTORY_MANAGEMENT` |
| Đối chiếu phiếu nhập | `CASHBOOK_VIEW`, `INVENTORY_VIEW` | `DETAIL_REPORT`, `INVENTORY_MANAGEMENT` |
| Void phiếu chi | `CASHBOOK_VOID` | `DETAIL_REPORT` |

Permission mới mặc định cấp system OWNER. OWNER có thể phân quyền qua STAFF catalog
hiện có; không tự cấp cho CASHIER/WAITER/KITCHEN. Không yêu cầu `PAYMENT_VIEW`,
`ORDER_VIEW`, permission công thức/giá vốn hoặc các feature order/payment để đọc thu
lịch sử. Guard chạy trước truy vấn nghiệp vụ và dùng một entitlement snapshot.

```http
Authorization: Bearer <staffAccessToken>
Accept: application/json
```

Body JSON thêm `Content-Type: application/json`. Chỉ create expense yêu cầu
`Idempotency-Key`, tối đa 100 ký tự.

## Tạo phiếu chi đã trả tiền

```http
POST /api/v1/cashbook/expenses
Idempotency-Key: expense-20261029-001
Content-Type: application/json
```

```json
{
  "categoryCode": "INGREDIENT_PURCHASE",
  "amount": 400000.00,
  "currencyCode": "VND",
  "paymentMethod": "TRANSFER",
  "expenseAt": "2026-10-29T03:30:00Z",
  "stockDocumentId": "<confirmed-receipt-uuid>",
  "referenceNo": "BANK-001",
  "payeeName": "Nhà cung cấp A",
  "note": "Đã trả đợt 1"
}
```

Khoản chi điện nước có thể dùng `categoryCode=UTILITIES`, `paymentMethod=CASH` và
bỏ `stockDocumentId`. Danh mục seed: `INGREDIENT_PURCHASE`, `RENT`, `SALARY`,
`UTILITIES`, `MAINTENANCE`, `OTHER`. `SALES_COLLECTION` chỉ dùng cho khoản thu hệ thống.

- `amount > 0`, tối đa 16 chữ số nguyên/2 chữ số lẻ; không tự làm tròn input lỗi.
- `paymentMethod`: `CASH` hoặc `TRANSFER`.
- `expenseAt` là thời điểm thực chi, bắt buộc, không được ở tương lai; có thể khác
  `createdAt` khi ghi sổ muộn.
- `currencyCode` tùy chọn, mặc định currency nhà hàng và được chốt vào phiếu.
- `referenceNo` tối đa 100, `payeeName` tối đa 150, `note` tối đa 10000 ký tự.
- Có nguồn phải là same-tenant `RECEIPT/CONFIRMED`, category `INGREDIENT_PURCHASE`.
- Nguồn có currency đã biết phải khớp currency phiếu chi. Nguồn cũ UNKNOWN được liên
  kết làm tham chiếu, không suy ra thanh toán đủ/thiếu.
- Nhiều phiếu chi được liên kết cùng nguồn; không có cap theo totalCost/công nợ.

Lần đầu trả `201`, replay hợp lệ `200`; header `Idempotency-Replayed: true/false`.
Same tenant/key + canonical payload/actor khác trả `409 CASHBOOK_IDEMPOTENCY_KEY_REUSED`.
Replay sau void trả phiếu VOIDED hiện tại, không ghi chi trở lại.

Response là expense DTO trực tiếp:

```json
{
  "id": "<expense-uuid>",
  "expenseCode": "<generated-code>",
  "categoryCode": "INGREDIENT_PURCHASE",
  "categoryName": "Mua nguyên liệu",
  "amount": 400000.00,
  "currencyCode": "VND",
  "paymentMethod": "TRANSFER",
  "expenseAt": "2026-10-29T03:30:00Z",
  "stockDocumentId": "<confirmed-receipt-uuid>",
  "sourceDocumentCode": "<receipt-code>",
  "sourceCurrencyStatus": "KNOWN",
  "referenceNo": "BANK-001",
  "payeeName": "Nhà cung cấp A",
  "note": "Đã trả đợt 1",
  "status": "POSTED",
  "createdBy": "<actor-uuid>",
  "createdAt": "<instant>",
  "updatedAt": "<instant>",
  "version": 0,
  "voidedBy": null,
  "voidedAt": null,
  "voidReason": null
}
```

## Quản lý phiếu chi

```http
GET /api/v1/cashbook/expenses?fromDate=2026-10-01&toDate=2026-10-29&page=0&size=20
GET /api/v1/cashbook/expenses/{expenseId}
POST /api/v1/cashbook/expenses/{expenseId}/void
```

List lọc theo `expenseAt`: `status=POSTED|VOIDED` (bỏ trống lấy cả hai),
`categoryCode`, `paymentMethod`, `stockDocumentId`, `createdBy`, `currencyCode`, `q`.
Sort `expenseAt` (mặc định), `amount`, `expenseCode`, `createdAt`; direction mặc định
`desc`; page 0/size 20, size tối đa 100. List trả envelope chứa `PageResponse`.
Detail trả expense DTO trực tiếp; foreign tenant/missing ID trả 404.

Void body:

```json
{"expectedVersion": 0, "reason": "Ghi nhầm số tiền, sẽ lập phiếu thay thế"}
```

Reason bắt buộc, tối đa 1000 ký tự. Void lần đầu kiểm version; gọi lại trả trạng thái
hiện tại và giữ nguyên metadata void đầu tiên. Response là expense DTO `VOIDED`.
Phiếu giữ bất biến số tiền/currency/ngày chi/phương thức/category/nguồn. Không có
PATCH/DELETE. Void là sửa ghi sổ nhầm, không phải hoàn tiền ngân hàng. Không tạo thu
bù giả và không thay stock/payment. Báo cáo quá khứ theo expenseAt được tính lại sau
void; chưa hỗ trợ lịch sử báo cáo as-of. Audit tạo/void cùng transaction với phiếu;
replay/no-op không tạo audit trùng.

## Danh mục

```http
GET /api/v1/cashbook/categories
GET /api/v1/cashbook/categories?type=EXPENSE
```

Trả array trực tiếp gồm `code`, `name`, `type`, `active`; `type=INCOME|EXPENSE` tùy chọn.
MVP không có API CRUD danh mục.

## Danh sách dòng tiền

```http
GET /api/v1/cashbook/entries?fromDate=2026-10-01&toDate=2026-10-29&currencyCode=VND&page=0&size=20
```

Filter chung cho entries/summary/series: `fromDate`, `toDate` bắt buộc, `type`
(`INCOME|EXPENSE`), `sourceType` (`ORDER_PAYMENT|EXPENSE_VOUCHER`), `categoryCode`,
`paymentMethod`, `currencyCode`, `createdBy`, `q` tùy chọn. Filter cùng áp vào hai
nguồn, kể cả trường hợp tổ hợp filter không khớp thì trả tập rỗng.
Entries thêm pagination/sort `occurredAt|amount|code`, mặc định `occurredAt desc`.
Tie-breaker dùng sourceType + sourceId, không giả định UUID giữa hai bảng khác nhau.

`ORDER_PAYMENT`: payment SUCCESS, ngày `paidAt`, amount payment; không yêu cầu order
COMPLETED và không cộng order.totalAmount. `EXPENSE_VOUCHER`: chỉ POSTED, ngày
`expenseAt`. Phiếu VOIDED chỉ xem qua quản lý phiếu chi. Hai payment cùng amount
vẫn là hai khoản thu. Không lộ danh tính khách/provider payload/recipe/cost snapshot.

Read row: `sourceType`, `sourceId`, `code`, `type`, `categoryCode`, `categoryName`,
`amount`, `currencyCode`, `paymentMethod`, `occurredAt`, `recordedAt`, `createdBy`,
`orderId`, `orderCode`, `stockDocumentId`, `sourceDocumentCode`. Field không áp dụng null.

## Tổng hợp và chuỗi thời gian

```http
GET /api/v1/cashbook/summary?fromDate=2026-10-01&toDate=2026-10-29
GET /api/v1/cashbook/series?fromDate=2026-10-01&toDate=2026-10-29&granularity=DAY
```

Series hỗ trợ `DAY`/`MONTH`, mặc định DAY, zero-fill bucket trống. Summary/series dùng
cùng filter với entries, không có page/sort. Tổng counts/amount series khớp summary.

```json
{
  "meta": {
    "fromDate": "2026-10-01", "toDate": "2026-10-29",
    "timezone": "Asia/Ho_Chi_Minh", "currencyCode": "VND",
    "dateBasis": "CASH_OCCURRED_AT", "granularity": null,
    "generatedAt": "<instant>"
  },
  "data": {
    "totalIncomeAmount": 1000000.00, "totalExpenseAmount": 500000.00,
    "netCashFlowAmount": 500000.00,
    "incomeEntryCount": 1, "expenseEntryCount": 2,
    "saleIncomeAmount": 1000000.00,
    "receiptLinkedExpenseAmount": 400000.00, "otherExpenseAmount": 100000.00
  }
}
```

Khoản chi liên kết receipt và chi khác là hai phần của tổng chi, không cộng thêm lần
nữa. Mỗi series row có `bucketStartDate`, ba amount tổng và hai count. Chênh lệch âm
được giữ. Empty trả 200/amount-count 0/list rỗng.

Ngày inclusive theo timezone hiện tại của nhà hàng, query `[fromInclusive,toExclusive)`,
tối đa 366 ngày. Currency mặc định theo nhà hàng; query currency lịch sử tường minh.
Không trộn/chuyển currency. Đổi timezone có thể đổi cách gom ngày lịch sử.

## Đối chiếu phiếu nhập

```http
GET /api/v1/cashbook/stock-receipts?fromDate=2026-10-01&toDate=2026-10-29&includeUnknownCurrency=true
```

Lọc theo `confirmedAt`, chỉ RECEIPT/CONFIRMED. Filter `warehouseId`, `currencyCode`,
`includeUnknownCurrency=false` mặc định, `q`; sort `confirmedAt|documentCode|totalCost`,
mặc định `confirmedAt desc`. Trả envelope/meta `dateBasis=CONFIRMED_AT` và page.

Row: `stockDocumentId`, `documentCode`, `warehouseId`, `warehouseName`, `confirmedAt`,
`totalCost`, `currencyCode`, `currencyStatus`, `linkedPostedExpenseCount`,
`recordedExpenseAmountAllTime`. Tổng chi liên kết là toàn thời gian, chỉ POSTED và
cùng currency; nguồn UNKNOWN trả amount đối chiếu null. Không suy ra còn nợ/tất toán.

Phiếu nhập tháng 10 trả tiền tháng 11 xuất hiện ở nguồn nhập kho tháng 10, còn khoản
chi xuất hiện tháng 11. Receipt.totalCost không được tự cộng vào tổng chi.

V39 thêm `stock_documents.currency_code` nullable. Fresh receipt chốt currency khi
tạo DRAFT, confirm/replay giữ nguyên. Phiếu cũ vẫn NULL/UNKNOWN, không backfill theo
currency hôm nay. Tạo/void phiếu chi không ghi lại currency hay totalCost của nguồn.

## Lỗi và phạm vi

400: `VALIDATION_ERROR`, `INVALID_CASHBOOK_DATE_RANGE`, `CASHBOOK_RANGE_TOO_LARGE`,
`INVALID_CASHBOOK_CURRENCY`, `INVALID_CASHBOOK_SORT`, `INVALID_CASHBOOK_FILTER`,
`INVALID_EXPENSE_AMOUNT`, `EXPENSE_DATE_IN_FUTURE`, `INVALID_IDEMPOTENCY_KEY`.
401 thiếu/sai JWT. 403 thiếu permission/feature/tenant hoặc subscription không effective.
404 `CASHBOOK_EXPENSE_NOT_FOUND`, `CASHBOOK_CATEGORY_NOT_FOUND` hoặc nguồn không tồn tại
trong tenant. 409 `CASHBOOK_IDEMPOTENCY_KEY_REUSED`, `CONCURRENT_CASHBOOK_UPDATE`,
`INVALID_STOCK_RECEIPT_LINK`, `STOCK_RECEIPT_CURRENCY_MISMATCH` theo business case.

Không có công nợ/supplier/FX/refund thực tế/số dư đầu kỳ/kế toán kép/export/realtime.
Xem [OpenAPI](openapi/cashbook-api.yaml) và [đồng bộ FE](cashbook-fe-sync.md).
