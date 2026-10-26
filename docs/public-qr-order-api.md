# QR gọi món: bàn và mang đi, có bước nhân viên duyệt

Nhân viên có thể nghe [QR/ORDER realtime](order-realtime-api.md) để tải lại backlog
và hàng đợi bếp sau approval. Guest WebSocket chưa được hỗ trợ; guest REST giữ nguyên.

Backend-only MVP. [OpenAPI](openapi/public-qr-order-api.yaml) · [Hướng dẫn đồng bộ FE](qr-order-fe-sync.md).

V32 chốt [recipe snapshot](order-recipe-snapshot-api.md) tại approve, không ở guest
submit/review. Approve tạo mới và gọi thêm cùng dùng operation snapshot của ORDER;
recipe version lấy tại lúc approve, không tự sửa snapshot dòng cũ. Lỗi snapshot
rollback cả approval/order/session/idempotency/audit trong transaction. Không thay
body hoặc quyền QR. V33 tiếp tục [giữ chỗ nguyên liệu](order-inventory-allocation-api.md)
chỉ khi approve; guest pending/review/reject/cancel/expire không giữ kho. Approve
thiếu available hoặc kho default trả 409 và request vẫn PENDING. Sau bổ sung tồn,
staff review/approve lại theo version/hash/quote guard. Replay không giữ kho thêm.
Ingredient/allocation/cost không xuất hiện trong public QR response.

Quote/hash/request DTO giữ contract hiện có.

`QrOrderRequest` là yêu cầu của khách, không phải order. PENDING không mở phiên, tạo order/order_items, gửi bếp, tính vào bill, làm bàn bận hoặc đặt giữ bàn. Sau approval mới có order/món và history/audit nghiệp vụ. `QrOrderRequest.PENDING` khác `OrderItem.PENDING` (món đã được duyệt vào order, chờ chế biến).

## Luồng và quyền

| Entry | Mapping do server đặt | Feature trong effective immutable snapshot | Kết quả duyệt |
| --- | --- | --- | --- |
| TABLE | DINE_IN / QR_TABLE | QR_MENU_VIEW, QR_TABLE_ORDER, ORDER_MANAGEMENT, TABLE_MANAGEMENT | Mở phiên khi chưa có; tạo hoặc append vào tối đa một serving order của phiên được review/chọn |
| RESTAURANT | TAKEAWAY / QR_STATIC | QR_MENU_VIEW, QR_STATIC_ORDER, ORDER_MANAGEMENT | Một TAKEAWAY order độc lập cho mỗi request |

Không cần nhân viên mở phiên hoặc cấp PIN trước khi khách gửi. Tên nhận hàng bắt buộc cho TAKEAWAY; phone tùy chọn. QR mang đi không cần `TABLE_MANAGEMENT`, `TABLE_OPEN` hoặc `POS_QUICK_ORDER`. API staff tạo TAKEAWAY hiện có vẫn giữ gate `POS_QUICK_ORDER` và chỉ cho source CASHIER/WAITER; guest không được dùng API đó hoặc giả source QR.

Feature lấy từ subscription ACTIVE và effective (`startAt <= Clock.instant() < endAt`), không từ packageCode/catalog hiện tại. V26 bổ sung `QR_TABLE_ORDER` và `QR_STATIC_ORDER` vào catalog BASIC/PRO/PREMIUM, chỉ thêm mapping còn thiếu và không sửa snapshot đã activate hoặc package tùy chỉnh. Subscription ACTIVE cũ không tự có quyền QR mới. Menu read hiện có vẫn chỉ cần `QR_MENU_VIEW`, không cần guest token/session/PIN:

```text
GET /api/v1/public/menu/tables/{qrToken}
GET /api/v1/public/menu/tables/{qrToken}/items
GET /api/v1/public/menu/restaurants/{menuToken}
GET /api/v1/public/menu/restaurants/{menuToken}/items
```

Staff dùng JWT/tenant hiện có và permission, không hardcode role:

