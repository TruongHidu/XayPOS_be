# Đồng bộ frontend: QR gọi món có nhân viên duyệt

Backend hỗ trợ hai cửa vào nhưng dùng chung một luồng **gửi yêu cầu → nhân viên xem lại → duyệt hoặc từ chối**. API đầy đủ ở [public-qr-order-api.md](public-qr-order-api.md), schema ở [OpenAPI](openapi/public-qr-order-api.yaml). Tài liệu này không triển khai frontend.

## Phân biệt ngữ cảnh

| Ngữ cảnh | Link menu đang có | Đổi lấy guest access | Order sau khi duyệt |
| --- | --- | --- | --- |
| QR bàn | `/api/v1/public/menu/tables/{qrToken}` | `POST /api/v1/public/qr-order/tables/{qrToken}/guest-access` | `DINE_IN`, `QR_TABLE`; tạo mới hoặc gọi thêm vào order đang phục vụ |
| QR chung nhà hàng | `/api/v1/public/menu/restaurants/{menuToken}` | `POST /api/v1/public/qr-order/restaurants/{menuToken}/guest-access` | Một order `TAKEAWAY`, `QR_STATIC` độc lập cho mỗi request |

Khách không chọn `entryType`, `serviceType`, `sourceChannel`, tenant, bàn hoặc phiên trong body. Backend lấy chúng từ guest authorization. Token bàn và token nhà hàng không thay thế cho nhau. Các GET menu vẫn chỉ đọc, chỉ cần `QR_MENU_VIEW`; không bắt buộc guest token, PIN, đăng nhập hoặc phiên OPEN.

## Web khách hàng

1. Đọc menu bằng GET hiện có. Giữ món OUT_OF_STOCK để gắn nhãn nhưng không cho thêm vào giỏ; backend vẫn kiểm tra lại lúc gửi và duyệt.
2. Khi khách muốn gửi, mint guest access bằng đúng cửa vào, body `{}`. Không gửi staff JWT. Giữ `guestToken` riêng với token đăng nhập nhân viên; không đưa vào URL, analytics, console, crash report hoặc storage chia sẻ.
3. Giỏ hàng chỉ là state frontend. Chọn món không mở phiên, không tạo order và không làm bàn bận.
4. Gửi `POST /api/v1/public/qr-order/requests` với `X-QR-Guest-Token`, một `Idempotency-Key` ổn định cho intent và body customer/note/items. Mang đi bắt buộc tên nhận hàng; số điện thoại tùy chọn. Ăn tại bàn có thể không nhập tên.
5. `201` là request mới; `200` với `Idempotency-Replayed: true` là kết quả intent cũ. Cả hai đều phải chuyển đến cùng receipt của `requestId`, không gửi lại bằng key mới vì timeout.
6. Hiển thị **Chờ nhà hàng xác nhận**, không hiển thị **Đã vào bếp** hoặc **Đã tính vào hóa đơn** khi status PENDING. `estimatedAmount` là báo giá lúc gửi, không phải số tiền chốt hoặc hóa đơn bàn.
7. Poll `GET /api/v1/public/qr-order/requests/{requestId}` bằng cùng guest token khi trang đang hiển thị. Dùng khoảng cách có backoff/jitter, dừng hoặc giảm polling khi app/tab background; tôn trọng `429` và `Retry-After`. Không gọi API order private để lấy bill.
8. APPROVED: hiển thị `approvedOrderCode`, `approvedAmount` và trạng thái các dòng riêng của request nếu response có. Không suy ra quyền xem toàn bộ order/table bill. REJECTED/CANCELLED/expired: hiển thị thông báo phù hợp; không tự submit lại.

`expired` chỉ là projection thời hạn ban đầu đã đến, có thể true cả trên request terminal. Ưu tiên APPROVED/REJECTED/CANCELLED theo status; chỉ hiển thị “Yêu cầu chờ duyệt đã hết hạn” khi status còn PENDING và expired=true. Không biến receipt APPROVED thành thất bại vì deadline đã qua.

Guest request tối đa 20 dòng, quantity > 0 và <= 20, tối đa ba chữ số thập phân; ghi chú chung/dòng <= 500 ký tự; tên <= 100; phone <= 30. Không gửi giá, trạng thái món hoặc tổng tiền. Dùng decimal hoặc chuỗi tính toán an toàn khi trình bày tiền; tiền backend là authoritative.

