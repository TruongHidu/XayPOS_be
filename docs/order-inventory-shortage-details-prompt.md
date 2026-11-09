# Prompt: Báo chi tiết khi không đủ tồn kho lúc gọi món

Bạn đang làm việc trong repo Spring Boot `XayPOS_be` (Java 21, JPA, Flyway, package `com.possaas`). Hãy triển khai
tính năng **trả chi tiết thiếu tồn kho** khi giữ chỗ nguyên liệu cho order. Làm đúng kiến trúc hiện có và theo SOLID.
Trước khi code, hãy đọc kỹ các file nêu ở mục 1.

## 1. Hiện trạng (đọc trước)
- Port do module ORDER sở hữu: `modules/order/application/port/OrderInventoryAllocationCommand.java`
  (`reserve`, record `AcceptedItem`, `Usage`). Module INVENTORY implement port này (theo DIP).
- Điểm map dữ liệu ORDER → port: `modules/order/service/OrderInventoryAllocationOperation.accept(...)`. Đây là nơi
  duy nhất dựng `AcceptedItem`. Hàm này được gọi khi create/confirm (`OrderConfirmationOperation`), append
  (`chain/append/AddOrderItemsPersistHandler`) và QR approve (`OrderQrApprovalOperation`).
- Adapter: `modules/inventory/service/OrderInventoryAllocationAdapter.reserve(...)`. Adapter cộng `demand` theo
  `BalanceKey(warehouseId, ingredientId)` (nhu cầu nguyên liệu dùng chung đã được gộp), khóa balance, rồi gọi
  `demand.forEach((key, q) -> availability.requireAvailable(locked.get(key), q))`. Bước này chạy **trước mọi lần ghi DB**.
- `modules/inventory/service/InventoryAvailabilityPolicy.requireAvailable` ném
  `ConflictException("INSUFFICIENT_AVAILABLE_STOCK", ...)`. Nếu balance là `null` thì coi available = 0.
- `Usage.quantity` là **tổng** lượng nguyên liệu cho cả dòng order, không phải cho 1 phần. Lượng này được tính bởi
  `modules/order/service/RecipeUsageQuantityCalculator` (làm tròn `CEILING`, scale 3). Với món bán trực tiếp
  (`directStock=true`), `usages` có đúng 1 phần tử, `quantity == orderQuantity` và `unit == orderUnit`.
- `OrderItem.quantity` là decimal scale 3 (`@DecimalMin("0.001")`).
- Khuôn lỗi `common/exception/GlobalExceptionHandler.ErrorResponse(success, code, message, fieldErrors, timestamp)`
  được tạo bằng constructor 5 tham số ở khoảng 25 chỗ. `BusinessException(status, code, message)`.
- Handler render lỗi cho các endpoint liên quan, mỗi handler có hàm `error(...)` riêng:
  - `modules/order/controller/OrderConflictHandler` (staff order)
  - `modules/qrorder/controller/QrStaffErrorHandler` (staff duyệt QR)
  - `modules/qrorder/controller/PublicQrOrderErrorHandler` (**khách**, tuyệt đối không được lộ chi tiết tồn kho)
  - `common/exception/GlobalExceptionHandler`
- OpenAPI: schema `Error` trong `docs/openapi/order-api.yaml` có `additionalProperties: false`. Test
  `QrOrderOpenApiTest` khẳng định schema `Error` có **đúng** 5 thuộc tính, nên không được thêm field vào `Error`.
- Jackson cấu hình `fail-on-unknown-properties: true`.

## 2. Yêu cầu nghiệp vụ
Khi một lệnh giữ tồn bị thiếu ở ít nhất một nguyên liệu, trả về:
```json
{
  "success": false,
  "code": "INSUFFICIENT_AVAILABLE_STOCK",
  "message": "Insufficient available inventory",
  "fieldErrors": {},
  "timestamp": "...",
  "details": {
    "lines": [
      {
        "orderItemId": "uuid", "menuItemId": "uuid", "itemName": "Phở bò",
        "requestedQuantity": 3, "maxAvailableQuantity": 1, "unit": "tô",
        "sharesIngredientWithOtherLines": true,
        "shortIngredients": [
          { "ingredientId": "uuid", "name": "Thịt bò", "unit": "g",
            "availableQuantity": 500.000, "requiredQuantity": 630.000 }
        ]
      }
    ]
  }
}
```
Quy tắc:
1. `code`, HTTP 409, `message` và rollback toàn bộ lệnh **giữ nguyên như cũ**. FE cũ chỉ đọc `code` vẫn chạy đúng.
2. Một ingredient bị coi là "thiếu" khi tổng `demand` của nó trong lệnh lớn hơn `available` (balance null tính là 0).
3. `lines` chỉ gồm các dòng **mới, có giữ tồn** (decision `null`, tức không SKIPPED) và dùng ít nhất một ingredient
   thiếu. Thứ tự theo đúng thứ tự `items` đầu vào.
