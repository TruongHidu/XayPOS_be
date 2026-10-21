# Recipe API

Module công thức nằm trong business module `menu`. Công thức là aggregate riêng:
`MENU_ITEM -> recipe version -> recipe items -> INGREDIENT`. Không lưu trong
`items.metadata`, không phải một `ItemType`, và không xuất hiện ở public/QR menu.

## Điều kiện truy cập

Mọi request dùng:

```http
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json   # với POST
```

Tenant chỉ lấy từ JWT. Subscription hiệu lực phải có `RECIPE_MANAGEMENT`; chỉ có
`INVENTORY_MANAGEMENT` không đủ. Permission theo từng API là `RECIPE_VIEW`,
`RECIPE_CREATE`, `RECIPE_UPDATE`, `RECIPE_DELETE`. V29 mặc định grant bốn quyền
cho system role OWNER và SUPER_ADMIN; staff chỉ dùng được khi owner cấp quyền và
JWT mới đã nhận authority.

Base URL: `/api/v1/menu/items/{menuItemId}/recipes`.

## Hai loại version

- `recipeVersion`: phiên bản nghiệp vụ bất biến `1, 2, 3...`.
- `version`: optimistic-lock version. FE gửi giá trị này trong `expectedVersion`.

Không gửi `recipeVersion` làm `expectedVersion`.

## API

| Method/path | Permission | Thành công |
|---|---|---|
| `GET .../active` | RECIPE_VIEW | 200 Recipe |
| `GET ...?page=0&size=20&sortBy=recipeVersion&direction=desc` | RECIPE_VIEW | 200 page summary |
| `GET .../{recipeId}` | RECIPE_VIEW | 200 Recipe |
| `GET .../{recipeId}/cost-estimate` | RECIPE_VIEW | 200 RecipeCostEstimate |
| `POST ...` | RECIPE_CREATE | 201 Recipe |
| `POST .../{activeRecipeId}/versions` | RECIPE_UPDATE | 201 RR+++ecipe mới
 |
| `DELETE .../{recipeId}?expectedVersion=0` | RECIPE_DELETE | 204 |

### Tạo công thức đầu tiên

Chỉ dùng khi món chưa có công thức active. `yieldUnit` được server chụp từ
`menuItem.baseUnit`; tên, SKU và đơn vị nguyên liệu cũng được snapshot.

```http
POST /api/v1/menu/items/11111111-1111-4111-8111-111111111111/recipes
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "yieldQuantity": 10.000,
  "ingredients": [
    {
      "ingredientId": "22222222-2222-4222-8222-222222222222",
      "quantity": 1200.000,
      "wastePercent": 5.00
    },
    {
      "ingredientId": "33333333-3333-4333-8333-333333333333",
      "quantity": 1800.000
    }
  ]
}
```

```json
{
  "id": "44444444-4444-4444-8444-444444444444",
  "menuItemId": "11111111-1111-4111-8111-111111111111",
  "recipeVersion": 1,
  "yieldQuantity": 10.000,
  "yieldUnit": "tô",
  "active": true,
  "ingredients": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "ingredientId": "22222222-2222-4222-8222-222222222222",
      "sku": "NL-THIT-BO",
      "name": "Thịt bò",
      "unit": "g",
      "quantity": 1200.000,
      "wastePercent": 5.00
    }
  ],
  "version": 0,
  "createdAt": "2026-10-16T08:00:00Z",
  "updatedAt": "2026-10-16T08:00:00Z"
}
```

Món có thể inactive hoặc OUT_OF_STOCK. Nguyên liệu phải cùng tenant, chưa xóa và
active; `trackInventory=false` vẫn hợp lệ. Tối đa 100 dòng, không được trùng ID.

### Tạo phiên bản mới

Không sửa trực tiếp version active. Server lock tenant, kiểm tra version, so sánh
aggregate đã chuẩn hóa, deactivate version cũ rồi flush trước khi insert version
mới trong cùng transaction.

