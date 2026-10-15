# KDS, hoàn tất order và đóng phiên bàn

Luồng KDS là **SUBMIT (món PENDING) → COOKING → READY → SERVED → COMPLETED → CLOSED**. Khi không dùng màn bếp, bulk mark-served có thể xác nhận trực tiếp **PENDING/COOKING/READY → SERVED** sau khi món thực sự được phục vụ; không cần chế độ vận hành riêng và không tạo bước nấu giả trong lịch sử. `PAID` là trạng thái thanh toán riêng: có thể thanh toán khi món vẫn `PENDING/COOKING/READY`; bếp tiếp tục làm sau khi đã PAID. Thanh toán không tự complete order. Từ V24, complete DINE_IN hoàn tất order và đóng đúng phiên trong cùng transaction; TAKEAWAY cũng đánh dấu `SERVED` khi đã giao món rồi complete, nhưng không có session để đóng.

Tất cả API dùng `Authorization: Bearer <accessToken>`, tenant từ JWT, `Cache-Control: no-store`. Mutation JSON cần `Content-Type: application/json`. Dùng quyền hiệu lực (gồm GRANT/DENY), không hard-code role. KDS cần snapshot `KITCHEN_DISPLAY`; mark-served/complete cần `ORDER_MANAGEMENT`; complete DINE_IN thêm `TABLE_MANAGEMENT`, giống close riêng.