Đổi giỏ/name/phone/note sau một submission thành công là intent mới, cần key mới. Nếu lỗi mạng chưa rõ kết quả, retry **cùng key và cùng payload đã chuẩn hóa**, không sửa payload rồi dùng lại key. Token hết hạn/rotate thì không âm thầm mint token mới rồi retry key cũ: guest mới không được phép lấy request của guest cũ. Hướng dẫn khách kiểm tra receipt hiện có hoặc gửi intent mới có xác nhận rõ ràng để tránh gọi món trùng.

MVP không có guest sửa/hủy request hoặc món, approve/reject, mở/đóng/chuyển bàn, thanh toán hay complete. Đối với TAKEAWAY, gửi request thứ hai là mua thêm một order độc lập, không phải gọi thêm vào order đầu và không được auto-merge theo tên/phone.

## App/web nhân viên

Thêm inbox QR riêng; không dùng order OPEN hoặc dòng món PENDING làm hàng đợi QR. List/detail vẫn cần staff JWT, tenant và `ORDER_VIEW`. Review/reject không đòi grant QR ordering còn hiệu lực, để xử lý backlog sau khi quyền lợi bị thu hồi; feature vận hành và security tenant vẫn áp dụng.

1. Đọc `/api/v1/qr-order-requests` để lấy hàng đợi. Filter theo `entryType`, `status`, `tableId`, `observedSessionId`, page/size. Queue mặc định bỏ request hết hạn hoặc scope đã vô hiệu. Đọc history bằng filter status tường minh.
2. Mở `/api/v1/qr-order-requests/{requestId}` trước mỗi lần quyết định. Hiển thị submitted quote và current quote cạnh nhau, tên/phone/note/món, TTL, `canApprove`/`reasonCode` cùng ngữ cảnh bàn nếu là TABLE.
3. Chỉ gửi approve khi người dùng đã xem current quote/context. Lấy `expectedRequestVersion` từ QR request, không từ Order.version; gửi nguyên `expectedReviewContextHash` của response vừa xem.
4. Body approve luôn chứa property `targetSessionId`, kể cả null. Missing không có nghĩa là null.

| Entry / context đã review | `targetSessionId` | Hành vi |
| --- | --- | --- |
| RESTAURANT | `null` tường minh | Tạo TAKEAWAY độc lập; không hỏi người dùng chọn bàn |
| TABLE chưa có phiên hiện tại | `null` tường minh | Duyệt, mở phiên bằng staff thật và tạo order cùng transaction |
| TABLE có phiên S phù hợp | ID S tường minh | Tạo order nếu chưa có hoặc append vào order hiện tại đủ điều kiện |
| TABLE quan sát phiên S lúc gửi | Chỉ ID S | Không chuyển request sang phiên/nhóm khách khác |

Approve cần `ORDER_CREATE` và `ORDER_UPDATE`. Nếu thực sự mở phiên TABLE mới, cần thêm `TABLE_OPEN`; không yêu cầu permission/feature TABLE cho QR mang đi. Không hardcode OWNER/WAITER/CASHIER; render theo permissions hiệu lực và backend `canApprove`, nhưng backend luôn quyết định cuối cùng.

Nếu request TABLE chưa quan sát phiên lúc gửi nhưng hiện đã có S, staff phải review lại và tự chọn S sau khi xác nhận đúng nhóm khách. Không tự chọn phiên latest/first. Nếu root order OPEN draft, không auto-confirm draft qua QR; xử lý draft bằng luồng staff riêng rồi review lại. Nếu checkout/nghĩa vụ thanh toán đã bắt đầu, không gọi thêm.

## Xử lý xung đột và replay

