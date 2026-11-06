# Snapshot công thức khi gửi món — Giai đoạn 4

> V38: Acceptance freezes monetary reference cost in the existing header/usages, alongside the V37 source decision. This recipe API keeps its existing permissions and response without cost fields. Read the separate protected [cost summary API](order-reference-cost-snapshot-api.md); CAPTURED alone does not mean complete pricing. Old monetary metadata remains null and is never repriced.

Snapshot này chốt **công thức và định lượng**, không phải giữ chỗ, trừ kho hoặc
giá vốn thực tế. ORDER sở hữu snapshot; dữ liệu MENU được đọc qua internal port
`ActiveOrderRecipeQuery`, không qua controller/API quản trị công thức.
Từ V33, [allocation kho riêng](order-inventory-allocation-api.md) dùng tracked
usages trong snapshot để reserve/consume/release; không sửa trạng thái snapshot.

## Khi nào tạo snapshot?

| Thao tác | Hành vi |
| --- | --- |
| Create DRAFT, append DRAFT, sửa quantity DRAFT | Không tạo snapshot |
| Create SUBMIT hoặc confirm OPEN | Chốt các dòng PENDING chưa gửi, bỏ dòng đã hủy |
| Append SUBMIT vào OPEN | Confirm và chốt toàn bộ các dòng được gửi lần đầu |
| Append SUBMIT vào order đang phục vụ | Chỉ chốt các dòng mới thêm |
| Khách gửi/review/reject/cancel QR request | Không tạo snapshot |
| Nhân viên approve QR tạo order mới | Chốt tại confirm, theo recipe lúc approve |
| Nhân viên approve QR gọi thêm | Chỉ chốt các dòng được thêm vào order |

Một lần gọi thêm tạo dòng mới, không gộp theo menu item ID: cùng món có thể dùng
hai recipe version khác nhau. Không cần quyền RECIPE_VIEW để gửi món; giữ nguyên
permission ORDER/QR. Nếu áp dụng inventory ở V33, phải có kho default và đủ
available; trường hợp SKIPPED không yêu cầu kho/tồn.

Capture chạy đồng bộ trong transaction gửi món, dưới restaurant mutation lock dùng
chung với MENU/recipe. Snapshot, order/session mới, history, audit, append ledger và
QR approval cùng commit/rollback. Không ghi snapshot trong prepare/read/review hay
AFTER_COMMIT. Create/append/QR approval replay không chốt lại hoặc tạo audit trùng.

## Các trạng thái

| snapshotStatus | Ý nghĩa |
| --- | --- |
| CAPTURED | Có RECIPE_MANAGEMENT và active recipe hợp lệ khi chấp nhận món |
| RECIPE_DISABLED | Món không áp dụng recipe; source MANUAL, không có usages |
| NO_RECIPE | Quyết định lịch sử; fresh acceptance bật recipe nhưng thiếu active recipe trả ACTIVE_RECIPE_REQUIRED |
| FEATURE_DISABLED | Lúc gửi món không có RECIPE_MANAGEMENT; bán hàng vẫn được phép |
| LEGACY_UNAVAILABLE | Dòng đã gửi từ trước V32; không biết công thức thật lúc gửi |
| NOT_SUBMITTED | Response-only cho dòng chưa gửi; không tạo row cho DRAFT |

RECIPE_DISABLED/NO_RECIPE/FEATURE_DISABLED là quyết định bền vững, có header dù không có usage.
Thêm recipe hoặc đổi package sau này không áp ngược vào dòng cũ. Dòng mới đánh giá
entitlement/recipe tại lần gửi mới. Không nhầm các trạng thái trên với trạng thái
bếp, order hoặc reservation.

## Định lượng và tính bất biến

```text
requiredQuantity = recipeQuantity × (1 + wastePercent / 100)
                   × orderQuantity / yieldQuantity
```

Ví dụ công thức cho 10 phần, dùng 1000 g và hao hụt 5%, gọi 3 phần: 315.000 g.
Giữ BigDecimal và các input gốc, không làm tròn trung gian; chỉ divide cuối cùng
với scale 3, CEILING. Lượng 0.0001 thành 0.001, không thành 0. Giới hạn usage
99,999,999,999.999 theo precision inventory hiện tại; vượt ngưỡng trả business 409.
Hao hụt 5% là nhân 1.05, không chia 0.95. Không đổi cách làm tròn tiền của API cost.