```http
POST /api/v1/menu/items/{menuItemId}/recipes/{activeRecipeId}/versions
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "expectedVersion": 0,
  "yieldQuantity": 10.000,
  "ingredients": [
    {
      "ingredientId": "22222222-2222-4222-8222-222222222222",
      "quantity": 1300.000,
      "wastePercent": 5.00
    }
  ]
}
```

Đổi thứ tự dòng không được coi là thay đổi. Nếu cần dùng lại nội dung cũ, đọc
version lịch sử rồi tạo một version mới; không kích hoạt trực tiếp lịch sử.

### Đọc active, lịch sử và chi tiết

```http
GET /api/v1/menu/items/{menuItemId}/recipes/active
GET /api/v1/menu/items/{menuItemId}/recipes?page=0&size=20&sortBy=recipeVersion&direction=desc
GET /api/v1/menu/items/{menuItemId}/recipes/{recipeId}
Authorization: Bearer <accessToken>
```

`sortBy` chỉ nhận `recipeVersion`, `createdAt`, `updatedAt`; `direction` là
`asc|desc`. History trả `ingredientCount` bằng batch/group query, không N+1.
Detail luôn đọc snapshot, nên đổi tên/SKU/đơn vị nguyên liệu sau đó không sửa lịch sử.

### Ước tính giá vốn một phiên bản công thức

```http
GET /api/v1/menu/items/{menuItemId}/recipes/{recipeId}/cost-estimate
Authorization: Bearer <accessToken>
Accept: application/json
```

Không có body, query chọn nguồn giá, `expectedVersion`, `Idempotency-Key`,
`restaurantId` hoặc `warehouseId`. Yêu cầu `RECIPE_VIEW` và `RECIPE_MANAGEMENT`;
không yêu cầu quyền/feature inventory, kho mặc định hay tồn kho. Tenant lấy từ JWT.
Menu item inactive/OUT_OF_STOCK vẫn theo contract đọc recipe; menu item đã xóa hoặc
recipe thuộc tenant/menu item khác trả 404. Response thành công có
`Cache-Control: no-store`.

```json
{
  "menuItemId": "11111111-1111-4111-8111-111111111111",
  "recipeId": "44444444-4444-4444-8444-444444444444",
  "recipeVersion": 2,
  "recipeActive": true,
  "yieldQuantity": 10.000,
  "yieldUnit": "tô",
  "currencyCode": "VND",
  "costSource": "INGREDIENT_COST_PRICE",
  "totalBatchCost": 369000.00,
  "costPerYieldUnit": 36900.00,
  "ingredients": [
    {
      "ingredientId": "22222222-2222-4222-8222-222222222222",
      "sku": "NL-THIT-BO",
      "name": "Thịt bò",
      "unit": "g",
      "quantity": 1200.000,
      "wastePercent": 5.00,
      "grossQuantity": 1260.000,
      "unitCost": 250.00,
      "lineCost": 315000.00
    },
    {
      "ingredientId": "33333333-3333-4333-8333-333333333333",
      "sku": "NL-BANH-PHO",
      "name": "Bánh phở",
      "unit": "g",
      "quantity": 1800.000,
      "wastePercent": 0.00,
      "grossQuantity": 1800.000,
      "unitCost": 30.00,
      "lineCost": 54000.00
    }
  ],
  "warnings": [],
  "estimatedAt": "2026-10-19T08:00:00Z"
}
```

`recipeVersion` là phiên bản nghiệp vụ được chọn, không phải optimistic-lock
`version`. `currencyCode` lấy từ nhà hàng; `estimatedAt` lấy từ Clock. Giá vốn tham
chiếu là `ingredient.costPrice` **trên một baseUnit**, ví dụ 250 VND/g; không tự
hiểu là giá/kg và không quy đổi đơn vị.

Cách tính cho toàn bộ mẻ recipe:

```text
grossQuantity = quantity × (1 + wastePercent / 100)
lineCost = grossQuantity × unitCost
totalBatchCost = tổng lineCost đã làm tròn
costPerYieldUnit = totalBatchCost / yieldQuantity
```