| Thao tác | Permission |
| --- | --- |
| List/detail request | ORDER_VIEW |
| Approve | ORDER_CREATE và ORDER_UPDATE |
| Approve thực sự mở phiên TABLE mới | Thêm TABLE_OPEN |
| Reject | ORDER_UPDATE |

List/detail/reject vẫn qua feature vận hành và security tenant nhưng không đòi QR ordering grant còn effective, để xử lý backlog. Approval kiểm lại QR entitlement và scope. Backend là nơi quyết định cuối cùng, không dựa field FE hoặc JWT của guest.

## Header chung

Tất cả response QR mới dùng `Cache-Control: no-store`. Không cache token, request, review ở service worker/CDN. JSON body có `Content-Type: application/json`; nhận response bằng `Accept: application/json`.

QR POST body giới hạn 32768 bytes (32 KiB), kiểm cả request chunked trước deserialization; vượt giới hạn trả `413 QR_REQUEST_BODY_TOO_LARGE`. Budget tách riêng khỏi staff ORDER APIs hiện có.

Guest access mint không có Authorization. Guest submit/read dùng riêng:

```http
X-QR-Guest-Token: <opaque guest token>
```

Token không phải JWT, không dùng được cho ORDER/TABLE/PAYMENT private. Staff JWT kèm theo không bypass kiểm tra guest token. Token chỉ được gửi trong header, không URL/query string. Grant gắn một guest identity và immutable entry scope; không tạo user hoặc tính vào maxStaff. Chỉ lưu digest, không plaintext.

Staff list/detail/approve/reject dùng:

```http
Authorization: Bearer <staff access token>
```

Không cần restaurantId header: guest tenant lấy từ grant; staff tenant lấy từ principal. Không gửi token/key/raw body vào log hoặc audit.

## 1. Mint access từ QR bàn

```http
POST /api/v1/public/qr-order/tables/{qrToken}/guest-access
Content-Type: application/json

{}
```

Resolve cùng token của menu bàn. Bàn/khu vực nếu có và nhà hàng phải hợp lệ; cần đủ TABLE ordering features. Response `201` cấp guest token TABLE, thời điểm hết hạn, loại phục vụ và display context. Không tạo request/order/session. Không trả lại raw qrToken trong DTO.

```json
{
  "guestToken": "<opaque-43-character-token>",
  "expiresAt": "2026-10-14T02:00:00Z",
  "entryType": "TABLE",
  "serviceType": "DINE_IN",
  "sourceChannel": "QR_TABLE",
  "restaurant": {
    "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "name": "Nhà hàng Demo",
    "currencyCode": "VND"
  },
  "table": {
    "id": "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    "code": "B01",
    "name": "Bàn 1"
  }
}
```

## 2. Mint access từ menu nhà hàng

```http
POST /api/v1/public/qr-order/restaurants/{menuToken}/guest-access
Content-Type: application/json

{}
```

menuToken chính là `restaurants.public_order_token` đang có, không sinh namespace token khác. Response `201` cấp guest token RESTAURANT/TAKEAWAY. Không gọi TABLE, không query/mở phiên, không cần feature bàn. Không trả lại raw menuToken.

```json
{
  "guestToken": "<opaque-43-character-token>",
  "expiresAt": "2026-10-14T02:00:00Z",
  "entryType": "RESTAURANT",
  "serviceType": "TAKEAWAY",
  "sourceChannel": "QR_STATIC",
  "restaurant": {
    "id": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    "name": "Nhà hàng Demo",
    "currencyCode": "VND"
  },
  "table": null
}
```

Guest access TTL mặc định 60 phút. TABLE grant không bắt buộc session ID. Mint/gửi/mở phiên đầu tiên không tăng table access epoch. Epoch tăng khi kết thúc/hủy/chuyển lượt bàn hoặc rotate QR; grant cũ không đi sang nhóm khách mới. Restaurant grant không có table epoch/session và không bị table lifecycle ảnh hưởng.

## 3. Gửi request chung