| API | Permission | Body | Thành công |
| --- | --- | --- | --- |
| `GET /api/v1/kitchen/items?status=PENDING&page=0&size=20` | `KITCHEN_VIEW` | Không | `200 PageResponse<KitchenQueueItemResponse>` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/start-cooking` | `KITCHEN_START_COOKING` | `{"expectedOrderVersion":3}` | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/mark-ready` | `KITCHEN_MARK_READY` | Như trên | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/items/{orderItemId}/mark-served` | `ORDER_MARK_SERVED` | Như trên | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/mark-ready` | `KITCHEN_MARK_READY` | `{"expectedOrderVersion":7}` | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/mark-served` | `ORDER_MARK_SERVED` | `{"expectedOrderVersion":7}` | `200 OrderResponse` |
| `POST /api/v1/orders/{orderId}/complete` | `ORDER_COMPLETE`; DINE_IN thêm `TABLE_CLOSE` | `{"expectedVersion":7}` của ORDER | `200 OrderResponse`; DINE_IN đóng đúng phiên cùng transaction |
| `POST /api/v1/table-sessions/{sessionId}/close` | `TABLE_CLOSE` | `{"expectedVersion":4}` | `200 TableSessionResponse` |

KDS chỉ trả item của order CONFIRMED/PREPARING/READY/SERVED, kể cả PAID; không trả draft OPEN, COMPLETED/CANCELLED, giá vốn, số điện thoại khách hoặc QR token. `status` chỉ nhận PENDING/COOKING/READY (mặc định PENDING), `size` 1–100. `sentToKitchenAt` được set một lần khi SUBMIT/confirm; hàng cũ có NULL vẫn được xếp hàng theo `createdAt`. Tên/mã bàn lấy từ session hiện tại, nên phản ánh bàn mới sau transfer. Ví dụ response:

```json
{"content":[{"orderId":"11111111-1111-4111-8111-111111111111","orderCode":"ORD-01","orderVersion":3,"orderItemId":"22222222-2222-4222-8222-222222222222","itemName":"Phở bò","quantity":2.000,"unit":"bowl","note":"Không hành","itemStatus":"PENDING","serviceType":"DINE_IN","createdAt":"2026-10-13T01:00:00Z","sentToKitchenAt":"2026-10-13T01:01:00Z","sessionId":"33333333-3333-4333-8333-333333333333","tableId":"44444444-4444-4444-8444-444444444444","tableCode":"B01","tableName":"Bàn 01"}],"page":0,"size":20,"totalElements":1,"totalPages":1}
```

Từng món chỉ chuyển `PENDING → COOKING → READY → SERVED`, không nhảy/lùi bước. Gọi lại đúng bước đã đạt là no-op (không tăng version, audit hoặc history); gọi lại bước đã vượt qua trả `409 INVALID_ORDER_ITEM_TRANSITION`. `expectedOrderVersion` phải là version từ response mới nhất khi thực sự đổi trạng thái. Mỗi bước ghi đúng một history append-only và một audit `ORDER_ITEM_COOKING/READY/SERVED`. Trạng thái order được tổng hợp từ các dòng chưa CANCELLED: tất cả PENDING → CONFIRMED; còn PENDING/COOKING và đã có tiến triển → PREPARING; không còn PENDING/COOKING nhưng có READY → READY; tất cả SERVED → SERVED.

## Cập nhật tất cả món theo đơn

Hai endpoint theo đơn không có chế độ vận hành riêng. Bulk mark-ready vẫn tuân thủ COOKING → READY; bulk mark-served cho phép PENDING/COOKING/READY → SERVED, khác API mark-served từng món vẫn chỉ READY → SERVED. Áp dụng cho cả DINE_IN và TAKEAWAY. “Món hiệu lực” là dòng có status khác CANCELLED; đơn phải có ít nhất một dòng hiệu lực và root đang CONFIRMED/PREPARING/READY/SERVED. OPEN chưa gửi bếp và root COMPLETED/CANCELLED bị từ chối, kể cả mọi dòng đã đạt trạng thái đích.

| API | Permission hiệu lực | Feature trong subscription snapshot |
| --- | --- | --- |
| `POST /api/v1/orders/{orderId}/mark-ready` | `KITCHEN_MARK_READY` | `KITCHEN_DISPLAY` |
| `POST /api/v1/orders/{orderId}/mark-served` | `ORDER_MARK_SERVED` | `ORDER_MANAGEMENT` |

Migration V23 bổ sung hai permission hiện có cho role hệ thống CASHIER và KITCHEN. Không cấp thêm KITCHEN_START_COOKING, ORDER_COMPLETE hoặc TABLE_CLOSE trong migration này, không sửa role tenant-custom hoặc user GRANT/DENY. CASHIER và mọi actor khác có quyền hiệu lực phù hợp được gọi bulk mark-served chỉ với ORDER_MARK_SERVED + ORDER_MANAGEMENT, kể cả món còn PENDING/COOKING; không cần KITCHEN_DISPLAY hoặc các API bếp trước đó. Khi muốn dùng mark-ready, món PENDING vẫn phải được người có KITCHEN_START_COOKING chuyển sang COOKING qua API từng món. DENY của user vẫn thắng. Sau migration cần login/refresh để JWT nhận role grants mới. Grant permission không bổ sung feature vào snapshot của tenant. Thay đổi direct bulk serving không thêm migration, permission hoặc chế độ mới.

Headers cho cả hai request:

```http
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json
```

Tenant lấy từ JWT, không có restaurantId hoặc tenant header. Không cần Idempotency-Key; retry dựa trên trạng thái hiện tại và expectedOrderVersion. Body không nhận itemId hoặc status tùy ý:

```json
{
  "expectedOrderVersion": 7
}
```

expectedOrderVersion bắt buộc, là số nguyên không âm và là Order.version, không phải version món hay phiên bàn. Dùng đúng version mới nhất khi thực sự thay đổi món.

| Trạng thái món hiện tại | mark-ready | mark-served |
| --- | --- | --- |
| PENDING | Từ chối toàn bộ request | Chuyển SERVED |
| COOKING | Chuyển READY | Chuyển SERVED |
| READY | Giữ nguyên | Chuyển SERVED |
| SERVED | Giữ nguyên, không chuyển lùi | Giữ nguyên |
| CANCELLED | Bỏ qua | Bỏ qua |

mark-ready kiểm tra toàn bộ dòng trước khi đổi COOKING → READY. Ví dụ `[COOKING, READY, SERVED, CANCELLED]` thành `[READY, READY, SERVED, CANCELLED]`. Nếu có một PENDING thì trả 409, mọi dòng và root giữ nguyên. Khi toàn bộ dòng hiệu lực đã READY/SERVED thì trả 200 no-op; order toàn SERVED vẫn giữ SERVED.

mark-served đổi tất cả dòng PENDING/COOKING/READY → SERVED để xác nhận đã giao món, giữ SERVED và bỏ qua CANCELLED. Ví dụ `[PENDING, COOKING, READY, SERVED, CANCELLED]` thành `[SERVED, SERVED, SERVED, SERVED, CANCELLED]`; root thành SERVED. Khi mọi dòng hiệu lực đã SERVED thì trả 200 no-op. API không tự tạo các bước COOKING/READY hoặc set lại sentToKitchenAt. Endpoint từng món vẫn chỉ READY → SERVED, phù hợp phục vụ từng phần khi những món khác chưa xong.

Mỗi request dùng một transaction và restaurant pessimistic lock theo thứ tự mutation hiện có. Một command có thay đổi cập nhật root một lần, tăng aggregate version/mutationSequence một lần, tổng hợp order.status từ các dòng hiệu lực, ghi một history cho mỗi dòng thực sự đổi và một audit `ORDER_ITEMS_MARKED_READY` hoặc `ORDER_ITEMS_MARKED_SERVED`. History bulk phục vụ dùng đúng trạng thái nguồn từng dòng: PENDING → SERVED, COOKING → SERVED hoặc READY → SERVED. Dòng đã đạt/vượt mục tiêu và CANCELLED không có history mới. Lỗi giữa transaction rollback toàn bộ.

No-op được nhận diện sau kiểm tra tenant, permission, feature, trạng thái root và toàn bộ dòng, trước kiểm version. Vì vậy retry bằng version cũ có thể trả 200 khi mục tiêu đã được thỏa mãn; version/mutationSequence, updatedAt, audit và history không thay đổi. Nếu còn dòng cần chuyển, version cũ trả 409 CONCURRENT_ORDER_UPDATE. Nếu sau lần gửi cũ đã phát sinh món PENDING, retry mark-ready bị transition policy chặn; retry mark-served cần mutation nên version cũ trả CONCURRENT_ORDER_UPDATE, không tự phục vụ món mới bằng ý định cũ. No-op không cho bỏ qua root COMPLETED/CANCELLED.

Đơn đã PAID vẫn được cập nhật món nếu root còn phục vụ. Hai endpoint giữ nguyên paymentStatus, paidAmount, mọi amount, quantity, snapshot tên/đơn vị/giá, customer, tableSessionId và sentToKitchenAt. Không đọc giá menu để tính lại tiền. mark-served không tự complete order hoặc đóng phiên; gọi complete khi đã thỏa điều kiện. Với DINE_IN, complete sẽ đóng phiên cùng transaction, không cần request close riêng.

Response thành công là `200 OrderResponse` hiện tại và `Cache-Control: no-store`, không bọc envelope. Ví dụ mark-ready chuyển một dòng COOKING thành READY, request version 7:

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "orderCode": "OD-EXAMPLE",
  "serviceType": "DINE_IN",
  "sourceChannel": "WAITER",
  "tableSessionId": "11111111-1111-4111-8111-111111111111",
  "status": "READY",
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
  "confirmedAt": "2026-10-13T01:00:00Z",
  "completedAt": null,
  "cancelledAt": null,
  "cancelReason": null,
  "createdAt": "2026-10-13T01:00:00Z",
  "updatedAt": "2026-10-13T01:05:00Z",
  "version": 8,
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
      "status": "READY",
      "note": "Không hành",
      "cancelReason": null,
      "sentToKitchenAt": "2026-10-13T01:00:00Z"
    }
  ]
}
```