Yield unit phải trùng order item unit; ingredient unit trong recipe phải trùng
baseUnit hiện hành tại lúc capture, không tự quy đổi kg/g, lít/ml. Active recipe
thiếu nguyên liệu hoặc nguyên liệu không hợp lệ là lỗi, không fallback NO_RECIPE.

Tên/SKU/đơn vị/quantity/waste lấy từ đúng recipe version, trackInventory chốt từ
ingredient hiện tại. Giữ cả nguyên liệu trackInventory=false. Từ V38, persistence
chốt giá tham chiếu cho mọi usage; response recipe này không trả các số tiền đó.
Không ghi actual COGS hoặc warehouse vào recipe snapshot.

Sau khi chốt, đổi recipe, tên/SKU/giá/tracking, deactivate/soft-delete ingredient,
cancel order/line hoặc COOKING/READY/SERVED đều không sửa snapshot. Không mở quyền
sửa quantity sau khi gửi. Nếu phát hiện line bị lệch quantity/unit/item so với
quyết định đã lưu thì trả conflict, không ghi đè lịch sử.

## API quản trị đọc snapshot

```http
GET /api/v1/orders/{orderId}/items/{orderItemId}/recipe-snapshot
Authorization: Bearer <accessToken>
Accept: application/json
```

Không có body/query. Yêu cầu ORDER_VIEW **và** RECIPE_VIEW, feature ORDER_MANAGEMENT
**và** RECIPE_MANAGEMENT, cùng các guard tenant/account/restaurant/subscription
hiện có. Đây không phải API public QR. Header trả về: `Cache-Control: no-store`.

200 CAPTURED:

```json
{
  "orderId": "11111111-1111-4111-8111-111111111111",
  "orderItemId": "22222222-2222-4222-8222-222222222222",
  "snapshotStatus": "CAPTURED",
  "recipeId": "33333333-3333-4333-8333-333333333333",
  "recipeVersion": 2,
  "yieldQuantity": 10.000,
  "yieldUnit": "phần",
  "orderQuantity": 3.000,
  "orderUnit": "phần",
  "capturedAt": "2026-10-20T08:00:00Z",
  "ingredients": [{
    "ingredientId": "44444444-4444-4444-8444-444444444444",
    "sku": "BEEF",
    "name": "Thịt bò",
    "unit": "g",
    "recipeQuantity": 1000.000,
    "wastePercent": 5.00,
    "trackInventory": true,
    "requiredQuantity": 315.000
  }]
}
```

`recipeVersion` là business version, không phải lock_version. Với RECIPE_DISABLED,
NO_RECIPE, FEATURE_DISABLED hoặc LEGACY_UNAVAILABLE, recipe fields null và ingredients=[].
capturedAt là lúc quyết định với NO_RECIPE/FEATURE_DISABLED; null với legacy và
NOT_SUBMITTED. Legacy orderQuantity/unit là dữ liệu dòng tại migration, không phải
bằng chứng công thức từng được chốt. NOT_SUBMITTED dùng quantity/unit của DRAFT.

GET không đọc recipe/ingredient live, không làm tăng version, cập nhật timestamp,
ghi audit, sửa tồn hay lưu snapshot. Quyền đọc snapshot được kiểm tra theo feature
hiện tại; downgrade không xóa dữ liệu cũ nhưng có thể không còn quyền đọc endpoint.

Lỗi:

| HTTP | Code |
| --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY |
| 401 | UNAUTHORIZED hoặc guard xác thực hiện tại |
| 403 | FORBIDDEN / FEATURE_NOT_ENTITLED / guard tenant-account-subscription hiện tại |
| 404 | ORDER_NOT_FOUND / ORDER_ITEM_NOT_FOUND (tenant khác hoặc item sai order) |
| 409 | ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT |

Lỗi lúc gửi/approve còn có: INVALID_RECIPE_SNAPSHOT_DATA,
RECIPE_YIELD_UNIT_MISMATCH, RECIPE_INGREDIENT_UNIT_MISMATCH,
RECIPE_INGREDIENT_UNAVAILABLE, RECIPE_USAGE_QUANTITY_OUT_OF_RANGE. Các lỗi này
rollback toàn bộ command. Constraint/concurrency không xác định vẫn dùng conflict
convention của ORDER/QR hiện có; unique snapshot được cả ORDER và QR staff dịch
thành ORDER_ITEM_RECIPE_SNAPSHOT_CONFLICT.

Không nhúng recipe/usages/cost vào order thường, KDS hoặc public QR response.

## Postman: kịch bản kiểm tra