```http
POST /api/v1/public/qr-order/requests
Content-Type: application/json
X-QR-Guest-Token: <guestToken>
Idempotency-Key: <key mới cho một intent>

{
  "customerName": "Nguyễn Văn An",
  "customerPhone": "0900000000",
  "note": "Nhận tại quầy",
  "items": [
    {
      "itemId": "11111111-1111-4111-8111-111111111111",
      "quantity": 2,
      "note": "Không hành"
    }
  ]
}
```

| Field | Rule |
| --- | --- |
| customerName | TAKEAWAY bắt buộc trim/nonblank <=100; TABLE tùy chọn |
| customerPhone | Tùy chọn, <=30; không suy guest identity từ phone |
| note | Tùy chọn, <=500 |
| items | 1–20 dòng; item thuộc đúng tenant, MENU_ITEM và đang bán được |
| itemId | UUID món từ menu của scope |
| quantity | >0, <=20, tối đa scale 3 |
| items[].note | Tùy chọn, <=500 |

Không nhận `restaurantId`, `tableId`, `tableSessionId`, `sessionId`, `orderId`, `entryType`, `serviceType`, `sourceChannel`, giá/tiền/status/actor/approval hoặc field không hỗ trợ. Item OUT_OF_STOCK vẫn có thể hiển thị trên menu nhưng không được gọi; group/item phải active, undeleted, cùng tenant. Giá/tiền backend tính bằng BigDecimal.

TABLE capture `observedSessionId` một lần dưới restaurant lock, hoặc null nếu chưa có phiên. Không mở phiên. Phiên có checkout/nghĩa vụ thanh toán không nhận request mới. TAKEAWAY không capture phiên, không tìm order cùng khách để gộp, không bị chặn bởi order khác đã PAID.

Response `201` request mới, `200` replay. Header `Idempotency-Replayed: false/true`. TTL request mặc định 15 phút. Quote lúc gửi là estimated amount; giá chốt ở lúc staff approve.

`expiresAt` là thời điểm sớm hơn giữa thời điểm gửi + request TTL và guest grant expiry; request không sống lâu hơn grant của khách.

Ví dụ pending TAKEAWAY receipt (`201`); timestamps/UUID/tiền chỉ minh họa:

```json
{
  "requestId": "22222222-2222-4222-8222-222222222222",
  "entryType": "RESTAURANT",
  "serviceType": "TAKEAWAY",
  "sourceChannel": "QR_STATIC",
  "status": "PENDING",
  "version": 0,
  "expired": false,
  "currencyCode": "VND",
  "estimatedAmount": 90000.00,
  "approvedAmount": null,
  "approvedOrderCode": null,
  "createdAt": "2026-10-14T01:00:00Z",
  "expiresAt": "2026-10-14T01:15:00Z",
  "items": [
    {
      "requestItemId": "33333333-3333-4333-8333-333333333333",
      "itemId": "11111111-1111-4111-8111-111111111111",
      "itemName": "Phở bò",
      "unit": "tô",
      "quantity": 2.000,
      "estimatedUnitPrice": 45000.00,
      "estimatedLineTotal": 90000.00,
      "note": "Không hành",
      "approvedOrderItemId": null,
      "approvedUnitPrice": null,
      "approvedLineTotal": null,
      "status": null
    }
  ]
}
```

TABLE dùng cùng schema receipt, mapping TABLE/DINE_IN/QR_TABLE. Receipt không có customerName/customerPhone của root order, approvedOrderId, tableSessionId, staff actor hoặc root totalAmount. Pending item `status: null` vì chưa có order item.

Same key + cùng guest/scope/payload trả request cũ, kể cả APPROVED/REJECTED. Same key khác actor/scope/payload trả `409 IDEMPOTENCY_KEY_REUSED`, không lộ request người khác. Fingerprint không phụ thuộc quote, phiên/order hiện tại hoặc branch xử lý; observed session không được capture lại khi replay. Retry lỗi mạng giữ nguyên key và body của intent.

Key gồm 1–100 ký tự trong `[A-Za-z0-9._:-]`; UUID string phù hợp. Replay vẫn cần guest grant/scope còn hợp lệ: không thể dùng token revoked/expired để truy cập request cũ.

