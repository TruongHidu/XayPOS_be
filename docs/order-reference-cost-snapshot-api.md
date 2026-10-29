# Snapshot giá vốn tham chiếu lịch sử của dòng order — Giai đoạn 2

Giá được chốt tại acceptance, theo giá tham chiếu catalogue của nhà hàng lúc đó.
Đây là historical reference cost; chưa bảo đảm actual COGS, FIFO hoặc lợi nhuận
ròng. Snapshot tồn tại không đồng nghĩa nguyên liệu đã được tiêu thụ.

## API đọc tổng của một dòng

```http
GET /api/v1/orders/{orderId}/items/{orderItemId}/cost-snapshot
Authorization: Bearer <accessToken>
Accept: application/json
```

Không có body/query. Tenant lấy từ JWT. Phải có đồng thời `ORDER_VIEW` và
`REPORT_DETAIL_VIEW`, cùng feature `ORDER_MANAGEMENT` và `DETAIL_REPORT`; các guard
account, restaurant và subscription hiện có tiếp tục áp dụng. Không cần feature
recipe/inventory hoặc quyền đọc công thức/kho cho API summary này. Quyền được kiểm
theo grant hiện hành, không hard-code role/package và không bổ sung grant mới.

Response `200`, `Cache-Control: no-store`, chỉ chứa tổng giá vốn của dòng; không có
recipe ID, ingredient, usage hoặc reservation detail. API recipe-snapshot giữ
nguyên quyền riêng `ORDER_VIEW` + `RECIPE_VIEW` và hai feature quản lý tương ứng.

Ví dụ COMPLETE:

```json
{
  "orderId": "11111111-1111-4111-8111-111111111111",
  "orderItemId": "22222222-2222-4222-8222-222222222222",
  "costSnapshotVersion": 1,
  "costBasis": "CATALOG_REFERENCE_AT_ACCEPTANCE",
  "costSource": "RECIPE",
  "costStatus": "COMPLETE",
  "reason": null,
  "orderQuantity": 2.000,
  "orderUnit": "portion",
  "currencyCode": "VND",
  "unitCost": 25000.00,
  "totalCost": 50000.00,
  "costCapturedAt": "2026-10-27T08:00:00Z"
}
```

Mọi field trên luôn xuất hiện; field chưa xác định trả JSON `null`.
`currencyCode` lấy từ currency bất biến của order, không lấy currency catalogue
hiện tại khi GET. `orderQuantity` và `orderUnit` lấy từ snapshot, hoặc từ dòng chưa
gửi đối với NOT_SUBMITTED.

| costStatus | version / basis | unitCost / totalCost | reason / thời điểm |
| --- | --- | --- | --- |
| COMPLETE | 1 / CATALOG_REFERENCE_AT_ACCEPTANCE | Có đủ số tiền không âm, gồm configured zero | reason=null; costCapturedAt có giá trị |
| INCOMPLETE | 1 / CATALOG_REFERENCE_AT_ACCEPTANCE | Cả hai null | MANUAL_COST_NOT_CONFIGURED hoặc INGREDIENT_COST_NOT_CONFIGURED; có costCapturedAt |
| UNAVAILABLE | 1 / CATALOG_REFERENCE_AT_ACCEPTANCE | Cả hai null | RECIPE_FEATURE_DISABLED; có costCapturedAt |
| LEGACY_UNAVAILABLE | null / null | Cả hai null | reason=null; costCapturedAt=null |
| NOT_SUBMITTED | null / null | Cả hai null | reason=null; costCapturedAt=null |

`costSource` là MANUAL/RECIPE theo quyết định đã lưu. Legacy giữ source thực sự đã
lưu nếu có; pre-V37 có thể null. NOT_SUBMITTED trả source null. Không suy source
từ toggle hiện tại. `snapshotStatus=CAPTURED` của recipe không đồng nghĩa giá đầy
đủ; dùng `costStatus` để xác định completeness.

## Khi nào chốt và cách tính

Fresh create SUBMIT, confirm DRAFT/OPEN, append SUBMIT và QR approval chốt giá
trong cùng transaction acceptance. Draft/cart/QR PENDING, review/reject và GET
không chốt. Replay giữ snapshot, không đọc giá live hay tạo audit trùng. Gọi thêm
tạo dòng mới theo giá lúc acceptance mới; dòng cũ giữ giá cũ dù cùng menu item.

| Nguồn đã chọn | Kết quả |
| --- | --- |
| MANUAL / RECIPE_DISABLED, menu costConfigured=true | COMPLETE; unitCost là manual costPrice, totalCost=round(unitCost × orderQuantity, 2) |
| MANUAL / RECIPE_DISABLED, costConfigured=false | INCOMPLETE / MANUAL_COST_NOT_CONFIGURED; không tạo usages hoặc ledger giả |
| RECIPE / CAPTURED, mọi ingredient configured | COMPLETE; tổng mọi usage, gồm tracked và untracked |
| RECIPE / CAPTURED, ít nhất một ingredient chưa configured | INCOMPLETE / INGREDIENT_COST_NOT_CONFIGURED; tổng null, usage đã biết giá vẫn được lưu riêng |
| RECIPE / FEATURE_DISABLED | UNAVAILABLE / RECIPE_FEATURE_DISABLED; không đọc recipe vượt gate, không fallback manual |

Món bật recipe, còn feature recipe nhưng thiếu active recipe vẫn bị từ chối với
`ACTIVE_RECIPE_REQUIRED`. Thiếu giá thông thường không làm acceptance thất bại và
không chặn consume, serve, payment hoặc complete hợp lệ.

