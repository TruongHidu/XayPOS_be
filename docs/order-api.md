# ORDER MVP API

Order dành cho nhân viên đã đăng nhập: tại bàn (DINE_IN) hoặc mang đi (TAKEAWAY). Dùng menu/bàn/phiên hiện có; chưa có QR ordering, KDS, payment, đóng bill, recipe hoặc trừ kho. OpenAPI: [order-api.yaml](openapi/order-api.yaml).

Files và kết quả test thực tế: [order-implementation.md](order-implementation.md).

## Authorization và headers

```http
Authorization: Bearer <accessToken>
Accept: application/json
Content-Type: application/json
```

Content-Type dùng cho request JSON. Tenant và createdBy lấy từ principal; không nhận restaurantId/createdBy hoặc các amount/status từ body. System account không có tenant bị từ chối. Resource tenant khác được ẩn bằng 404. Account/restaurant status guards hiện có vẫn áp dụng. Response dùng Cache-Control no-store.

Mọi endpoint yêu cầu feature ORDER_MANAGEMENT trong effective subscription snapshot. Tạo DINE_IN và append SUBMIT vào DINE_IN thêm TABLE_MANAGEMENT; tạo TAKEAWAY và append SUBMIT vào TAKEAWAY thêm POS_QUICK_ORDER. Append DRAFT giữ feature gate legacy. Không cần recipe/menu-management/kitchen feature để tạo order. Feature gate không suy từ tên package và không sửa snapshot cũ.

| Method | Endpoint | Permission |
| --- | --- | --- |
| GET | /api/v1/orders | ORDER_VIEW |
| GET | /api/v1/orders/{orderId} | ORDER_VIEW |
| GET | /api/v1/table-sessions/{sessionId}/active-order | ORDER_VIEW |
| POST | /api/v1/orders | ORDER_CREATE; SUBMIT cần thêm ORDER_UPDATE; mở phiên mới cần thêm TABLE_OPEN |
| PATCH | /api/v1/orders/{orderId} | ORDER_UPDATE |
| POST | /api/v1/orders/{orderId}/items | ORDER_UPDATE |
| PATCH | /api/v1/orders/{orderId}/items/{orderItemId} | ORDER_UPDATE |
| POST | /api/v1/orders/{orderId}/items/{orderItemId}/cancel | ORDER_UPDATE cho draft chưa confirm; ORDER_CANCEL sau confirm |
| POST | /api/v1/orders/{orderId}/confirm | ORDER_UPDATE |
| POST | /api/v1/orders/{orderId}/cancel | ORDER_CANCEL |

V17 bổ sung CASHIER: ORDER_CREATE, ORDER_UPDATE, TABLE_VIEW; MANAGER: ORDER_VIEW, ORDER_CREATE, ORDER_UPDATE, ORDER_CANCEL, TABLE_VIEW. Không thêm ORDER_CANCEL cho WAITER/CASHIER mặc định. User GRANT/DENY không bị thay đổi; DENY vẫn thắng. JWT cần refresh/login lại để có các role grants mới. Frontend gọi API menu chọn món riêng vẫn cần permission/feature của API menu đó.

## Tạo order

`submissionMode` optional nhận `DRAFT` hoặc `SUBMIT`. Absent/null tương đương DRAFT; mode không hợp lệ trả 400 INVALID_REQUEST_BODY. Đây là ý định command, không phải quyền đặt status tùy ý.

- DRAFT: giữ legacy OPEN, confirmedAt=null, chỉ cần ORDER_CREATE.
- SUBMIT: cần ORDER_CREATE **và** ORDER_UPDATE. Tạo + xác nhận đồng bộ trong cùng transaction, trả CONFIRMED và confirmedAt từ Clock. Lines vẫn PENDING, sentToKitchenAt=null; chưa có gửi bếp thật.
- Không đủ quyền gửi thì 403, không tự downgrade về DRAFT. Audit/history/confirmation lỗi rollback toàn bộ.

Các body legacy không có mode nên là DRAFT; thêm `"submissionMode":"SUBMIT"` để gửi ngay. Riêng `tableId` bắt buộc SUBMIT tường minh. Không cần gọi /confirm lần nữa sau create SUBMIT thành công.