## 4. Đọc receipt của chính guest

```http
GET /api/v1/public/qr-order/requests/{requestId}
X-QR-Guest-Token: <guestToken>
Accept: application/json
```

Response `200` chỉ chứa request của đúng guest identity và tenant. Có estimated amount, trạng thái/version/TTL; sau APPROVED có approved amount của chính request, order code và trạng thái exact dòng được tạo từ request. Không trả root bill, customer/staff PII của người khác, costPrice, full private OrderResponse hoặc quyền ghi ORDER/TABLE/PAYMENT. Không có guest approve/reject/edit/cancel trong MVP.

Stored PENDING đã `expiresAt <= now` có `expired: true`, không còn approve được dù không có expiry job. Guest GET vẫn phải có grant còn hợp lệ; stale/rotated/revoked guest scope không được dùng để lấy receipt.

GET trả cùng schema như submit. Sau APPROVED, `status` trở thành APPROVED, `approvedAmount`/`approvedOrderCode` có giá trị; từng dòng có `approvedOrderItemId`, `approvedUnitPrice`, `approvedLineTotal` và status order item tương ứng. Estimated fields không đổi theo giá menu mới hoặc root bill mới. Nếu root có món từ staff/guest khác, các món đó không xuất hiện ở receipt này.

`expired` chỉ phản ánh deadline đã qua, không đổi terminal status. Request APPROVED vẫn APPROVED dù expired=true sau deadline; FE phải ưu tiên terminal status. Chỉ PENDING+expired bị từ chối approval do hết hạn.

## 5. List request cho staff

```http
GET /api/v1/qr-order-requests?page=0&size=20
Authorization: Bearer <staffAccessToken>
Accept: application/json
```

Filter `entryType` TABLE/RESTAURANT, `status` PENDING/APPROVED/REJECTED/CANCELLED, `tableId`, `observedSessionId`, `includeExpired`, `page`, `size`. TABLE filters chỉ có ý nghĩa cho request bàn. Default queue PENDING còn hạn và scope hợp lệ; history có thể xem bằng status tường minh. `includeExpired` mặc định false; page mặc định 0, size 20, tối đa 100.

Response `200` PageResponse với `content`, `page`, `size`, `totalElements`, `totalPages`. Không N+1: lọc trước pagination, batch request items/context cần thiết. Cross-tenant request không thể đọc được.

Mỗi `content[]` là staff summary gồm:

```text
requestId, entryType, serviceType, sourceChannel, status, version, expired,
tableId, observedSessionId, customerName, customerPhone, note, currencyCode,
estimatedAmount, approvedAmount, approvedOrderId, approvedSessionId,
approvedBy, approvedAt, rejectedBy, rejectedAt, rejectionReason,
cancelledAt, cancellationReason, createdAt, expiresAt, items[]
```

`items[]`: id (request item), itemId, itemName, unit, quantity, unitPrice, lineTotal, note, approvedOrderItemId. `unitPrice`/`lineTotal` ở summary là submitted snapshot, không phải current quote. TABLE ID/observed session và terminal metadata nullable; RESTAURANT tableId/observedSessionId/approvedSessionId luôn null.