Gọi mark-served sau đó với expectedOrderVersion 8 trả cùng shape, order/item status SERVED, version 9 và updatedAt từ Clock. completedAt vẫn null. TAKEAWAY dùng cùng response với tableSessionId null. UUID/mã/version trong ví dụ chỉ minh họa; luôn dùng response thực tế.

| HTTP | Code/tình huống cho hai endpoint |
| --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY: body thiếu/sai/âm expectedOrderVersion |
| 401 | UNAUTHORIZED / ACCOUNT_INACTIVE theo security guard |
| 403 | FORBIDDEN, TENANT_ACCESS_DENIED, RESTAURANT_INACTIVE, SUBSCRIPTION_NOT_ACTIVE, FEATURE_NOT_ENTITLED |
| 404 | ORDER_NOT_FOUND: missing hoặc order tenant khác |
| 409 | INVALID_ORDER_ITEM_TRANSITION: mark-ready còn PENDING hoặc root OPEN/COMPLETED/CANCELLED; bulk mark-served không chặn vì PENDING/COOKING |
| 409 | EMPTY_ORDER: không có dòng hiệu lực |
| 409 | CONCURRENT_ORDER_UPDATE: version cũ khi còn cần mutation hoặc locking conflict |

Ví dụ lỗi, message chỉ minh họa; FE quyết định theo HTTP/code:

```json
{
  "success": false,
  "code": "INVALID_ORDER_ITEM_TRANSITION",
  "message": "Order still has pending items; start cooking before marking all items ready",
  "fieldErrors": {},
  "timestamp": "2026-10-13T01:05:00Z"
}
```

## Postman và đồng bộ FE cho bulk actions

1. Đặt baseUrl, accessToken, orderId; login/refresh sau V23. Dùng tenant có feature yêu cầu. Để đọc chi tiết cần ORDER_VIEW + ORDER_MANAGEMENT; start-cooking cần KITCHEN_START_COOKING + KITCHEN_DISPLAY.
2. Tạo đơn SUBMIT hoặc confirm draft, lưu items[].id và version. Gọi mark-ready lúc dòng còn PENDING để kiểm 409 và GET lại xác nhận không dòng nào đổi.
3. Gọi start-cooking từng dòng còn PENDING, mỗi request gửi expectedOrderVersion từ response mới nhất. Không có API bulk start-cooking.
4. Khi không còn PENDING, POST `{{baseUrl}}/api/v1/orders/{{orderId}}/mark-ready` với version mới nhất; kiểm chỉ COOKING đổi READY và root tổng hợp đúng. Nếu có CANCELLED/SERVED, các dòng đó giữ nguyên.
5. Gửi lại request bước 4 với version cũ để kiểm 200 no-op, version và updatedAt không đổi. Có thể đối chiếu DB history/audit để chắc không ghi trùng.
6. POST `{{baseUrl}}/api/v1/orders/{{orderId}}/mark-served` với version từ bước 4; kiểm READY đổi SERVED, tiền giữ nguyên, completedAt vẫn null và session vẫn OPEN.
7. Retry mark-served cùng body để kiểm no-op. Dùng một đơn SUBMIT khác còn PENDING/COOKING/READY, gọi bulk mark-served với version mới nhất để kiểm 200, mọi dòng hiệu lực thành SERVED và history lưu đúng từng nguồn; không cần gọi API bếp. Có thể dùng token chỉ có ORDER_MARK_SERVED và tenant chỉ có ORDER_MANAGEMENT để kiểm không phụ thuộc KITCHEN_DISPLAY. Dùng version cũ khi còn món cần đổi để kiểm CONCURRENT_ORDER_UPDATE và không dòng nào đổi; sau append PENDING mới cũng phải bị version cũ chặn. API mark-served từng món với nguồn PENDING/COOKING vẫn trả 409.
8. Thử thiếu permission, user DENY, thiếu feature và order tenant khác. Bulk no-op cũng phải bị chặn bởi những guard này.
9. Có thể thanh toán trước bước 3–6; các transition vẫn thành công khi PAID và root chưa terminal. Khi mọi món hiệu lực SERVED và đã thanh toán đủ, gọi complete; DINE_IN sẽ đóng phiên trong cùng request, TAKEAWAY chỉ complete. Sau complete, cả hai bulk endpoints trả 409.

FE hiện “Xong tất cả” khi có KITCHEN_MARK_READY + KITCHEN_DISPLAY, root đang phục vụ, có dòng COOKING và không có PENDING. Hiện “Đã phục vụ tất cả” khi có ORDER_MARK_SERVED + ORDER_MANAGEMENT, root đang phục vụ và có ít nhất một dòng PENDING/COOKING/READY; không chặn nút vì chưa COOKING/READY, không kiểm thêm feature bếp hoặc role CASHIER. Tính điều kiện trên danh sách loại CANCELLED; đơn không có dòng hiệu lực hoặc tất cả đã SERVED không cần thao tác bulk. Nút này là xác nhận món đã thực sự được phục vụ, không phải nút thanh toán/complete.