POST `/api/v1/orders`, thêm header bắt buộc:

```http
Idempotency-Key: <key ổn định của lần tạo này>
```

Key không blank, tối đa 100 ký tự. Frontend phải giữ nguyên key và payload khi retry; không sinh key mới mỗi retry.

CORS giữ allowed origins hiện có, cho phép Idempotency-Key qua cấu hình allowed headers và expose Idempotency-Replayed để web đọc response header.

### DINE_IN từ bàn: chỉ mở phiên khi gửi đơn

```json
{
  "serviceType": "DINE_IN",
  "sourceChannel": "WAITER",
  "tableId": "11111111-1111-4111-8111-111111111111",
  "submissionMode": "SUBMIT",
  "guestCount": 2,
  "note": "Khách tại bàn B01",
  "items": [
    { "itemId": "22222222-2222-4222-8222-222222222222", "quantity": 2, "note": "Không hành" }
  ]
}
```

Chọn bàn/chọn món không cần ghi DB. POST trên tìm phiên OPEN dưới restaurant lock:

- Chưa có phiên: cần TABLE_OPEN, tạo phiên và order CONFIRMED cùng transaction.
- Có phiên, chưa có serving order: dùng phiên cũ, không cần TABLE_OPEN và không sửa metadata/version của phiên.
- Có serving order: 409 TABLE_SESSION_HAS_SERVING_ORDER, không tự tạo thêm hoặc append.

Không truyền đồng thời tableId và tableSessionId. tableId + mode absent/null/DRAFT trả 400 TABLE_ORDER_REQUIRES_SUBMIT, không tự đổi mặc định DRAFT.
Bàn phải AVAILABLE/undeleted, area nếu có active/undeleted, cùng tenant. Phiên mới guestCount mặc định 1; phiên cũ lấy guestCount của phiên nếu request không gửi. Override chỉ áp dụng order; không sửa phiên cũ. Không bắt guestCount <= capacity. Order note không tự sao chép thành session note; QR không đổi.

Không cần expectedVersion cho command tạo từ tableId: kiểm cấu hình hiện tại dưới lock. Manual-open API vẫn giữ expectedVersion của bàn. Prepare chỉ đọc/kiểm món và tiền; persistence mới mở phiên qua TABLE-owned port, lấy UUID thật trước khi lưu order. Lỗi order/lines/history/confirmation/audit sau đó rollback cả phiên mới và audit TABLE_SESSION_OPENED.
Response vẫn OrderResponse, chứa tableSessionId thực tế. Không có endpoint /orders/from-table và không thêm tableId vào schema DB.

### DINE_IN từ phiên đã mở (legacy)

```json
{
  "serviceType": "DINE_IN",
  "sourceChannel": "WAITER",
  "tableSessionId": "11111111-1111-4111-8111-111111111111",
  "guestCount": 2,
  "note": "Khách tại bàn B01",
  "items": [
    { "itemId": "22222222-2222-4222-8222-222222222222", "quantity": 2, "note": "Không hành" }
  ]
}
```

Phiên phải OPEN và cùng tenant; bàn AVAILABLE/undeleted, area nếu có active/undeleted. Không tự mở phiên thay thế nếu tableSessionId missing/foreign/CLOSED/CANCELLED. Không thêm TABLE_OPEN vào contract legacy. guestCount mặc định từ session, không bắt buộc <= capacity. V19 chỉ cho tối đa một order đang phục vụ trong mỗi phiên; lịch sử cancel/recreate vẫn được giữ.

TAKEAWAY:

```json
{
  "serviceType": "TAKEAWAY",
  "sourceChannel": "CASHIER",
  "tableSessionId": null,
  "customerName": "Khách mang đi",
  "customerPhone": "0900000000",
  "items": [
    { "itemId": "22222222-2222-4222-8222-222222222222", "quantity": 1, "note": null }
  ]
}
```

TAKEAWAY không nhận tableId/session và không query/create bàn; guestCount mặc định 1. guestCount nếu gửi phải 1–32767. customerName <=100, customerPhone <=30, note <=2000 ký tự. items 1–100 dòng/request. quantity >0, tối đa 9 chữ số phần nguyên và 3 thập phân. Dòng cùng itemId không tự gộp.