Empty page hợp lệ:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0 }
```

## 6. Staff review detail/current quote

```http
GET /api/v1/qr-order-requests/{requestId}
Authorization: Bearer <staffAccessToken>
Accept: application/json
```

Response `200`: request/submitted items/customer snapshot, submitted quote, current authoritative quote, `reviewContextHash`, `canApprove`, `reasonCode`, TABLE review context nếu có. GET không mở phiên, tạo order, assign hoặc mutation request. TAKEAWAY không resolve TABLE/session và không đưa order khác vào hash.

Hash canonical versioned bao gồm identity/version/intent/scope/credential, quote/currency/availability; TABLE thêm epoch/current session/current serving order/version/state/financial context. Staff phải xem lại khi hash/context/price đổi; hash không thay authorization.

Detail schema chính xác:

```text
{
  request: <staff summary nêu trên>,
  currentQuote: { currencyCode, amount, lines[] } hoặc null,
  reviewContextHash: <64 chữ số hex lowercase> hoặc null,
  canApprove: true/false,
  reasonCode: <stable code> hoặc null,
  reviewContext: { currentSessionId, tableAccessEpoch, sessionVersion, guestCount, currentOrder } hoặc null
}
```

Current quote lines: itemId, quantity, note, itemName, unit, unitPrice, lineTotal. Serving `currentOrder` nếu có: id, version, status, paymentStatus, currencyCode, totalAmount, paidAmount. TABLE sessionVersion/guestCount nullable khi chưa có phiên và tham gia review hash để phát hiện phiên đã cập nhật. `reviewContext` là TABLE-only; TAKEAWAY trả null. Khi request/scope/quote không approvable, current quote/hash có thể null và reasonCode giải thích; không dùng hash null để approve.

## 7. Staff approve

```http
POST /api/v1/qr-order-requests/{requestId}/approve
Authorization: Bearer <staffAccessToken>
Content-Type: application/json

{
  "expectedRequestVersion": 0,
  "expectedReviewContextHash": "<64-hex-hash-from-detail>",
  "targetSessionId": null
}
```

Cả ba property bắt buộc. Version là QR request version. Missing `targetSessionId` bị `400`, không được hiểu như null:

- RESTAURANT: phải null tường minh; UUID bị `400 TAKEAWAY_SESSION_NOT_ALLOWED`. Mỗi approval tạo đúng một TAKEAWAY/QR_STATIC CONFIRMED order, lines PENDING/sentToKitchenAt, UNPAID/paidAmount=0, createdBy reviewer.
- TABLE null: chỉ mở phiên mới nếu hiện không có OPEN session. openedBy là reviewer thật; `TABLE_OPEN` chỉ cần khi thực sự mở mới.
- TABLE ID S: phải đúng session hiện tại, OPEN, đúng bàn/tenant và đủ điều kiện. Nếu request đã quan sát S lúc gửi, chỉ chọn S. Request observed null có thể chọn S sau review mới và xác nhận đúng nhóm khách, không tự chọn latest.

Không có serving order thì tạo DINE_IN/QR_TABLE CONFIRMED. Có CONFIRMED/PREPARING/READY/SERVED unpaid đủ điều kiện thì append vào cùng order, giữ root source/creator/customer/note/snapshot cũ. OPEN draft trả `409 QR_ORDER_DRAFT_NOT_SUBMITTED`, không tự confirm draft. Có checkout/nghĩa vụ thanh toán không append. Nhiều serving order fail rõ ràng, giữ unique index V19.

Response `200` có request APPROVED/result linkage, reviewer/time, approved order/session, exact approved order item IDs và staff OrderResponse. Session/order/items/linkage/request/history/audit cùng transaction dưới restaurant pessimistic lock. Rollback không để lại phiên/order nửa chừng.

Response fields:

```text
requestId, status="APPROVED", version, approvedOrderId, approvedSessionId,
approvedBy, approvedAt, approvedAmount, approvedOrderItemIds[], replay, order
```

`replay` false khi mutation, true khi trả stored approval. `approvedSessionId` null với TAKEAWAY. `order` là [private OrderResponse hiện có](order-api.md), không thay contract root Order; request approvedAmount chỉ là tiền của các dòng request. Không đưa response staff này cho guest/customer website.

APPROVED retry trả stored result, không reroute hoặc thêm món/order/phiên, không đổi approvedBy/approvedAt; không yêu cầu session còn OPEN/order còn UNPAID và không kiểm hash cũ theo root state mới. Target UUID khác approved session conflict. TAKEAWAY target luôn null.

Hai TABLE request gửi khi chưa có phiên: A được duyệt mở S; B dùng hash/context “chưa có phiên” cũ trả 409. Staff đọc lại B rồi chọn S để append một lần. Hai TAKEAWAY requests của cùng guest được duyệt thành hai orders riêng; retry không tạo order thứ ba.

## 8. Staff reject

```http
POST /api/v1/qr-order-requests/{requestId}/reject
Authorization: Bearer <staffAccessToken>
Content-Type: application/json