Disable nút trong lúc request chạy; gửi Order.version hiện tại và thay state bằng OrderResponse backend trả. Khi 409, tải lại đơn và để người dùng kiểm tra trước thao tác lại. Không tự tăng version, không tự chuyển trạng thái mọi dòng để giả lập thành công, không tự complete/close. API từng món dùng cho các bước hoặc lần phục vụ riêng lẻ. Permission/feature ở UI hỗ trợ hiển thị; backend là nơi quyết định cuối cùng.

Order chưa PAID có thể gọi thêm bằng `POST /api/v1/orders/{orderId}/items` với `submissionMode: "SUBMIT"`, `Idempotency-Key` và `expectedVersion` khi root đã CONFIRMED/PREPARING/READY/SERVED. Dòng mới PENDING với snapshot giá/tên/đơn vị mới; root READY/SERVED có thể về PREPARING. Không thêm DRAFT khi root đã được gửi bếp. Chỉ dòng PENDING chưa thanh toán mới được hủy; không nới điều kiện hủy toàn order. Đã PAID thì không gọi thêm, chỉnh giá/số lượng hoặc hủy order.

## Hoàn tất đơn và đóng phiên tự động

```http
POST /api/v1/orders/{orderId}/complete
Authorization: Bearer <accessToken>
Content-Type: application/json
Accept: application/json
```

```json
{
  "expectedVersion": 9
}
```

expectedVersion bắt buộc, không âm và là **version ORDER** mới nhất; khác expectedOrderVersion của API cập nhật món. Body không có sessionId, tableId, expectedSessionVersion, paymentStatus hoặc amount. Không cần Idempotency-Key. Tenant lấy từ JWT.

| Loại đơn | Permission hiệu lực | Feature snapshot | Kết quả |
| --- | --- | --- | --- |
| DINE_IN | ORDER_COMPLETE + TABLE_CLOSE | ORDER_MANAGEMENT + TABLE_MANAGEMENT | Order COMPLETED và đúng order.tableSessionId CLOSED, atomic |
| TAKEAWAY | ORDER_COMPLETE | ORDER_MANAGEMENT | Chỉ order COMPLETED; không kiểm quyền/feature TABLE hoặc gọi TABLE port |

Lần complete đầu yêu cầu submitted order còn phục vụ, không OPEN/CANCELLED; `paymentStatus=PAID`, `paidAmount=totalAmount>0`, có ít nhất một dòng chưa CANCELLED và mọi dòng đó đều SERVED. Dòng CANCELLED được bỏ qua, không đổi trạng thái/snapshot. Còn PENDING/COOKING/READY, chưa thanh toán đủ hoặc tổng bằng 0 thì 409; không tự cập nhật món, tạo payment hoặc reprice menu.

DINE_IN phải có đúng session tồn tại/cùng tenant và OPEN. TABLE kiểm toàn bộ order của phiên sau khi order được flush COMPLETED: ít nhất một COMPLETED/PAID/đủ tiền; các order khác phải COMPLETED/PAID/đủ tiền hoặc CANCELLED/UNPAID/paidAmount=0. Còn serving order hoặc nghĩa vụ tài chính thì closure bị chặn và completion rollback.

Một transaction giữ restaurant lock tới commit và bao gồm order, session và cả hai audit `ORDER_COMPLETED`, `TABLE_SESSION_CLOSED`. Order version/mutationSequence và session version tăng một lần; completedAt/closedAt từ Clock. Audit/persistence/closure thất bại rollback tất cả. Không ghi item history vì không có món bị đổi trạng thái.

Response vẫn `200 OrderResponse` đầy đủ và `Cache-Control: no-store`, cùng schema đã minh họa ở trên; các field chính sau hoàn tất có dạng sau (đây là trích đoạn, không phải response rút gọn):

```json
{
  "id": "33333333-3333-4333-8333-333333333333",
  "serviceType": "DINE_IN",
  "tableSessionId": "11111111-1111-4111-8111-111111111111",
  "status": "COMPLETED",
  "paymentStatus": "PAID",
  "totalAmount": 100000.00,
  "paidAmount": 100000.00,
  "completedAt": "2026-10-14T01:05:00Z",
  "updatedAt": "2026-10-14T01:05:00Z",
  "version": 10
}
```

Response không thêm session object/version. Với DINE_IN, 200 bảo đảm đúng session đã CLOSED; muốn hiển thị session/occupancy mới nhất thì reload query bàn/session theo quyền đọc hiện có. TAKEAWAY trả tableSessionId null. Không thay tiền, snapshot món hoặc payment records.