Tính bằng BigDecimal. `grossQuantity` giữ chính xác, không CEILING về 3 chữ số thập
phân: `0.001 × (1 + 0.01/100) = 0.0010001`. `lineCost` làm tròn 2 chữ số bằng
HALF_UP; tổng mẻ cộng các line cost đã làm tròn; giá mỗi đơn vị sản lượng tiếp tục
HALF_UP 2 chữ số. Không làm tròn gross quantity trước khi nhân đơn giá. Waste là
phần cộng thêm, không phải phép chia cho `1 - wastePercent/100`.

API hỗ trợ recipe active và inactive. Tên, SKU, đơn vị, định lượng, waste và yield
lấy từ snapshot của đúng recipe được chọn; unit cost lấy từ giá tham chiếu hiện
tại. Ước tính phiên bản cũ bằng giá hiện tại **không phải giá vốn lịch sử** lúc tạo
phiên bản đó. Đổi `costPrice` làm lần GET sau thay đổi estimate, không sửa recipe.
Nguyên liệu inactive/soft-deleted còn nguồn giá hợp lệ vẫn được tính; cả
`trackInventory=true` lẫn `false` đều phát sinh chi phí. Giá được lấy theo batch,
không query riêng từng nguyên liệu.

Nếu đơn vị snapshot khác `baseUnit` hiện tại, API từ chối toàn bộ estimate với
`409 INGREDIENT_COST_UNIT_MISMATCH`. Thiếu nguồn giá hoặc dữ liệu không hợp lệ
không bị bỏ qua hoặc tự coi là 0. `costPrice=0` hợp lệ và có warning theo nguyên
liệu, không trùng:

```json
{
  "code": "ZERO_INGREDIENT_REFERENCE_COST",
  "ingredientId": "22222222-2222-4222-8222-222222222222",
  "message": "Nguyên liệu đang có giá vốn tham chiếu bằng 0"
}
```

Nếu mọi giá đều bằng 0, vẫn trả 200, total/per-yield bằng 0 và warnings. Các lỗi
409 riêng của estimate: `INGREDIENT_COST_UNIT_MISMATCH`,
`INGREDIENT_COST_UNAVAILABLE`, `INVALID_INGREDIENT_REFERENCE_COST`,
`INVALID_RECIPE_COST_DATA`. Các lỗi 400/401/403/404 dùng envelope hiện tại.

#### Test Postman

1. Tạo hai nguyên liệu đơn vị `g`, giá tham chiếu 250 và 30; không cần kho/tồn.
2. Tạo recipe yield 10 với 1200 g/waste 5% và 1800 g/waste 0%.
3. GET cost-estimate bằng token có `RECIPE_VIEW` và `RECIPE_MANAGEMENT`.
   Kiểm tra tổng mẻ 369000.00, mỗi đơn vị sản lượng 36900.00 và header no-store.
4. Sửa giá nguyên liệu thứ nhất rồi GET lại: estimate đổi, snapshot/recipeVersion
   không đổi. Đặt giá 0 để kiểm tra warning nhưng vẫn nhận 200.
5. Deactivate recipe và GET lại: vẫn tính được đúng phiên bản. Đổi baseUnit của
   ingredient khi được phép rồi GET recipe lịch sử: trả unit-mismatch 409.
6. Dùng token thiếu permission/feature để kiểm tra 403; dùng recipe thuộc món
   khác/tenant khác để kiểm tra 404.

#### Đồng bộ Flutter/web

- Mở “Ước tính giá vốn” tại chi tiết một phiên bản recipe và gọi endpoint này.
- Hiển thị giá cả mẻ, giá mỗi `yieldUnit`, từng line và warning giá 0.
- Cho refresh sau khi sửa giá nguyên liệu; backend quyết định toàn bộ phép tính.
- Không gửi giá override, không tự lưu kết quả vào `MENU_ITEM.costPrice` hoặc giá bán.
- Gắn nhãn “ước tính theo giá tham chiếu hiện tại”; không hiển thị như lợi nhuận
  thực tế hay giá vốn lịch sử.