- `QR_REVIEW_CONTEXT_CHANGED`: tải lại detail, hiển thị phần thay đổi và yêu cầu nhân viên xác nhận lần nữa. Không tự thay hash rồi tự approve.
- `CONCURRENT_QR_REQUEST_UPDATE`: tải lại request. Nếu người khác đã xử lý, hiển thị kết quả đó; không chuyển intent sang order/phiên khác.
- `QR_REQUEST_SESSION_CHANGED`: hiển thị phiên/bàn đã đổi hoặc kết thúc; không tự gắn request cũ vào lượt khách mới.
- `QR_ORDER_REQUEST_EXPIRED`: request không duyệt được dù stored status vẫn PENDING; dùng `expired` và `canApprove` để thể hiện. Không đợi job dọn dữ liệu.
- APPROVED retry trả kết quả approval đã lưu, kể cả order đã PAID/COMPLETED hoặc phiên đã CLOSED. Không tạo order/món/phiên lần hai; không đổi người/thời gian duyệt.
- REJECTED retry là no-op, giữ reason/reviewer ban đầu. Reject cần `ORDER_UPDATE`, body expectedRequestVersion và reason bắt buộc.
- `IDEMPOTENCY_KEY_REUSED`: không tự đổi key và gửi lại; đây là payload/actor/scope khác sử dụng một key cũ, cần kiểm tra intent trước.

## Checkout và kết thúc lượt bàn

MVP hiện có payment SUCCESS thủ công, chưa có API PENDING payment intent/provider.
Callback hủy request chạy tại settlement thành công; adapter intent tương lai phải gọi cùng port
trong transaction tạo intent. FE chỉ sử dụng API payment hiện có, không giả endpoint intent mới.

Trước checkout, staff nên cảnh báo các QR requests PENDING **đã quan sát đúng session đang trả tiền**. Payment intent tạo thành công sẽ cancel đúng các request đó với reason `CHECKOUT_STARTED`; request observedSessionId null không tự bị gán vào phiên chỉ vì cùng bàn. Payment rollback giữ cancellation rollback cùng transaction. Payment fail/cancel không hồi sinh request CANCELLED; khi không còn nghĩa vụ có thể nhận intent mới.

Đóng/hủy/chuyển phiên và rotate QR bàn làm invalid scope/epoch của lượt bàn và cancel requests PENDING bị ảnh hưởng. Không ảnh hưởng QR mang đi. Rotate menuToken nhà hàng vô hiệu grant và request PENDING RESTAURANT của credential cũ, không hủy order APPROVED/payment và không ảnh hưởng QR_TABLE.

Không chặn payment chỉ vì chưa SERVED hết. Giữ payment trước/sau phục vụ; API complete vẫn kiểm thanh toán đủ + mọi món hiệu lực SERVED và tự đóng phiên DINE_IN trong cùng transaction. TAKEAWAY chỉ complete order đó, không tác động request mang đi khác của cùng guest/restaurant.

## Security và vận hành

Luôn `Cache-Control: no-store`; không cache service-worker/CDN các response token/request/review. CORS chỉ giữ origins cấu hình và thêm headers được hỗ trợ, không đổi wildcard. Mask token segment và `X-QR-Guest-Token` ở proxy/APM/access logs; dùng website `Referrer-Policy: no-referrer`. Không ghi customer body, idempotency key, hash hoặc secrets vào analytics.

Guest token/QR/TTL/rate limit không chứng minh khách hiện diện vật lý. Staff cần xác nhận yêu cầu, đặc biệt QR bàn công khai và request chưa quan sát phiên. Rate limiter memory của MVP chỉ áp dụng một instance; scale nhiều instance cần shared limiter/edge protection. Chưa có WebSocket: dùng polling hợp lý, không giả realtime.

Feature phải lấy từ immutable subscription snapshot effective. TABLE ordering cần `QR_MENU_VIEW`, `QR_TABLE_ORDER`, `ORDER_MANAGEMENT`, `TABLE_MANAGEMENT`; RESTAURANT cần `QR_MENU_VIEW`, `QR_STATIC_ORDER`, `ORDER_MANAGEMENT`. V26 bổ sung hai QR ordering feature vào catalog BASIC/PRO/PREMIUM nhưng không sửa snapshot ACTIVE cũ hoặc package tùy chỉnh. Không suy quyền từ tên gói hoặc việc catalog vừa cập nhật. Staff TAKEAWAY API cũ vẫn yêu cầu `POS_QUICK_ORDER`; QR_STATIC có gate riêng, không bỏ gate staff để frontend QR chạy được.