4. `maxAvailableQuantity` của một dòng, **với giả định các dòng khác trong cùng lệnh giữ nguyên**:
   - Với mỗi ingredient `k` của dòng: `remaining_k = available_k − (demand_k − own_k)`, chặn dưới tại 0.
     Ở đây `own_k = usage.quantity` của dòng này.
   - `candidate_k = floor(remaining_k × orderQuantity / own_k)`, theo bước số nguyên nếu
     `orderQuantity.stripTrailingZeros().scale() <= 0`, ngược lại theo bước 0.001.
   - **Kiểm tra bảo thủ** (vì calculator làm tròn CEILING): trong khi `candidate > 0` và
     `ceil3(candidate × own_k / orderQuantity) > remaining_k` thì giảm candidate một bước.
   - `maxAvailableQuantity = min(requestedQuantity, min_k candidate_k)`. Hết hoàn toàn thì trả `0`.
5. `sharesIngredientWithOtherLines = true` nếu có ingredient thiếu của dòng mà `demand_k − own_k > 0`.
6. `shortIngredients` của một dòng: các ingredient thiếu mà dòng đó dùng. `availableQuantity` là available hiện tại,
   `requiredQuantity` là tổng demand của ingredient đó trong lệnh. Số giữ scale 3.
7. Chi tiết **chỉ** trả cho endpoint staff (order, staff duyệt QR). Không persist, không ghi audit, không trả cho khách
   QR. `PublicQrOrderErrorHandler` giữ nguyên.
8. Các luồng khác cũng dùng mã `INSUFFICIENT_AVAILABLE_STOCK` (phiếu kho `StockPostingService`, `requireStockPosting`,
   stock audit) **không đổi**.

## 3. Thiết kế (bắt buộc tuân theo)
**SRP**: tính toán (pure), quyết định ném lỗi (adapter) và render HTTP (handler) là ba trách nhiệm tách riêng.
**OCP**: handler render `details` một cách tổng quát qua `BusinessException`, không `instanceof` theo từng loại lỗi.
**LSP**: lỗi mới là `ConflictException`, nên mọi chỗ đang bắt `BusinessException`/`ConflictException` vẫn đúng.
**ISP/DIP**: kiểu lỗi và payload nằm ở **port của ORDER**. INVENTORY phụ thuộc vào port, ORDER không phụ thuộc
vào INVENTORY.

### 3.1 `common/exception`
- `BusinessException`: thêm `public Object details() { return null; }`, có thể override, có Javadoc
  "payload có cấu trúc, an toàn để trả cho caller đã xác thực; mặc định null".
- `GlobalExceptionHandler.ErrorResponse`: thêm component cuối `@JsonInclude(JsonInclude.Include.NON_NULL) Object details`,
  kèm **constructor phụ 5 tham số** truyền `null`, để không phải sửa ~25 call site. JSON của mọi lỗi hiện tại
  phải không đổi (không xuất hiện key `details`).
- `GlobalExceptionHandler.business(...)`: truyền `ex.details()`.

### 3.2 Port ORDER: `modules/order/application/port/`
- `OrderInventoryAllocationCommand.AcceptedItem`: thêm `UUID menuItemId` và `String itemName`. Cập nhật constructor
  phụ 6 tham số (đang được dùng trong test) để truyền `null` cho hai field mới. Không đổi ngữ nghĩa các field cũ.
- File mới `InsufficientStockException extends ConflictException`: code cố định `INSUFFICIENT_AVAILABLE_STOCK`,
  message `Insufficient available inventory`. Nhận `List<ShortageLine>` (lưu bằng `List.copyOf`, ném lỗi nếu rỗng).
  Override `details()` trả `new Details(lines)`.
