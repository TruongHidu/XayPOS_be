# Cảnh báo tồn thấp — Inventory Giai đoạn 6

Base: `/api/v1/inventory/alerts`. Tenant lấy từ JWT, không nhận restaurantId.
Đây là API nội bộ nhà hàng, không dành cho public QR.

## Security và header

```http
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json
```

Content-Type chỉ cần khi gửi JSON body. Cả ba endpoint cần subscription effective
có **INVENTORY_MANAGEMENT và LOW_STOCK_ALERT**, cùng guard account/restaurant
hiện có. GET cần `INVENTORY_VIEW`; acknowledge cần đồng thời
`INVENTORY_VIEW` và `INVENTORY_ALERT_ACKNOWLEDGE`.

Permission mới được seed cho system OWNER/SUPER_ADMIN; owner có thể cấp cho staff
qua permission catalog hiện có, không mặc định cấp cho cashier/kitchen/manager.
Không sửa package mapping hoặc subscription snapshot để tự bật feature.

## 1. Danh sách

```http
GET /api/v1/inventory/alerts?status=OPEN&page=0&size=20
```

Không có body. Query optional:

| Query | Kiểu/giới hạn |
| --- | --- |
| warehouseId, ingredientId | UUID |
| status | OPEN / ACKNOWLEDGED / RESOLVED; bỏ trống trả tất cả |
| q | Tìm substring tên/SKU **snapshot**, tối đa 100 ký tự; `%`, `_` là ký tự literal |
| triggeredFrom | ISO Instant, inclusive |
| triggeredTo | ISO Instant, exclusive; from phải nhỏ hơn to |
| page | >=0, mặc định 0 |
| size | 1–100, mặc định 20 |
| sortBy | triggeredAt (mặc định) / updatedAt |
| direction | desc (mặc định) / asc; tie-breaker ID ASC |

Response `200`, `Cache-Control: no-store`:

```json
{
  "content": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

## 2. Chi tiết

```http
GET /api/v1/inventory/alerts/{alertId}
```

Không body/query. Response `200`, `Cache-Control: no-store`:

```json
{
  "id": "11111111-1111-4111-8111-111111111111",
  "warehouseId": "22222222-2222-4222-8222-222222222222",
  "warehouseCode": "KHO-CHINH",
  "warehouseName": "Kho chính",
  "ingredientId": "33333333-3333-4333-8333-333333333333",
  "ingredientSku": "NL-THIT-BO",
  "ingredientName": "Thịt bò",
  "unit": "g",
  "status": "OPEN",
  "quantityAtTrigger": 150.000,
  "thresholdQuantity": 200.000,
  "currentQuantity": 150.000,
  "reservedQuantity": 50.000,
  "availableQuantity": 100.000,
  "currentMinQuantity": 200.000,
  "triggeredAt": "2026-10-21T08:00:00Z",
  "acknowledgedBy": null,
  "acknowledgedAt": null,
  "resolvedAt": null,
  "resolutionReason": null,
  "version": 0,
  "createdAt": "2026-10-21T08:00:00Z",
  "updatedAt": "2026-10-21T08:00:00Z"
}
```

Mỗi phần tử `content` có cùng DTO trên. Không có wrapper success/data cho response
thành công. Những field snapshot gồm metadata kho/nguyên liệu, unit,
quantityAtTrigger, thresholdQuantity, triggeredAt; chúng không đổi khi nguồn được
đổi tên, sửa threshold hoặc nhập kho. `ingredientSku` có thể null.

Các số currentQuantity/reservedQuantity/availableQuantity/currentMinQuantity lấy
từ balance **hiện tại**, không làm tăng version của alert. Chúng có thể null nếu
kho inactive/deleted, ingredient untracked/deleted hoặc unit hiện tại khác unit
snapshot; không cộng/trộn số khác đơn vị. Alert lịch sử vẫn đọc được sau khi nguồn
soft-delete, bỏ tracking hoặc đổi tên. Không trả recipe hoặc giá vốn.

## 3. Xác nhận đã xem

```http
POST /api/v1/inventory/alerts/{alertId}/acknowledge
Authorization: Bearer <accessToken>
Content-Type: application/json
```

```json
{"expectedVersion": 0}
```

Body bắt buộc; expectedVersion phải có và >=0. Actor/thời gian lấy từ server.
Response `200` cùng DTO detail, status ACKNOWLEDGED, acknowledgedBy/At được chốt,
version tăng. ACKNOWLEDGED replay trả hiện trạng, kể cả version của lần gọi đầu đã
cũ; không đổi actor/time/version/audit. OPEN với stale version trả 409. RESOLVED
không được acknowledge. Không cần Idempotency-Key.

ACK chỉ là đã xem, **không** phải đã nhập kho hoặc đã hết thiếu. Nó không sửa
quantity, reservedQuantity, threshold, order hoặc inventory ledger.

## Quy tắc và vòng đời

Low = `minQuantity > 0 && quantity <= minQuantity`, dùng **tồn vật lý**.
Kho phải active/chưa xóa; item là INGREDIENT tracked/chưa xóa. Ingredient inactive
vẫn được theo dõi như GET `/inventory/low-stock` hiện có. Threshold 0 tắt cảnh báo;
logical zero chưa cấu hình không tạo row balance/alert.

```text
OPEN -> ACKNOWLEDGED -> RESOLVED
OPEN ----------------> RESOLVED
```

Tồn vẫn thấp: giữ nguyên đợt cảnh báo và ACK. Tồn vượt threshold: resolve.
Thiếu lại: tạo ID mới, không mở lại lịch sử. Thay threshold nhưng vẫn low không
ghi đè thresholdQuantity lúc trigger. Các reason:

- STOCK_RECOVERED: physical vượt threshold hiện tại.
- THRESHOLD_DISABLED: minQuantity=0.
- WAREHOUSE_INACTIVE / WAREHOUSE_DELETED.
- INGREDIENT_UNTRACKED / INGREDIENT_DELETED.
- INGREDIENT_UNIT_CHANGED: kết thúc episode đơn vị cũ rồi đánh giá bằng đơn vị mới.

Không tự quy đổi/reset threshold khi đổi baseUnit; owner phải kiểm tra lại giá trị
threshold. Disable ingredient đơn thuần không resolve. Đổi default không chuyển
cảnh báo sang kho khác.

Reserve/release chỉ đổi reserved, không tạo/resolve physical-low alert.
Stock confirm và order consume (start-cooking hoặc bulk pending->served) đánh giá
số dư cuối cùng, cùng transaction với balance/ledger/order/audit. Thiếu feature
alert không làm hỏng các thao tác nội bộ: projection vẫn được duy trì, nhưng API
đọc/ack bị feature gate. Staff chỉ cần quyền command hiện có cho side effect này.

## Lỗi

Error envelope dùng chung gồm success=false/code/message/fieldErrors/timestamp.

| HTTP | Code |
| --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY |
| 401 | UNAUTHORIZED / ACCOUNT_INACTIVE theo guard hiện tại |
| 403 | FORBIDDEN / FEATURE_NOT_ENTITLED / SUBSCRIPTION_NOT_ACTIVE / restaurant guard |
| 404 | LOW_STOCK_ALERT_NOT_FOUND, gồm tenant khác |
| 409 | INVALID_LOW_STOCK_ALERT_TRANSITION / CONCURRENT_LOW_STOCK_ALERT_UPDATE |

## Postman: vòng đời đầy đủ

1. Dùng token owner có hai feature và quyền nêu trên. Tạo kho và tracked ingredient
   `baseUnit=g` theo [Inventory API](inventory-api.md), nhập 1000 g bằng RECEIPT.
2. PATCH `/inventory/warehouses/{warehouseId}/balances/{ingredientId}/threshold`
   với `{"expectedVersion":<balanceVersion>,"minQuantity":200}`. Chưa có cảnh báo.
3. Confirm ISSUE 800 g: physical=200, xuất hiện một OPEN. GET alerts lấy alertId.
4. POST acknowledge `{"expectedVersion":0}`: ACKNOWLEDGED. Gửi lại body lần đầu:
   vẫn 200, không thêm audit/version.
5. Confirm ISSUE 50 g: physical=150; vẫn cùng alert và ACKNOWLEDGED. Trigger
   quantity vẫn 200, currentQuantity=150.
6. Confirm RECEIPT 51 g: physical=201; alert RESOLVED/STOCK_RECOVERED, giữ actor ACK.
7. POST acknowledge alert vừa resolved: 409 INVALID_LOW_STOCK_ALERT_TRANSITION.
8. Confirm ISSUE 1 g: physical=200; OPEN mới với ID mới, lịch sử RESOLVED còn nguyên.
9. PATCH threshold=0 với balanceVersion mới: OPEN vừa tạo được resolve bằng
   THRESHOLD_DISABLED. GET `/low-stock` và GET alerts không ghi dữ liệu.
10. Thử token tenant khác: detail/ack ID trên trả 404. Token chỉ INVENTORY_VIEW đọc
    được nhưng không ACK; thiếu LOW_STOCK_ALERT trả 403.

Test reserve: physical=1000, min=200, reserved=900 => available=100 nhưng chưa low.
Chỉ sau consume làm physical giảm mới mở alert. Cancel pending/release không mở
cảnh báo. Replay consume/stock confirm không thêm alert hoặc audit.

## Baseline và giới hạn

V34 tạo OPEN cho các balance đang low tại thời điểm nâng cấp, snapshot dữ liệu
hiện tại; triggeredAt là **thời điểm phát hiện khi nâng cấp**, không phải thời điểm
thiếu thật trong quá khứ. Không backfill lịch sử, audit người dùng giả hay thay tồn.

GET `/inventory/low-stock` vẫn là balance đang thấp, không có acknowledge/lịch sử.
GET `/inventory/alerts` là các episode đã lưu, gồm cả đã resolved. Không có endpoint
tạo/resolve/delete alert thủ công. WebSocket/STOMP `/ws` phát thay đổi sau commit
cho client đang kết nối và đủ quyền; xem [realtime](inventory-alert-realtime.md).
Không có push hệ điều hành/email/outbox/replay; không tự đặt mua, nhập kho,
chuyển kho, kiểm kê hoặc sửa giá vốn.

Thiết kế: [implementation](low-stock-alert-implementation.md).
Đồng bộ UI: [FE sync](low-stock-alert-fe-sync.md).