| State khi retry | Hành vi sau kiểm tenant/permission/feature |
| --- | --- |
| TAKEAWAY COMPLETED | 200 no-op kể cả version cũ; không thay version/timestamp/audit |
| DINE_IN COMPLETED + đúng session CLOSED | 200 no-op kể cả version cũ; không ghi lại completion/closure |
| Legacy DINE_IN COMPLETED + đúng session OPEN | Kiểm lại tiền, món SERVED và tất cả order của phiên; nếu đủ điều kiện chỉ đóng session, giữ order version/mutationSequence/completedAt và chỉ ghi TABLE_SESSION_CLOSED |
| Legacy COMPLETED + OPEN còn order khác đang phục vụ/tài chính chưa giải quyết | 409 TABLE_SESSION_NOT_READY_TO_CLOSE, giữ nguyên dữ liệu |
| Order chưa COMPLETED + session CLOSED, hoặc session CANCELLED | 409 TABLE_SESSION_NOT_OPEN; không mở lại hoặc đổi CANCELLED thành CLOSED |

Closure chỉ dùng order.tableSessionId, không tìm phiên mới theo tableId. Nếu phiên đã transfer, đóng đúng session tại bàn hiện tại, giữ order linkage/QR/history. Retry order cũ khi bàn đã có phiên mới không đóng phiên mới. Không sửa status cấu hình AVAILABLE/INACTIVE; occupancy được suy từ OPEN session. Bàn hoặc area inactive sau khi mở không thêm điều kiện chặn closure.

V24 cấp TABLE_CLOSE cho global system CASHIER/MANAGER; không thay custom roles, user GRANT/DENY hoặc feature snapshot, không cấp ORDER_COMPLETE cho KITCHEN. TABLE_CLOSE cũng cho phép manual close/cancel theo business rule riêng; DENY TABLE_CLOSE vẫn chặn complete DINE_IN. Login/refresh để token nhận quyền mới. Tất cả guard vẫn áp dụng trước nhánh no-op hoặc repair.

| HTTP | Completion error |
| --- | --- |
| 400 | VALIDATION_ERROR / INVALID_REQUEST_BODY: thiếu/sai/âm expectedVersion |
| 401 | UNAUTHORIZED / ACCOUNT_INACTIVE |
| 403 | FORBIDDEN / FEATURE_NOT_ENTITLED và account/restaurant/subscription/tenant guards |
| 404 | ORDER_NOT_FOUND / TABLE_SESSION_NOT_FOUND; thiếu hoặc khác tenant |
| 409 | ORDER_NOT_PAID: chưa PAID hoặc paidAmount không bằng totalAmount > 0 |
| 409 | ORDER_NOT_READY_FOR_COMPLETION: order OPEN/CANCELLED, không có món hiệu lực hoặc còn món chưa SERVED |
| 409 | INVALID_ORDER_SEATING: DINE_IN không có tableSessionId |
| 409 | TABLE_SESSION_NOT_OPEN: session terminal không hợp lệ cho completion |
| 409 | TABLE_SESSION_NOT_READY_TO_CLOSE: các order trong phiên chưa terminal/settled |
| 409 | CONCURRENT_ORDER_UPDATE / CONCURRENT_TABLE_UPDATE theo business/locking conflict tương ứng |
| 500 | INTERNAL_ERROR; transaction rollback, không trả SQL/exception details |

### Postman complete và kiểm chứng closure