- Records (nested trong exception hoặc file `StockShortage.java` cùng package):
  `Details(List<ShortageLine> lines)`,
  `ShortageLine(UUID orderItemId, UUID menuItemId, String itemName, BigDecimal requestedQuantity, BigDecimal maxAvailableQuantity, String unit, boolean sharesIngredientWithOtherLines, List<ShortIngredient> shortIngredients)`,
  `ShortIngredient(UUID ingredientId, String name, String unit, BigDecimal availableQuantity, BigDecimal requiredQuantity)`.
  Dùng `List.copyOf` trong compact constructor.
- Javadoc của `reserve(...)`: ghi rõ có thể ném `InsufficientStockException`.

### 3.3 ORDER service
- `OrderInventoryAllocationOperation.accept(...)`: truyền `header.getMenuItemIdSnapshot()` và `line.getItemName()` vào
  `AcceptedItem`. Không thêm logic nào khác.

### 3.4 INVENTORY
- File mới `modules/inventory/service/InventoryShortageCalculator` (`@Component`, **không** inject repository,
  không có side effect, thuần BigDecimal):
  `List<ShortageLine> shortages(List<AcceptedItem> reservable, Map<UUID, BigDecimal> availableByIngredient)`.
  Hàm tự tính `demand` theo ingredient từ `reservable`, áp dụng quy tắc ở mục 2, và trả list rỗng nếu không thiếu.
  Dùng `InventoryAvailabilityPolicy.quantity(...)` để chuẩn hóa scale. Dùng `RoundingMode.FLOOR` cho candidate
  và `CEILING` (scale 3) cho bước kiểm tra.
- `InventoryAvailabilityPolicy`: thêm `boolean hasAvailable(InventoryBalance balance, BigDecimal requested)`.
  `requireAvailable` gọi lại hàm này (giữ nguyên hành vi và test cũ). Thêm
  `BigDecimal available(InventoryBalance balance)` (null → `0.000`).
- `OrderInventoryAllocationAdapter.reserve(...)`: thay dòng `demand.forEach(... requireAvailable ...)` bằng:
  1. `if (demand.entrySet().stream().allMatch(e -> availability.hasAvailable(locked.get(e.getKey()), e.getValue())))`
     thì tiếp tục như cũ.
  2. Ngược lại: dựng `availableByIngredient` từ `locked` (mọi key trong một lần reserve dùng chung `warehouseId`),
     lọc `reservable = fresh` có `decisions.get(id) == null`, gọi calculator rồi
     `throw new InsufficientStockException(lines)`.
  - Giữ nguyên vị trí của bước kiểm tra (sau khi khóa balance, **trước** mọi `save`). Không đổi thứ tự lock.
    Không đổi `BalanceKey`.
  - Không đưa logic tính số phần vào adapter.

### 3.5 Handler HTTP
- `OrderConflictHandler` và `QrStaffErrorHandler`: thêm overload `error(status, code, message, details)`.
  `business(...)` truyền `failure.details()`. Giữ `CacheControl.noStore()` và `clock.instant()`.
- `PublicQrOrderErrorHandler`: **không sửa**. Thêm test chứng minh khách không bao giờ nhận `details`.

## 4. Tài liệu (theo bộ ba api / fe-sync / implementation của repo)
- `docs/openapi/order-api.yaml`: **giữ nguyên `Error`**. Thêm schema `InsufficientStockError`, gồm 5 thuộc tính
  của `Error` cộng `details` bắt buộc (với `ShortageLine`/`ShortIngredient`, `additionalProperties: false`).
  Response 409 của POST `/api/v1/orders`, confirm và append items dùng `oneOf: [Error, InsufficientStockError]`.
  Cập nhật đoạn mô tả V33 ở `info.description`.
- `docs/openapi/public-qr-order-api.yaml`: endpoint staff `/api/v1/qr-order-requests/{requestId}/approve`, response
  409 cũng dùng `oneOf`, bằng `$ref` sang `order-api.yaml`. Không đổi `Error` của file này.