SourceChannel client chỉ khai báo luồng tác nghiệp CASHIER/WAITER; không chứng nhận thiết bị hoặc tạo quyền. QR_STATIC/QR_TABLE bị từ chối tại endpoint nhân viên. Public QR/menu token chỉ cho xem menu.

### Idempotency contract

- Tạo mới: HTTP 201, Idempotency-Replayed: false.
- Cùng key/payload chuẩn hóa/actor trong tenant: HTTP 200, Idempotency-Replayed: true, cùng orderId và representation hiện tại.
- Body replay có thể thay đổi nếu order đã được sửa; đây là resource replay, không phải lưu nguyên response 201.
- Key khác payload/actor: 409 IDEMPOTENCY_KEY_REUSED.
- Auth/feature check và tenant lock trước replay. Replay được resolve trước recheck session/menu hiện tại, không reprice hoặc ghi audit/history/version mới.
- Normalization trim text, blank optional thành null, quantity chuẩn scale 3 (2 tương đương 2.0); hash gồm actor và payload, không gồm Clock/giá/session status hiện tại.
- Canonical JSON DRAFT giữ nguyên tám field legacy, không serialize submissionMode vào hash. Absent/null/explicit DRAFT tương đương, SUBMIT có fingerprint khác. Không rewrite hash lịch sử. Cùng key nhưng đổi mode trả 409 IDEMPOTENCY_KEY_REUSED.
- tableId absent/null giữ cả LegacyCreate DRAFT và create-submit-v1 SUBMIT byte-compatible. Có tableId dùng create-table-submit-v1, bao gồm tableId gốc và input đã normalize; không hash sessionId được resolve/tạo hoặc guestCount mặc định từ DB. Đổi bàn, actor, payload hoặc đổi intent sang tableSessionId với cùng key trả IDEMPOTENCY_KEY_REUSED.
- Replay tableId không resolve bàn hoặc mở phiên nữa, kể cả phiên cũ đã đóng/hủy, bàn có phiên/order mới hay món đã inactive. Vẫn kiểm current ORDER permissions/features; không bắt TABLE_OPEN cho replay không có side effect mở phiên.
- Replay trước kiểm tra serving slot: order A đã CANCELLED và order B đang phục vụ trong cùng phiên thì retry key/payload của A vẫn trả A, không bị B chặn hoặc trả nhầm B.

### Một serving order mỗi phiên

Serving status: OPEN, CONFIRMED, PREPARING, READY, SERVED. CANCELLED/COMPLETED không chiếm slot. OPEN draft cũng chiếm slot, không dùng tạo nháp để né rule.

Create DINE_IN bằng key mới khi đã có serving order trả 409 TABLE_SESSION_HAS_SERVING_ORDER; không tạo thêm, không tự append hoặc trả existing order như success. Caller cần xem active order rồi gọi thêm vào id đó. Error không expose existing order/PII cho caller thiếu ORDER_VIEW.

TAKEAWAY không có session và không bị rule này giới hạn. Khi chỉ có lịch sử terminal và phiên vẫn OPEN, create mới được phép theo các gate hiện có. Cancel không tự đóng phiên, không xóa snapshots/history. Predicate độc lập paymentStatus; financial seating cancellation guard giữ nguyên.

Migration [V19 diagnostic](order-serving-migration.md) fail rõ ràng nếu dữ liệu cũ có nhiều serving orders; không tự sửa dữ liệu.

## Snapshot và money

Backend batch-load menu/group trong đúng tenant. Chỉ MENU_ITEM active, undeleted, AVAILABLE; group null hợp lệ, group có giá trị phải active/undeleted/cùng tenant. OUT_OF_STOCK/INGREDIENT không được nhận vào order. Public menu vẫn có thể hiển thị OUT_OF_STOCK nhưng đây không phải quyền bán.

Snapshot itemName/unit/unitPrice từ menu; currencyCode từ restaurant. Đọc lịch sử không join menu để thay snapshot. Sửa quantity giữ giá cũ, thêm dòng mới lấy giá mới. Đổi currency profile không đổi order cũ; thêm dòng vào order cũ khác currency bị từ chối.