Tính bằng BigDecimal; tiền làm tròn HALF_UP scale 2:

```text
usage.lineCost = round(requiredQuantity × ingredient.unitCost, 2)
recipe.totalCost = SUM(usage.lineCost đã làm tròn)
recipe.unitCost = round(recipe.totalCost / orderQuantity, 2)
```

`requiredQuantity` đã gồm waste/yield/order quantity và CEILING scale 3. Không áp
waste hoặc yield lần nữa. Với recipe, totalCost là authoritative; không nhân
unitCost trình bày đã làm tròn ngược với quantity. Ví dụ total=0.01, quantity=3
có unitCost=0.00 và vẫn totalCost=0.01. Live recipe cost-estimate tính theo batch
rồi chia yield nên có thể khác snapshot tính trên lượng kho đã CEILING.

Giá catalogue được hiểu theo currency hiện tại của nhà hàng tại acceptance. Đơn
vị giá phải đúng unit snapshot. Không tự quy đổi đơn vị hoặc currency, không cộng
tiền khác currency. Giá nguồn NUMERIC(14,2); header/usage line totals NUMERIC(38,2).
Overflow hoặc dữ liệu hỏng rollback toàn bộ command, không truncate/clamp.

## Ledger và lịch sử

Chỉ tracked usage được reserve/consume mới tạo RECIPE_USE. Từ V38, `unitCost` của
ledger lấy từ chính immutable usage tương ứng: known giữ giá đã chốt, configured
zero là 0.00, unknown hoặc legacy là null. Header INCOMPLETE vẫn có thể có tracked
usage đã biết giá và ledger ghi đúng giá riêng đó. Không đọc giá ingredient lúc
consume; không sửa ledger cũ. Legacy RESERVED vẫn consume/release được.

Cancel PENDING chỉ release reservation; không tăng tồn vật lý. Snapshot tiền vẫn
giữ khi hủy. Payment/complete/retry không ghi chi phí hoặc trừ kho lần hai. Ledger
tracked không đại diện toàn bộ giá vốn món vì còn MANUAL và untracked usages.

## Lỗi

| HTTP | Code và ý nghĩa |
| --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY, gồm UUID không hợp lệ |
| 401 | UNAUTHORIZED / guard xác thực hiện tại |
| 403 | FORBIDDEN / FEATURE_NOT_ENTITLED / tenant-account-subscription guard |
| 404 | ORDER_NOT_FOUND / ORDER_ITEM_NOT_FOUND; tenant khác hoặc item sai order |
| 409 | ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT nếu submitted line thiếu header hoặc lệch snapshot; ORDER_COST_SNAPSHOT_CONFLICT nếu metadata tiền lưu bị hỏng |

Lỗi acceptance liên quan giá trả 409: `INVALID_ORDER_COST_SNAPSHOT_DATA`,
`ORDER_COST_UNIT_MISMATCH`, `ORDER_COST_CURRENCY_MISMATCH`,
`ORDER_COST_AMOUNT_OUT_OF_RANGE`. Đây là dữ liệu sai, khác INCOMPLETE do chưa cấu
hình giá. Giữ các conflict recipe/inventory/idempotency hiện có. UI xử lý code,
không parse message SQL.

GET không ghi DB/audit, không tăng order version và không tạo dữ liệu lịch sử còn
thiếu. Không bổ sung cost vào ordinary OrderResponse, KDS, public QR/menu hoặc
ORDER_CHANGED. Không thêm realtime event hay API chỉnh snapshot.

## Kịch bản kiểm tra API

1. Tạo menu recipeEnabled=false, costPrice=12000, costConfigured=true. SUBMIT 2
   phần; GET cost-snapshot trả MANUAL/COMPLETE, unitCost=12000, totalCost=24000.
2. Đổi manual price thành 13000; GET dòng cũ vẫn 12000. Append 1 phần SUBMIT
   tạo dòng mới có 13000. Replay append cùng key/payload không thêm snapshot.
3. Đặt manual costConfigured=false; dòng mới INCOMPLETE, hai số tiền null.
   Xác nhận configured zero; dòng mới COMPLETE với hai số tiền 0.
4. Bật recipe hợp lệ, cấu hình ingredient tracked và untracked; chốt và kiểm
   tổng cả hai. Bỏ cấu hình một ingredient; dòng mới INCOMPLETE, không có tổng giả.
5. Đổi giá/nhập kho repricing sau acceptance, rồi start cooking; ledger của usage
   đã biết giá phải giữ giá lúc acceptance. Unknown/legacy tiếp tục unitCost=null.
6. DRAFT hoặc QR PENDING không có monetary snapshot; confirm/approve dùng giá tại
   thời điểm acceptance đó. GET legacy trả LEGACY_UNAVAILABLE, không định giá lại.
7. Token chỉ có ORDER_VIEW bị từ chối. Token có đủ quyền/feature summary đọc được
   MANUAL kể cả không có RECIPE_MANAGEMENT; recipe-snapshot vẫn theo guard riêng.
8. Kiểm tenant khác/item sai order trả 404; GET không đổi version/audit/count row.

[Thiết kế và migration](order-reference-cost-snapshot-implementation.md) ·
[Đồng bộ FE](order-reference-cost-snapshot-fe-sync.md) ·
[OpenAPI ORDER](openapi/order-api.yaml)