- `docs/order-inventory-allocation-api.md`: thêm mục "Chi tiết thiếu tồn" (công thức, ví dụ 500g / 105g mỗi phần).
- `docs/order-inventory-allocation-fe-sync.md`: hướng dẫn FE hiển thị "{itemName}: chỉ còn {max} {unit}" hoặc
  "đã hết" khi 0. Nếu `sharesIngredientWithOtherLines` thì hiện thêm câu "dùng chung nguyên liệu với món khác trong đơn".
  Hiển thị `shortIngredients` cho quản lý. Nhắc rằng số liệu chỉ đúng tại thời điểm báo.
- `docs/direct-stock-menu-items-fe-sync.md`, `docs/qr-order-fe-sync.md`: thêm một dòng về `details`.
- `docs/order-inventory-allocation-implementation.md`: ghi lại quyết định thiết kế ở mục 3.

## 5. Test (bắt buộc)
- **Unit, mới** `src/test/java/com/possaas/modules/inventory/InventoryShortageCalculatorTest` (không dùng Spring,
  theo phong cách `InventoryAvailabilityPolicyTest`). Các case:
  - Một dòng: 3 phần × 105g, available 300 → max 2, shared=false.
  - Dùng chung: hai dòng, mỗi dòng 3 phần (315g), available 500 → mỗi dòng max 1, shared=true,
    `requiredQuantity` = 630.
  - available = 0 / balance null → max 0.
  - Direct stock: 5 lon, available 3 → max 3, unit "lon".
  - `orderQuantity` thập phân (1.5 kg) → bước 0.001.
  - Kiểm tra bảo thủ: lượng mỗi phần không chia hết (ví dụ recipe 100g, yield 3 → 33.334g/phần), kết quả
    không bao giờ vượt quá available.
  - Dòng chỉ dùng ingredient đủ thì không xuất hiện; ingredient đủ không có trong `shortIngredients`.
- **Unit**: `InventoryAvailabilityPolicyTest` cũ vẫn pass. Thêm case cho `hasAvailable`.
- **Integration** `OrderInventoryAllocationIntegrationTest`, mở rộng các test hiện có:
  - `sharedIngredientDemandIsAggregatedBeforeCheckingAndFailureLeavesNoOrderOrKey`: assert `details.lines` có 2 phần
    tử, mỗi phần tử `maxAvailableQuantity=1`, `sharesIngredientWithOtherLines=true`, `itemName`, `unit`.
  - `insufficientAppendPreservesExistingRootLinesReservationAndIdempotency`: max = 0, available 85.
  - `missingSecondIngredientRollsBack...`: `shortIngredients` chỉ chứa ingredient "Missing".
  - Giữ nguyên mọi assert về rollback, audit và idempotency.
- **Integration** `DirectStockIntegrationTest`: lỗi thiếu tồn của món bán trực tiếp có `details` đúng đơn vị món.
- **QR**: staff approve thiếu tồn → 409 có `details`, request vẫn PENDING. Endpoint khách không bao giờ có key `details`.
- **Hồi quy**: một lỗi `BusinessException` bất kỳ (ví dụ `ITEM_OUT_OF_STOCK`) **không** có key `details` trong JSON.
- **OpenAPI**: các test `*OpenApiTest` hiện có vẫn pass (đặc biệt `Error` vẫn đúng 5 thuộc tính). Thêm assert schema
  `InsufficientStockError` tồn tại và mọi `$ref` resolve được.

## 6. Ràng buộc
- Không migration DB, không đổi hành vi lock/rollback/idempotency/audit, không đổi chữ ký public của các hàm khác
  ngoài những gì nêu ở trên.
- Giữ code style của repo (compact, Lombok `@RequiredArgsConstructor`, records, `List.copyOf`). Không thêm dependency.
- Message giữ tiếng Anh. FE tự dịch theo `code`.

## 7. Kiểm tra hoàn thành
```bash
./mvnw -q test -Dtest="InventoryShortageCalculatorTest,InventoryAvailabilityPolicyTest,OrderInventoryAllocationIntegrationTest,DirectStockIntegrationTest,OrderInventoryAllocationOperationTest,OrderQrApprovalOperationTest,QrOrderOpenApiTest,OrderOpenApiTest"
```
```bash
./mvnw -q test
```
Báo cáo lại: danh sách file đã đổi, kết quả test (pass/fail kèm output nếu fail), và mọi điểm lệch so với prompt kèm lý do.