BigDecimal: lineTotal=quantity*unitPrice, HALF_UP scale 2; subtotal cộng các lineTotal chưa CANCELLED. discount/tax/serviceCharge=0, total=subtotal, paidAmount=0, paymentStatus=UNPAID. Giá 0 hợp lệ. Overflow NUMERIC(14,2) bị từ chối trước ghi DB. Dòng CANCELLED giữ snapshot/gross lineTotal nhưng loại khỏi tổng hiệu lực; order hủy hết món total=0.

## Response chi tiết

Create/mutation và GET detail trả cùng OrderResponse. Ví dụ 201 (UUID/mã chỉ minh họa):

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "orderCode": "OD-EXAMPLE",
  "serviceType": "DINE_IN",
  "sourceChannel": "WAITER",
  "tableSessionId": "11111111-1111-4111-8111-111111111111",
  "status": "OPEN",
  "paymentStatus": "UNPAID",
  "customerName": null,
  "customerPhone": null,
  "guestCount": 2,
  "note": "Khách tại bàn B01",
  "currencyCode": "VND",
  "subtotalAmount": 100000.00,
  "discountAmount": 0.00,
  "taxAmount": 0.00,
  "serviceChargeAmount": 0.00,
  "totalAmount": 100000.00,
  "paidAmount": 0.00,
  "createdBy": "44444444-4444-4444-8444-444444444444",
  "confirmedAt": null,
  "completedAt": null,
  "cancelledAt": null,
  "cancelReason": null,
  "createdAt": "2026-10-08T00:00:00Z",
  "updatedAt": "2026-10-08T00:00:00Z",
  "version": 0,
  "items": [
    {
      "id": "55555555-5555-4555-8555-555555555555",
      "itemId": "22222222-2222-4222-8222-222222222222",
      "itemName": "Phở bò",
      "unit": "tô",
      "quantity": 2.000,
      "unitPrice": 50000.00,
      "discountAmount": 0.00,
      "lineTotal": 100000.00,
      "status": "PENDING",
      "note": "Không hành",
      "cancelReason": null,
      "sentToKitchenAt": null
    }
  ]
}
```

Không có costPrice/metadata/idempotencyKey/requestHash/JPA association. Lines giữ thứ tự lineNumber nội bộ; dòng CANCELLED vẫn có trong detail.

## List và detail

### GET /api/v1/table-sessions/{sessionId}/active-order

Headers: Authorization Bearer + Accept application/json. Không có request body/key.

Yêu cầu ORDER_VIEW + ORDER_MANAGEMENT effective snapshot; không yêu cầu MENU_VIEW hoặc dùng role bypass. Session phải thuộc tenant; foreign/missing session trả 404 TABLE_SESSION_NOT_FOUND. Đây là query ownership, không kiểm seating OPEN để không nhầm với mutation eligibility.

- Có đúng một serving order: 200 OrderResponse hiện tại, giữ snapshot cũ.
- Không có serving order: 204, không có body.
- Dữ liệu inconsistent nhiều serving orders: 409 INCONSISTENT_SERVING_ORDER_STATE, không chọn latest/first.
- Cache-Control:no-store; không có side effect/audit/CoR trên API đọc.

### Các API đọc order hiện có

GET `/api/v1/orders/{orderId}` trả 200 OrderResponse.

GET `/api/v1/orders` query:

| Query | Default | Validation |
| --- | --- | --- |
| status | absent | OrderStatus enum |
| serviceType | absent | DINE_IN/TAKEAWAY |
| tableSessionId | absent | UUID; lọc theo tenant, không có kết quả trả empty page |
| q | absent | Tìm orderCode, <=100 ký tự, trim/case-insensitive; %, _, backslash literal |
| page | 0 | >=0, offset phải nằm trong giới hạn JPA |
| size | 20 | 1–100 |
| sortBy | createdAt | createdAt/updatedAt/orderCode/totalAmount |
| direction | desc | asc/desc, id ASC tie-breaker |

Response PageResponse<OrderSummaryResponse> gồm content/page/size/totalElements/totalPages. Summary không có items collection hoặc customerPhone; chứa id/code/type/source/session/status/paymentStatus/customerName/guestCount/currency/total/paid/timestamps/version. Không fetch-join collection rồi paginate trong memory.

## Mutation và expectedVersion

expectedVersion luôn là Order.version mới nhất, không phải version dòng hoặc session. Client lấy version từ response/GET; không tự tăng. Mọi mutation child thật đều tăng root version kể cả note/total=0/Clock.fixed. No-op không tăng version hoặc audit. Timestamp có thể bằng nhau nhưng version vẫn bảo vệ concurrent updates.

### PATCH /api/v1/orders/{orderId}

Chỉ OPEN, UNPAID và paidAmount=0.

```json
{ "expectedVersion": 0, "customerName": "Khách", "guestCount": 2, "note": "Ghi chú" }
```

Cho sửa customerName/customerPhone/guestCount/note. Absent/null không đổi; chuỗi blank clear optional field. Không cho đổi source/type/session/currency/status/amount. Chỉ expectedVersion không có field sửa trả EMPTY_UPDATE_REQUEST.

### POST /api/v1/orders/{orderId}/items

DRAFT (absent/null mode): chỉ OPEN, UNPAID/paidAmount=0, không tự confirm. Dòng mới batch snapshot theo giá hiện tại.

```json
{
  "expectedVersion": 1,
  "items": [
    { "itemId": "22222222-2222-4222-8222-222222222222", "quantity": 1, "note": "Ít cay" }
  ]
}
```

DRAFT không cần Idempotency-Key và không sử dụng ledger (header nếu gửi không có tác dụng idempotency). Retry cùng expectedVersion sau một lần thành công trả conflict; không tự tăng version rồi retry mù.

SUBMIT: cho OPEN hoặc CONFIRMED, UNPAID/paidAmount=0; bắt buộc Idempotency-Key và expectedVersion. DINE_IN cần session OPEN, bàn/area hợp lệ; currency root phải khớp restaurant. Các trạng thái PREPARING/READY/SERVED/COMPLETED/CANCELLED bị từ chối.

```http
POST /api/v1/orders/{orderId}/items
Authorization: Bearer <accessToken>
Content-Type: application/json
Idempotency-Key: <key ổn định riêng của lần gọi thêm>
```

```json
{
  "expectedVersion": 5,
  "submissionMode": "SUBMIT",
  "items": [
    { "itemId": "22222222-2222-4222-8222-222222222222", "quantity": 1, "note": "Ít cay" }
  ]
}
```

- Giữ orderId/orderCode/source/actor/context; mỗi dòng mới ID mới, lineNumber tiếp theo, giá/tên/đơn vị hiện tại. Không merge, không reprice/reset dòng cũ.
- OPEN + SUBMIT: append và confirm cùng transaction, recheck mọi dòng hiệu lực; món cũ không sellable thì rollback cả phần gọi thêm. confirmedAt từ Clock.
- CONFIRMED + SUBMIT: root vẫn CONFIRMED, confirmedAt giữ nguyên, không ghi ORDER_CONFIRMED lần nữa. Dòng mới PENDING, kitchen timestamp null.
- Metadata và sửa quantity/note của dòng cũ vẫn chỉ OPEN. Sau tự confirm, hủy cả dòng mới cũng cần ORDER_CANCEL theo confirmedAt.
- New/replay đều 200 OrderResponse hiện tại, `Idempotency-Replayed:false/true`, `Cache-Control:no-store`. Dùng version backend trả, không giả định chỉ tăng một lần trong operation gộp.

Ledger V18 `order_item_submissions` có unique(restaurant_id,idempotency_key), FK tenant-safe đến order. Namespace append độc lập create; không thay creation key/hash của root. Fingerprint gồm actor, target, expectedVersion, mode và ordered normalized lines, không gồm thời gian/giá/status.

Replay sau auth/features/lock nhưng trước lifecycle/version/menu/seating: cùng actor/target/hash/key trả resource hiện tại, không thêm line/audit/history/version. Payload/target/actor hoặc expectedVersion khác với key đã ghi: 409 IDEMPOTENCY_KEY_REUSED. Hai key khác nhau cùng version: một thành công, một CONCURRENT_ORDER_UPDATE. Ledger rollback cùng mutation.

Timeout: giữ nguyên key + target + payload + expectedVersion đã gửi để thử lại. Không đọc version mới rồi thay vào payload cũ. 409 cần tải lại và để người dùng quyết định một intent mới; không âm thầm tạo đơn mới khi append thất bại.

Một session có thể có nhiều historical rows, nhưng chỉ tối đa một serving order. Bàn → phiên → GET active-order → detail → append vào id đó. Danh sách vẫn phân trang để xem lịch sử; không lấy latest/first hoặc auto-create-or-append. Nếu chưa có serving order và session còn OPEN, create mới là action riêng có chủ ý.

### PATCH /api/v1/orders/{orderId}/items/{orderItemId}

Chỉ dòng PENDING của OPEN order. Giữ giá snapshot.

```json
{ "expectedVersion": 2, "quantity": 3, "note": "Không hành" }
```

### POST /api/v1/orders/{orderId}/confirm

```json
{ "expectedVersion": 3 }
```

OPEN -> CONFIRMED, phải có dòng hiệu lực và recheck sellability. Không reprice. Confirm lại CONFIRMED trả current response không kiểm stale version vì đây là terminal no-op của operation confirm; không đổi confirmedAt/audit. Các trạng thái khác bị từ chối. Chưa có gửi bếp thật nên sentToKitchenAt vẫn null. Sau confirm không sửa metadata/quantity/note cũ; gọi thêm vào order đã chọn bằng append SUBMIT.

### POST /api/v1/orders/{orderId}/items/{orderItemId}/cancel

```json
{ "expectedVersion": 4, "reason": "Khách đổi món" }
```

Reason không blank, <=1000 ký tự. Draft chưa confirm cần ORDER_UPDATE; đã confirm cần ORDER_CANCEL, kể cả retry sau order cancelled (confirmedAt giữ boundary gốc). Chỉ PENDING; COOKING/READY/SERVED bị từ chối. Dòng cuối bị hủy tự chuyển order CANCELLED. Dòng đã CANCELLED trả current response, bỏ stale version, không audit/history mới.

### POST /api/v1/orders/{orderId}/cancel

```json
{ "expectedVersion": 5, "reason": "Khách không sử dụng" }
```

ORDER_CANCEL. OPEN/CONFIRMED và không có cooking/fulfilled items hoặc nghĩa vụ thanh toán. Hủy các dòng còn hiệu lực, giữ snapshots/history; cancel lại CANCELLED là no-op trước version check, không ghi trùng. Mutation response 200 OrderResponse.

## Session guard và transaction

Order mutation, menu mutation và table/session mutation dùng chung pessimistic restaurant lock tới commit. Khi cancel session, TABLE gọi port TableSessionUsagePolicy, ORDER cung cấp query DB thật. Chặn nếu còn order chưa CANCELLED, paidAmount>0 hoặc paymentStatus!=UNPAID. Cho cancel khi không có order hoặc mọi order CANCELLED/UNPAID/paid=0. Query lỗi fail closed, thiếu adapter fail startup.

Create thắng race thì cancel session bị TABLE_SESSION_IN_USE; cancel session thắng thì create bị TABLE_SESSION_NOT_OPEN. Không có order mới vào session đã CANCELLED/CLOSED. Cancel seating không hủy bill/refund; chưa có /close API.

Audit/history/mutation cùng transaction. Lịch sử dòng chỉ null->PENDING và PENDING->CANCELLED trong MVP, append-only trigger; changeSequence từ mutationSequence nội bộ tạo thứ tự dù Clock bằng nhau. Sửa quantity/note hoặc confirm không tạo PENDING->PENDING giả. mutationSequence làm parent dirty để @Version tăng, không tự gán version.

Audit: ORDER_CREATED, ORDER_UPDATED, ORDER_ITEMS_ADDED, ORDER_ITEM_UPDATED, ORDER_ITEM_CANCELLED, ORDER_CONFIRMED, ORDER_CANCELLED; tự mở phiên thêm TABLE_SESSION_OPENED đúng một lần trong cùng transaction. Tái sử dụng phiên không thêm event mở phiên. Không dump customer PII/note/body/key/hash/token. Request bị từ chối/no-op/replay không có audit thành công giả. Audit/history failure rollback toàn bộ.

## Errors

| HTTP | Codes |
| --- | --- |
| 400 | VALIDATION_ERROR, INVALID_REQUEST_BODY, EMPTY_UPDATE_REQUEST, EMPTY_ORDER (input), INVALID_IDEMPOTENCY_KEY, DINE_IN_SESSION_REQUIRED, AMBIGUOUS_ORDER_SEATING, TABLE_ORDER_REQUIRES_SUBMIT, TAKEAWAY_SESSION_NOT_ALLOWED, TAKEAWAY_TABLE_NOT_ALLOWED, UNSUPPORTED_ORDER_SOURCE, ORDER_AMOUNT_LIMIT_EXCEEDED |
| 401 | UNAUTHORIZED, ACCOUNT_INACTIVE |
| 403 | FORBIDDEN, TENANT_ACCESS_DENIED, RESTAURANT_INACTIVE, SUBSCRIPTION_NOT_ACTIVE, FEATURE_NOT_ENTITLED |
| 404 | ORDER_NOT_FOUND, ORDER_ITEM_NOT_FOUND, TABLE_SESSION_NOT_FOUND, TABLE_NOT_FOUND, TABLE_AREA_NOT_FOUND, MENU_ITEM_NOT_FOUND |
| 409 | TABLE_SESSION_HAS_SERVING_ORDER, TABLE_ALREADY_OCCUPIED, INCONSISTENT_TABLE_SESSION_STATE, INCONSISTENT_SERVING_ORDER_STATE, CONCURRENT_ORDER_UPDATE, IDEMPOTENCY_KEY_REUSED, ORDER_CODE_CONFLICT, ORDER_NOT_EDITABLE, ORDER_ITEM_NOT_EDITABLE, INVALID_ORDER_TRANSITION, EMPTY_ORDER (confirm), TABLE_SESSION_NOT_OPEN, TABLE_SESSION_IN_USE, TABLE_INACTIVE, TABLE_AREA_INACTIVE, ITEM_NOT_SELLABLE, ITEM_OUT_OF_STOCK, ORDER_CURRENCY_MISMATCH, ORDER_HAS_FINANCIAL_OBLIGATIONS |
| 500 | INTERNAL_ERROR |

```json
{
  "success": false,
  "code": "CONCURRENT_ORDER_UPDATE",
  "message": "Order changed; reload before retrying",
  "fieldErrors": {},
  "timestamp": "2026-10-08T00:00:00Z"
}
```

Use status/code, not message matching. Scope handler uses Clock and does not expose SQL/exception or rejected values. Existing private error fieldErrors contract is preserved.

## Postman flow

Set baseUrl=http://localhost:8080, accessToken, tableId/sessionId, itemId. UUID trong ví dụ phải thay bằng dữ liệu thật.

1. Login/refresh OWNER/WAITER/CASHIER/MANAGER với quyền phù hợp.
2. DINE_IN: chọn bàn hoạt động; dùng tableId + SUBMIT để backend mở phiên khi gửi. Hoặc manual-open rồi dùng tableSessionId theo contract legacy.
3. POST /orders với Idempotency-Key, lưu id/tableSessionId/version và items[].id. Kiểm response CONFIRMED nếu SUBMIT.
4. GET /orders/{id}; thêm/sửa dòng với version mới nhất.
5. Retry create cùng key/payload xác nhận 200 cùng id.
6. Confirm (nếu tạo DRAFT), xác nhận không sửa quantity cũ; append SUBMIT vào cùng id và thử replay cùng key/payload. Hoặc tạo SUBMIT ngay từ bước 3 để bỏ bước confirm riêng.
7. Cancel với quyền đúng; kiểm lịch sử và không có audit trùng.
8. Thử cancel session có order hiệu lực: TABLE_SESSION_IN_USE.

API hoạt động với package/role permission thực tế, không sửa feature snapshot để test local. Test automation dùng schema riêng it_order_*, Flyway từ rỗng và cleanup riêng, không clean dev/public.