{
  "expectedRequestVersion": 0,
  "reason": "Nhà hàng hiện chưa thể nhận yêu cầu này"
}
```

Reason trim/nonblank <=1000, version >=0. Response `200` request REJECTED. Không tạo/cập nhật order/session. REJECTED retry là no-op, giữ reason/reviewer/time ban đầu, không audit trùng. Không reject APPROVED, không approve REJECTED/CANCELLED. Không yêu cầu guest/QR entitlement còn hiệu lực để reject backlog.

Response dùng staff summary như `content[]` ở list, với status REJECTED, rejectedBy/rejectedAt/rejectionReason được chốt. Approved linkage vẫn null; không có private order payload.

## Scope checkout/lifecycle

PAYMENT MVP hiện chỉ có settlement SUCCESS thủ công: callback hủy request chạy cùng transaction
ở bước đó. Chưa thêm API tạo PENDING payment intent. Khi bổ sung provider/intent sau này, writer
phải gọi cùng lifecycle port khi tạo intent thành công dưới restaurant lock; kiểm tra checkout PENDING
đã được hỗ trợ. Quy tắc phạm vi dưới đây áp dụng cho callback đó, không tự theo dõi mọi insert trực tiếp.

| Sự kiện | Scope bị invalid/cancel PENDING | Không bị ảnh hưởng |
| --- | --- | --- |
| TABLE payment intent tạo thành công | Requests đã quan sát đúng session đang checkout, reason CHECKOUT_STARTED | observedSessionId null, phiên/tenant khác, RESTAURANT requests |
| Payment creation rollback | Cancellation rollback cùng transaction | Không để request bị hủy bởi payment thất bại chưa tạo được |
| TABLE close/cancel/transfer/QR rotate | Grant/epoch/credential và PENDING TABLE requests bị ảnh hưởng | RESTAURANT requests, APPROVED orders/payment |
| Restaurant menu token rotate | Grant và PENDING RESTAURANT requests của credential cũ | QR_TABLE requests, APPROVED orders/payment |
| TAKEAWAY payment/cancel/complete | Chỉ order được chọn | Requests khác cùng guest/name/phone/restaurant |

PENDING QR request không là financial obligation và không block payment toàn nhà hàng. Không tự gán observed null vào session vì cùng bàn. Failed/cancelled payment khi hết nghĩa vụ cho phép intent mới, không hồi sinh request CANCELLED. Payment không đòi mọi món SERVED; complete vẫn đòi thanh toán đủ + tất cả món hiệu lực SERVED, DINE_IN auto-close cùng transaction; bulk served PENDING/COOKING/READY → SERVED và single READY → SERVED giữ nguyên.

## Errors

Envelope gồm `success: false`, `code`, `message`, `fieldErrors`, `timestamp`. Message không phải contract phân nhánh UI; dùng HTTP/code. Không echo secret, rejected value, SQL hoặc stack trace.

| HTTP | Code | Cách xử lý |
| --- | --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY | Body/header/query/unknown fields hoặc property bắt buộc không hợp lệ |
| 400 | TAKEAWAY_SESSION_NOT_ALLOWED | RESTAURANT approve target phải null |
| 401 | QR_GUEST_ACCESS_INVALID | Guest token invalid, expired hoặc revoked |
| 404 | QR_ORDER_REQUEST_NOT_FOUND / QR_ORDER_UNAVAILABLE | Receipt không thuộc guest/tenant hoặc public QR/menu link invalid/unknown; không thử đọc request khác |
| 403 | QR_ORDER_UNAVAILABLE / FORBIDDEN / FEATURE_NOT_ENTITLED / SUBSCRIPTION_NOT_ACTIVE | Ordering không khả dụng hoặc staff thiếu quyền/feature; giữ menu read riêng |
| 409 | QR_ORDER_REQUEST_EXPIRED | Không approve được; không cần job expire |
| 409 | CONCURRENT_QR_REQUEST_UPDATE | Reload request/result |
| 409 | QR_REVIEW_CONTEXT_CHANGED | Reload quote/context, staff xác nhận lại; không tự thay hash rồi approve |
| 409 | QR_REQUEST_SESSION_CHANGED | Không route sang phiên mới hoặc nhóm khách khác |
| 409 | QR_ORDER_DRAFT_NOT_SUBMITTED | Xử lý draft bằng luồng staff trước, không QR auto-confirm |
| 409 | QR_ORDER_CHECKOUT_IN_PROGRESS / ORDER_HAS_FINANCIAL_OBLIGATIONS | Không gọi thêm vào phiên/order đang checkout hoặc có nghĩa vụ |
| 409 | INVALID_QR_REQUEST_TRANSITION | Terminal state không cho hành động được yêu cầu |
| 409 | IDEMPOTENCY_KEY_REUSED | Key cũ nhưng actor/scope/payload khác; kiểm tra intent |
| 409 | INVENTORY_DEFAULT_WAREHOUSE_REQUIRED / INSUFFICIENT_AVAILABLE_STOCK | Approve rollback, request vẫn PENDING; cấu hình/nhập tồn rồi review lại |
| 429 | QR_RATE_LIMIT_EXCEEDED | Tôn trọng Retry-After; không đổi identity/key để bypass |
| 413 | QR_REQUEST_BODY_TOO_LARGE | Body QR POST vượt 32768 bytes; giảm payload, không bypass qua chunked |
| 500 | INTERNAL_ERROR | Fail an toàn, không giả lỗi entitlement |

Sellability, order/tenant concurrency và security filter vẫn dùng stable codes của modules hiện có. Endpoint public dùng thông báo generic cho grant/scope/ownership invalid để tránh enumeration.

## Postman tự test

Environment `baseUrl`, staffAccessToken, qrToken, menuToken, itemId. Tokens/UUID phải lấy từ dữ liệu tenant thật đã có effective snapshot features; examples chỉ là placeholder. Không dùng production tokens trong collection chia sẻ.

1. GET menu TABLE/RESTAURANT theo link hiện có, No Auth; xác nhận chưa tạo session/order.
2. POST guest-access đúng entry, No Auth, `{}`; lưu guestToken vào biến riêng `tableGuestToken`/`takeawayGuestToken`.
3. POST requests với guest token/header key cố định + customer/items; lưu requestId. Xác nhận status PENDING; DB chưa có session/order/items mới, total bill/occupancy không đổi.
4. Retry cùng key/payload: `200`, Idempotency-Replayed true, cùng requestId. Đổi quantity với key cũ: `409`. Đổi guest token sang khách khác không được lấy receipt.
5. GET staff detail bằng Bearer, lưu request.version và reviewContextHash. TABLE chưa có phiên dùng targetSessionId null tường minh; TAKEAWAY luôn null. GET không tạo dữ liệu.
6. POST approve; TABLE tạo/append đúng phiên, TAKEAWAY tạo order riêng. GET guest receipt chỉ thấy amount/dòng riêng, không thấy root bill/PII/costPrice.
7. Replay approve cùng request sau paid/completed/session close: không thêm order/món/session/history/audit. Target khác approved session conflict.
8. Tạo hai TAKEAWAY requests cùng guest, duyệt cả hai: hai orders, không merge. Payment một order không cancel request thứ hai đang PENDING.
9. TABLE giá/context đổi sau detail: approve hash cũ `409`, không mutation. Đọc lại/chọn session đúng và xác nhận giá lại mới approve.
10. Reject request khác hai lần: REJECTED, nguyên reviewer/reason, một audit, không session/order.
11. Verify thiếu `targetSessionId`, field override, invalid limits, expired request/token, grant revoked/rotation, missing staff permissions, thiếu TABLE_OPEN khi lazy-open, and 429 + Retry-After.
12. Checkout TABLE chỉ cancel pending đã observed đúng session; close/cancel/transfer/rotate TABLE không ảnh hưởng TAKEAWAY; rotate restaurant menu không ảnh hưởng TABLE.

## Vận hành và giới hạn

Migration mới `V25__create_qr_order_requests.sql` tạo `qr_table_access` (epoch), `qr_guest_authorizations` (digest/scope/expiry), `qr_order_requests` và `qr_order_request_items`. Unique guest intent key ở cấp restaurant; composite tenant FKs/entry shape/linkage bảo vệ ownership. Không unique toàn bộ approvedOrderId vì nhiều TABLE requests được append vào cùng order. Partial unique serving order/session V19 giữ nguyên. Không sửa migration trước V25, package mapping hoặc ACTIVE subscription snapshot.

Migration kế tiếp `V26__enable_qr_ordering_for_standard_packages.sql` chỉ bổ sung hai QR ordering mapping của gói chuẩn. Không thay đổi V25, API, quyền staff, giá/maxStaff, mapping đã có hoặc subscription snapshot. Package tùy chỉnh giữ nguyên cấu hình.

Domain reuse: giữ Strategy DINE_IN/TAKEAWAY hiện có và private staff source normalizer/hash. QR submission CoR xử lý scope → normalization → replay → entry guard → quote → pending persistence; approval CoR xử lý staff authorization/lock → replay/version → review/context/branch → shared order preparation/persistence → request linkage/audit. Trusted QR approval port dùng real staff actor, không fake principal hoặc copy toàn bộ staff creation/append. Lifecycle callbacks dùng application ports và cùng tenant lock.

Detail review chỉ quan sát; business denial được thể hiện `canApprove: false`/reasonCode mà không mutation/reconcile hoặc làm read transaction rollback-only. Approval kiểm lại authoritative quote/context trong transaction locked, không dùng kết quả GET như quyền ghi dữ liệu.

Guest token/QR/TTL không chứng minh hiện diện vật lý: staff cần xác nhận đúng yêu cầu/nhóm khách trước duyệt. Không yêu cầu PIN trước gửi. Rate limiting memory bounded/configurable của MVP là single-instance; multi-instance cần shared limiter/edge protection, không dùng token làm metric label. Counter không mất vì transaction rollback.

Cấu hình tùy chọn (default khi không khai báo):

```yaml
app:
  qr-order:
    guest-access-ttl-minutes: 60
    request-ttl-minutes: 15
    rate-limit:
      mint: 30
      submit: 30
      window-seconds: 60
      max-entries: 10000