1. Dùng owner có RECIPE_MANAGEMENT tạo menu item baseUnit="phần", ingredient
   baseUnit="g" và recipe yieldQuantity=10, quantity=1000, wastePercent=5 theo
   [Recipe API](recipe-api.md). Nếu có INVENTORY_MANAGEMENT và tracked usage,
   tạo kho default và nhập đủ tồn theo [allocation API](order-inventory-allocation-api.md).
2. POST `/api/v1/orders`, header Idempotency-Key cố định cho lần gửi, body:

```json
{
  "serviceType": "TAKEAWAY",
  "sourceChannel": "CASHIER",
  "submissionMode": "SUBMIT",
  "items": [{"itemId": "<menuItemId>", "quantity": 3}]
}
```

3. Lấy orderId và items[0].id từ response. GET snapshot bằng token có hai quyền
   đọc nêu trên: CAPTURED và requiredQuantity=315.000.
4. Tạo recipe version mới đổi quantity thành 2000. GET snapshot cũ vẫn 315.000.
5. Gọi thêm 2 phần bằng API append SUBMIT, expectedVersion order và Idempotency-Key
   mới. GET snapshot dòng mới phải version mới và requiredQuantity=420.000.
6. Gửi lại create/append với đúng key/payload ban đầu: không thêm snapshot/usage.
7. Tạo DRAFT rồi GET snapshot: NOT_SUBMITTED. Đổi recipe trước khi confirm; snapshot
   phải dùng recipe tại confirm, không phải lúc tạo DRAFT.
8. Thử token chỉ có ORDER_VIEW: GET snapshot bị từ chối; token waiter không có
   RECIPE_VIEW nhưng đủ quyền order vẫn gửi món thành công.
9. QR submit/review chưa có snapshot. Approve mới tạo; approve gọi thêm chỉ thêm
   snapshot cho dòng mới. Recipe đổi giữa review/approve dùng version tại approve
   nếu review/quote vẫn hợp lệ theo các guard QR hiện hành.

## Persistence, migration và audit

V32 tạo order_item_recipe_snapshots + order_item_recipe_usages, tenant composite
FK, unique một header/line và một ingredient/header, indexes theo tenant/line,
snapshot và ingredient. Header/usage không UPDATE/DELETE; usage chỉ được thêm trong
transaction tạo header CAPTURED. Deferred constraint yêu cầu CAPTURED có usage khi
commit. Không CASCADE xóa lịch sử, không endpoint sửa snapshot.

V32 chỉ đánh dấu các line có sent_to_kitchen_at là LEGACY_UNAVAILABLE; không lấy
recipe live, không tạo usage giả, không đổi order/item/version/tồn/audit cũ. DRAFT
và dòng hủy trước khi gửi không có marker và vẫn capture khi gửi mới hợp lệ.

Audit ORDER_ITEM_RECIPE_SNAPSHOTTED lưu summary orderId/orderItemId/status/recipe ID,
business version và source decision. V38 bổ sung monetary version/status/reason/
unit/total trong cùng audit cho mỗi fresh decision, không thêm audit action thứ
hai. Ghi qua batch AuditService trong transaction; không chép chi tiết ingredient,
không audit cho legacy, không tạo audit thành công khi rollback hoặc replay.

## Điểm tích hợp và giới hạn

- OrderConfirmationOperation: các dòng PENDING chưa gửi, gồm create SUBMIT/confirm/
  append vào OPEN/QR create; không capture trùng ở create handler.
- AddOrderItemsPersistHandler: chỉ added cho SUBMIT vào order đã gửi.
- OrderQrApprovalOperation: chỉ added ở nhánh QR append.
- MENU adapter đọc active recipe, recipe_items và ingredients bằng ba batch query;
  ORDER kiểm tra quyết định đã có bằng một batch query, không N+1.
- API recipe cost-estimate vẫn tính bằng giá ingredient hiện tại, khác snapshot
  định lượng bất biến và khác giá vốn lịch sử.
- V33 dùng snapshot để giữ chỗ/tiêu thụ/giải phóng qua allocation riêng. V38 thêm
  reference cost trong persistence và [API summary riêng](order-reference-cost-snapshot-api.md);
  snapshot không lưu warehouse; không unit conversion, FIFO hoặc tự ghi
  MENU_ITEM.costPrice. CAPTURED có từ trước V33 vẫn có allocation SKIPPED với
  skipReason=LEGACY_UNAVAILABLE.

OpenAPI: [order-api.yaml](openapi/order-api.yaml).