1. Login/refresh sau V24, dùng tenant có feature phù hợp. Lưu orderId, tableSessionId và version thực tế từ response; các API GET kiểm chứng cần ORDER_VIEW/TABLE_VIEW và feature tương ứng.
2. Tạo DINE_IN SUBMIT. Thử complete trước thanh toán/phục vụ xong: 409, GET lại xác nhận order chưa COMPLETED và session vẫn OPEN.
3. Nếu dùng KDS, chuyển món qua start-cooking, mark-ready, mark-served. Nếu không dùng màn bếp, khi đã phục vụ thực tế gọi bulk mark-served trực tiếp từ PENDING/COOKING/READY. Luôn lấy version mới nhất. Thanh toán đủ qua PAYMENT MVP; thanh toán có thể diễn ra trước hoặc sau các bước món.
4. POST complete với expectedVersion mới nhất. Kiểm OrderResponse COMPLETED/PAID, completedAt có giá trị, tiền/snapshot giữ nguyên; GET session xác nhận CLOSED/closedAt/closedBy, GET bàn không còn OPEN session cũ.
5. Retry chính body bước 4 có version cũ: 200, order/session version và timestamps giữ nguyên, audit không tăng.
6. Gọi API manual close sau auto-close với body version session: 200 no-op. Không cần request này trong luồng FE thông thường.
7. Tạo TAKEAWAY với user chỉ có ORDER_COMPLETE và ORDER_MANAGEMENT cho completion: complete thành công mà không cần TABLE_CLOSE/TABLE_MANAGEMENT.
8. DINE_IN thiếu TABLE_CLOSE, user DENY hoặc thiếu TABLE_MANAGEMENT: 403 trước mutation/no-op. Thử version cũ khi order còn cần complete: 409 CONCURRENT_ORDER_UPDATE.
9. Với fixture test riêng, thử legacy COMPLETED+OPEN: complete chỉ đóng session, không tăng order version/ghi ORDER_COMPLETED lại. Có order khác chưa kết thúc thì 409, không đóng phiên. Không sửa trực tiếp dữ liệu production để tạo fixture.
10. Sau phiên cũ CLOSED và bàn có phiên mới, retry order cũ: phiên mới vẫn OPEN. Trường hợp transfer cũng đóng đúng sessionId gốc tại bàn mới, không đổi QR/config/history.

FE giữ endpoint/body cũ; với DINE_IN kiểm ORDER_COMPLETE + TABLE_CLOSE và ORDER_MANAGEMENT + TABLE_MANAGEMENT, TAKEAWAY chỉ kiểm ORDER_COMPLETE + ORDER_MANAGEMENT. Disable nút lúc gửi; sau 200 thay state bằng OrderResponse và reload bàn/session. Không gọi close HTTP thứ hai cho DINE_IN; không tự giả lập CLOSED khi request thất bại. Khi 409 reload dữ liệu và để người dùng kiểm tra. [Handoff FE](order-completion-session-fe-sync.md).

## Đóng phiên riêng

`POST /api/v1/table-sessions/{sessionId}/close` vẫn yêu cầu TABLE_CLOSE + TABLE_MANAGEMENT. Body `{"expectedVersion":4}` là **version SESSION**. Close chỉ thành công với session OPEN có ít nhất một order COMPLETED/PAID/đủ tiền và mọi order liên quan đều terminal/settled. Session rỗng hoặc chỉ có order CANCELLED phải dùng cancel session. Close trả `200 TableSessionResponse` có status CLOSED, closedAt/closedBy; gọi lại CLOSED là no-op kể cả version cũ, không ghi audit trùng. V24 cấp default TABLE_CLOSE cho CASHIER/MANAGER; quyền hiệu lực và user DENY vẫn quyết định. Session CLOSED không được update/transfer/cancel/mở lại. API riêng phù hợp luồng thủ công/legacy, không cần gọi thêm sau complete DINE_IN thành công.

Lỗi dùng format `{"success":false,"code":"...","message":"...","fieldErrors":{},"timestamp":"..."}`. `400` body sai, `401` chưa đăng nhập, `403` thiếu permission/feature, `404` ID khác tenant/không tồn tại, `409` trạng thái hoặc version xung đột. Các code chính: `INVALID_ORDER_ITEM_TRANSITION`, `EMPTY_ORDER`, `ORDER_NOT_READY_FOR_COMPLETION`, `ORDER_NOT_PAID`, `INVALID_ORDER_SEATING`, `TABLE_SESSION_NOT_READY_TO_CLOSE`, `TABLE_SESSION_NOT_OPEN`, `CONCURRENT_ORDER_UPDATE`, `CONCURRENT_TABLE_UPDATE`, `FEATURE_NOT_ENTITLED`.

Chưa triển khai WebSocket/realtime, QR khách tự đặt món, SePay, refund, recipe/inventory deduction hoặc in phiếu bếp. Xem [OpenAPI](openapi/kitchen-lifecycle-api.yaml), [order-api.md](order-api.md), [payment-api.md](payment-api.md).