```

Mint buckets theo remote IP + entry scope, submit buckets theo guest identity + scope; không dùng forwarded IP chưa trusted. Hết quota hoặc limiter đạt tối đa live buckets trả 429/Retry-After; không evict live bucket để bypass limit. Các giới hạn phải dương.

Guest access TTL cho phép 1–1440 phút; request TTL 1–60 phút. Env overrides: `QR_GUEST_ACCESS_TTL_MINUTES`, `QR_REQUEST_TTL_MINUTES`, `QR_MINT_RATE_LIMIT`, `QR_SUBMIT_RATE_LIMIT`, `QR_RATE_WINDOW_SECONDS`, `QR_RATE_MAX_ENTRIES`. CORS expose `Idempotency-Replayed` và `Retry-After` cho origins được cấu hình; không tự cấp ordering feature qua CORS hoặc frontend.

Mask path token cho guest-access ở proxy/CDN/APM/access logs và header X-QR-Guest-Token; giữ web/security/JDBC payload tracing tắt. Website dùng Referrer-Policy no-referrer. CORS giữ origins hiện có, không wildcard; không tin forwarded IP nếu chưa cấu hình trusted proxy.

TTL không đổi stored request sang EXPIRED; `expired` projection và rule Clock quyết định. Chưa có cleanup background job. Không triển khai FE, WebSocket/outbox, guest edit/cancel, direct QR order, gateway/refund/invoice/printing/delivery/CRM. Recipe snapshot và inventory allocation chạy nội bộ khi approve; không public API nguyên liệu. Pending/history được giữ, không xóa lịch sử subscription/order.