GET này chỉ đọc: không tăng entity version, đổi timestamp, ghi mutation audit,
lưu estimate, tạo reservation/chứng từ/ledger hay thay đổi tồn kho. Không có
migration mới. Nguồn giá hiện tại có provider riêng để có thể thay đổi sau này;
provider vẫn đọc `ingredient.costPrice`. INVENTORY nay cập nhật trường này theo
bình quân liên hoàn khi xác nhận RECEIPT (xem inventory-api.md), nên lần GET tiếp
theo tự phản ánh giá mới mà không sửa công thức. GET không tự tính/lưu bình quân
và chưa hỗ trợ FIFO hoặc định giá lịch sử.

### Deactivate

```http
DELETE /api/v1/menu/items/{menuItemId}/recipes/{recipeId}?expectedVersion=0
Authorization: Bearer <accessToken>
```

Không xóa dữ liệu. Retry đúng recipe đã inactive trả 204, không kiểm tra version
cũ, không audit lần hai và không tác động active version mới.

## Quy ước định lượng

- `yieldQuantity`, `quantity`: lớn hơn 0, tối đa 3 chữ số thập phân.
- `wastePercent`: 0..100, tối đa 2 chữ số thập phân; bỏ qua thì bằng 0.
- Không tự làm tròn và chưa có quy đổi đơn vị.
- Công thức cho kho sau này có thể dùng:
  `grossUsage = quantity * (1 + wastePercent/100) * orderedQuantity / yieldQuantity`.

## Guard lên menu và nguyên liệu

Trong khi có công thức active:

- Không đổi `menuItem.baseUnit`: `MENU_ITEM_UNIT_IN_USE`.
- Không xóa menu item: `MENU_ITEM_HAS_ACTIVE_RECIPE`.
- Không disable/xóa nguyên liệu được tham chiếu: `INGREDIENT_USED_BY_ACTIVE_RECIPE`.
- Không đổi `ingredient.baseUnit`: `INGREDIENT_UNIT_IN_USE`.

Tên, SKU, mô tả, giá và cờ theo dõi kho vẫn sửa được. Khi chỉ còn history inactive,
đổi đơn vị hoặc soft-delete được phép; snapshot lịch sử vẫn nguyên vẹn.

## Lỗi chính

- 400: `VALIDATION_ERROR`, `INVALID_REQUEST_BODY`.
- 403: `FORBIDDEN`, `SUBSCRIPTION_NOT_ACTIVE`, `FEATURE_NOT_ENTITLED`.
- 404: `MENU_ITEM_NOT_FOUND`, `INGREDIENT_NOT_FOUND`, `RECIPE_NOT_FOUND`,
  `ACTIVE_RECIPE_NOT_FOUND`.
- 409: `ACTIVE_RECIPE_ALREADY_EXISTS`, `RECIPE_NOT_ACTIVE`,
  `DUPLICATE_RECIPE_INGREDIENT`, `INGREDIENT_INACTIVE`,
  `SAME_RECIPE_VERSION_NOT_ALLOWED`, `CONCURRENT_RECIPE_UPDATE` và các guard trên.

Mutation thành công audit `RECIPE_CREATED`, `RECIPE_VERSION_CREATED` hoặc
`RECIPE_DEACTIVATED`. Request lỗi/rollback và deactivate retry không sinh audit giả.

## Giới hạn hiện tại

Recipe chưa có DRAFT/approval, hard delete, reservation/tự trừ kho, unit conversion,
tự cập nhật costPrice món bán, giá vốn lịch sử/FIFO,
combo, topping, nutrition/allergen hoặc public/QR recipe response. Estimate chỉ
dùng giá nguyên liệu tham chiếu hiện tại; module inventory có API riêng và tự
cập nhật giá nguyên liệu bình quân khi confirm RECEIPT, không cập nhật giá món.

V32 đã chốt recipe/định lượng vào từng order item tại SUBMIT/confirm/approve QR.
Không chốt ở DRAFT hoặc QR pending; gọi thêm có thể dùng recipe version khác.
Đây không phải snapshot giá vốn, giữ chỗ hoặc trừ kho. Xem
[Order recipe snapshot](order-recipe-snapshot-api.md); endpoint đọc riêng yêu cầu
ORDER_VIEW và RECIPE_VIEW, không mở dữ liệu recipe qua order thường/public QR.
