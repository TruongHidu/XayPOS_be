# Test plan — subscription purchase (Phase2 / phần còn lại PLANNED)

Ngày chốt2026-11-01. Phase1 chỉ kiểm tra tài liệu. Phase2 bổ sung SubscriptionPurchaseIntegrationTest và regression/Flyway; kết quả thực tế được ghi ở mục xác minh Phase2 sau khi chạy Maven. Các case Phase3–6 vẫn NOT RUN; case kết hợp nhiều phase chỉ kiểm tra phần Phase2/fixture, không xác nhận payment/provider đã chạy. Nguồn: [thiết kế](subscription-purchase-design.md), [OpenAPI](openapi/subscription-purchase-api.yaml), [FE sync](subscription-purchase-fe-sync.md).

## Fixture và cách thực hiện về sau

PostgreSQL thật qua integration setup hiện có, schema cô lập, không dùng H2 cho partial unique/locks. Clock.fixed UTC; package PRO active399000VND/cycle1/maxStaff30; package FREE0VND, USD, fractional VND để kiểm tra từ chối. OWNER_A/B khác tenant, staff có SUBSCRIPTION_VIEW nhưng không owner, system SUPER_ADMIN với/không review permission, restaurant ACTIVE/SUSPENDED, user active/disabled. Bằng chứng provider giả lập có HMAC đúng/sai trong **test harness Phase4**, không dùng tài khoản/secret production.

Đồng thời dùng hai thread/connection và barrier tại điểm trước lock; không dùng sleep làm bằng chứng race. Đếm rows + audit theo aggregate ID, kiểm tra uniqueness trực tiếp SQL khi cần. Inject lỗi trước commit/audit/activation; kill/restart worker để kiểm tra durable recovery. Header owner/admin bearer, mutation JSON; create/attempt có Idempotency-Key theo OpenAPI. Event ACK200 chỉ chứng minh inbox, phải đợi worker/quét DB để assert settlement.

Audit viết tắt: C=SUBSCRIPTION_PURCHASE_CREATED; A=SUBSCRIPTION_PAYMENT_ATTEMPT_CREATED; P=SUBSCRIPTION_PURCHASE_PAID; ACT=SUBSCRIPTION_ACTIVATED + SUBSCRIPTION_PURCHASE_ACTIVATED; CAN=SUBSCRIPTION_PURCHASE_CANCELLED; EXP=SUBSCRIPTION_PURCHASE_EXPIRED; IN=SUBSCRIPTION_PAYMENT_EVENT_RECEIVED; REV=SUBSCRIPTION_PAYMENT_REVIEW_REQUIRED; RC=SUBSCRIPTION_PAYMENT_RECHECK_REQUESTED; CL=SUBSCRIPTION_PAYMENT_REVIEW_CLOSED (+SUBSCRIPTION_PURCHASE_CLOSED khi đủ điều kiện). “0 success” = không tạo audit thành công giả; security failure có thể ghi log bảo mật đã redaction.

## Ma trận runtime cần triển khai

| ID | Phase | Điều kiện đầu | Request/sự kiện | HTTP / code mong đợi | DB/state sau xử lý | Audit | Retry/concurrency cần assert |
|---|---|---|---|---|---|---|---|
| SP01 | 2 | Email/code mới | Register DTO đúng |201 theo auth hiện có |1 OWNER,1 restaurant ACTIVE, tokens,0 subscription |RESTAURANT_REGISTERED=1 |Không dùng purchase idempotency cho auth |
| SP02 | 2 | Lỗi sau save owner trước commit | Register/fault injection |Lỗi hiện có |Owner/restaurant/token DB rollback |0 registration success |Login không thấy dữ liệu nửa chừng |
| SP03 | 2 | OWNER hợp lệ, chưa subscription | GET onboarding |200,NONE,canPurchase=true |Không write |0 |Lặp GET không audit |
| SP04 | 2 | Không có subscription | POST purchase keyK PRO |201,false header |PENDING_PAYMENT,quote15phút |C=1 |Same key replay SP10 |
| SP05 | 2 | Chỉ EXPIRED/CANCELLED history | POST purchase |201 |Mở purchase mới, history giữ |C=1 |Hai fixture độc lập |
| SP06 | 2 | ACTIVE endAt>now, cả future start | POST purchase |409 SUBSCRIPTION_ALREADY_ACTIVE |Không purchase mới |0 success |Không override qua create+activate |
| SP07 | 2 | Admin PENDING | POST purchase |409 SUBSCRIPTION_PENDING_EXISTS |Giữ PENDING |0 success |Cancel admin rồi create mới được |
| SP08 | 2 | ACTIVE endAt==now, package hợp lệ | POST purchase |201 |Old EXPIRED + new purchase cùng commit |SUBSCRIPTION_EXPIRED=1,C=1 |Fixed Clock boundary; rollback nếu create lỗi |
| SP09 | 2 | Cùng tenant chưa purchase | Hai create khác key |Một201,một409 PURCHASE_IN_PROGRESS |Chỉ1 open purchase |C=1 |Barrier +2connections, unique enforced |
| SP10 | 2 | Purchase đã tạo | Retry cùng key/package normalized |200,replayed=true |Cùng ID/body lúc insert |Không thêmC |PRO và ' pro ' normalize giống |
| SP11 | 2 | K đã tạo PRO | RetryK package khác |409 IDEMPOTENCY_KEY_REUSED |Không write |0 thêm |Kể cả purchase ACTIVATED/EXPIRED |
| SP12 | 2/4 | PurchaseK đã ACTIVATED | Retry createK |200 original body |Không subscription mới, không kéo kỳ |0 thêm |Replay trước ACTIVE eligibility |
| SP13 | 2 | Cùng tenant/key đồng thời | Hai POST cùngK |201 +200 |1row,creation_response đúng |C=1 |Unique loser rollback rồi đọc replay |
| SP14 | 2 | OWNER_B biết purchaseA UUID | GET/detail/cancel/current/createattempt |404 PURCHASE_NOT_FOUND sau access hợp lệ |A không đổi |0 success |Không lộ tenant, không scope bằng body |
| SP15 | 2 | Staff có SUBSCRIPTION_VIEW | POST purchase |403 FORBIDDEN |Không write |0 |Thử cả grant purchase permission nhưng role staff vẫn403 |
| SP16 | 2 | OWNER thiếu VIEW/CREATE/CANCEL tương ứng | Mỗi API protected |403 FORBIDDEN |Không write |0 |allowedActions không cấp quyền thiếu |
| SP17 | 2 | Thiếu JWT / account disabled / restaurant suspend | Owner API |401 UNAUTHORIZED /401 ACCOUNT_INACTIVE /403 RESTAURANT_INACTIVE |Không write |0 success |Old token không bypass filter |
| SP18 | 2 | Package inactive/notfound | POST purchase |404 PACKAGE_NOT_FOUND |Không quote |0C |No catalog leak |
| SP19 | 2 | FREE/USD/fractionalVND | POST purchase |422 PACKAGE_NOT_PURCHASABLE |Không quote |0C |399000.00 BigDecimal hợp lệ,399000.50 không |
| SP20 | 2 | Quote còn hạn | Admin sửa price/grants/maxStaff/disable |Admin success; GET purchase200 |Quote giữ nguyên |Chỉ audit admin hiện có |New quote dùng catalog mới, old honor |
| SP21 | 2 | Catalog metadata/mapping/global feature đổi cùng lúc | Create quote race |201 coherent snapshot hoặc bounded409 CONCURRENT_SUBSCRIPTION_UPDATE |Không ghép hai version |C chỉ cho commit |REPEATABLE_READ +package lock, retry snapshot mới |
| SP22 | 2 | maxStaff=null | Create quote/GET |201/200 |Top-level null, không feature.limits.maxStaff |C=1 |Legacy subscription snapshot không bị đổi |
| SP23 | 2 | Flag accept-new=false | New create, old replay, GET |503 PURCHASE_UNAVAILABLE /200 /200 |Không intent mới, resource cũ đọc được |0 thêmC |Không tắt webhook/recovery cũ |
| SP24 | 2 | Input field giá/tenant/status thêm | POST purchase, malformed JSON/key |400 INVALID_REQUEST_BODY hoặc VALIDATION_ERROR |Không write |0 |Check strict request unknown fields |
| SP25 | 2 | List nhiều trạng thái | GET filters page0,size20,date range |200 PageResponse; invalid range400 VALIDATION_ERROR |Read-only |0 |size101/page-1 từ chối; sort tie theoid |
| SP26 | 2 | Stored ACTIVE đã endAt; stale purchase | GET onboarding/detail |200 projection EXPIRED |Không cập nhật status DB |0EXP |Command sau đó mới reconcile |
| SP27 | 3 | Purchase còn hạn, chưa attempt | POST attempts {} keyA |201,false |1PENDING,amount/reference/instructions/hạn đúngquote |A=1 |Không HTTP khi giữ lock |
| SP28 | 3 | Đã có attemptA | Retry cùngkey/path |200,true original body |Cùng ID/reference/hạn |Không thêmA |Sau SUCCESS/expiry vẫn replay rồi GET |
| SP29 | 3 | Có PENDING | Key mới /2request kháckey |409 PAYMENT_ATTEMPT_PENDING hoặc1new201+1conflict |1PENDING |A=1 tổng |DB partial unique trực tiếp |
| SP30 | 3 | Tenant có attemptK ở purchase1 | DùngK pathpurchase2 |409 IDEMPOTENCY_KEY_REUSED |Không row mới |0A |Fingerprint chứa purchaseId |
| SP31 | 3/5 | now==quote.expiresAt | Create attempt/GETcurrent |409 PURCHASE_PERIOD_EXPIRED /200 projected expiry |Không extend quote |EXP chỉ command/job commit |Không chấp nhận +1ms |
| SP32 | 3 | Chưa có attempt/attempt SUCCESS | GETcurrent |200,null /200 terminal attempt |Không write |0 |Reload không autoPOST |
| SP33 | 3 | Lỗi prepare QR trước insert | POSTattempt |503 PAYMENT_PROVIDER_UNAVAILABLE |Không attempt thành công giả |0A |Retry cùngkey khi provider/config lại sẵn |
| SP34 | 2/3 | Purchase pending +attempt | Cancel version đúng, lặp version cũ |200,CANCELLED cảhai |Giải phóng open slot |CAN=1 |Lầnhai no-op trước version, create ý định mới được |
| SP35 | 2/4 | Purchase PAID/ACTIVATED/review/closed | Cancel |409 PURCHASE_CANNOT_CANCEL |Money/history giữ |0CAN |Không thu tiền lại |
| SP36 | 2 | Purchase EXPIRED hoặc version stale | Cancel |409 PURCHASE_PERIOD_EXPIRED /CONCURRENT_SUBSCRIPTION_UPDATE |Không write |0CAN |Refetch version |
| SP37 | 4 | HMAC sai, thiếu hoặc timestamp lệch >300s | POST webhook | 401 WEBHOOK_AUTHENTICATION_FAILED / WEBHOOK_TIMESTAMP_INVALID | Không lưu trusted inbox | 0 IN/P/ACT | Ký raw bytes; sửa whitespace phải fail |
| SP38 | 4 | HMAC đúng, JSON hợp lệ, tiền khớp | POST webhook | 200 success=true | Commit inbox trước ACK; worker tiến tới PAID/ACTIVATED | IN=1, P=1, ACT=1 | Không JWT; không dùng202 để ACK |
| SP39 | 4 | DB down lúc ingest | POST webhook hợp lệ | 503 WEBHOOK_PERSISTENCE_UNAVAILABLE | Không giả durable row | 0 IN/P/ACT | Provider retry sau khi DB hồi phục |
| SP40 | 4 | Cùng provider transaction | Hai webhook đồng thời | Cả hai200 sau durable insert/select | Một event, một settlement, một subscription | IN/P/ACT mỗi loại một | Barrier; claim/fencing và unique DB |
| SP41 | 4 | Event PROCESSING/RETRY_PENDING | Gửi lại webhook | 200 | Không reset lease hoặc bỏ job | Không audit trùng | Worker vẫn tiếp tục, kể cả restart |
| SP42 | 4 | PAID đã commit, activation lỗi DB/audit | Inject lỗi activation | Webhook đã200; GET200 PAID | Attempt SUCCESS, event PROCESSED, purchase PAID; không partial subscription | P=1, ACT=0 lúc lỗi | Retry sau đó tạo một subscription/ACT |
| SP43 | 4 | Quote cũ, catalog đã thay đổi | Worker activation | GET200 ACTIVATED | Snapshot schema2 đúng quote, capturedAt=startAt activation | ACT=1 | Không dùng live factory/giá mới |
| SP44 | 4/5 | PAID gặp ACTIVE/PENDING khác hoặc tenant suspend | Activation | GET200 REVIEW_REQUIRED | Giữ paidAt/SUCCESS; paid event vào review; không cấp gói khác | REV=1 | RECHECK sau khi gỡ blocker không ghi P lần hai |
| SP45 | 4/5 | Attempt đã SUCCESS/ACTIVATED | Transfer thật thứ hai, ID khác | ACK200; review event mới | Purchase giữ ACTIVATED; event DUPLICATE_PAYMENT | IN/REV event mới; không thêm ACT | Thử khác attempt cùng purchase; không SUCCESS lần hai; pending khác phải cancel |
| SP46 | 4/5 | Số tiền thiếu hoặc thừa | Webhook amount khác quote | ACK200; AMOUNT_MISMATCH | Không PAID; purchase được link vào review | IN/REV; không P/ACT | Không cộng hai khoản thiếu thành split payment |
| SP47 | 4/5 | Thiếu/sai mã hoặc nhiều mã khác nhau | Webhook hợp lệ chữ ký | ACK200; REFERENCE_UNMATCHED / AMBIGUOUS | Link unmatched null; không fuzzy match | IN/REV; không P | Không match theo amount/tên khách |
| SP48 | 4/5 | Sai account/bank hoặc tiền ra | Webhook HMAC đúng | ACK200; ACCOUNT_MISMATCH / OUTGOING_TRANSFER | Giữ evidence; không PAID | IN/REV; không P/ACT | Payload account không thành trusted config |
| SP49 | 5 | Purchase CANCELLED | Nhận transfer trong hoặc ngoài hạn | ACK200; PURCHASE_CANCELLED | Không auto activate; giữ bằng chứng tiền | IN/REV | Không xóa history cancelled |
| SP50 | 5 | Quote EXPIRED, occurredAt>=expiresAt | Nhận webhook | ACK200; PAYMENT_OUTSIDE_WINDOW | Không PAID; event review | IN/REV | Reference cũ không gán purchase mới |
| SP51 | 5 | Transfer trong hạn; quote expired; không blocker | Webhook đến muộn | ACK200; cuối cùng GET ACTIVATED | Honor quote cũ; attempt SUCCESS | P/ACT một lần | Dựa occurredAt, không receivedAt |
| SP52 | 5 | Purchase cũ expired, có open purchase/sub mới | Timely transfer cũ đến muộn | ACK200; SUBSCRIPTION_CONFLICT review | Không reopen phá unique, không cancel gói mới | IN/REV | Admin xử lý bằng chứng của purchase cũ |
| SP53 | 4/5 | Worker PROCESSING, app bị kill | Restart và chờ lease hết | Không HTTP job; GET200 | Recover RETRY_PENDING rồi xử lý tiếp | Không P/ACT trùng | Fencing ngăn worker cũ commit |
| SP54 | 5 | Provider API offline | Reconciliation job | Job retry; không ACK giả | Giữ inbox/PAID hiện tại | Không success giả | Mỗi5phút, lookback48h, phân trang/backoff |
| SP55 | 5 | Webhook thất lạc, có transfer trong API v2 | Import reconciliation | Cuối cùng GET ACTIVATED nếu identity đủ | Durable inbox source RECONCILIATION | IN/P/ACT một lần | Webhook đến sau dedupe cùng canonical identity |
| SP56 | 5 | Webhook integer ID, API UUID cùng bank ref | Nhập từ hai nguồn | ACK200/read200 | Một canonical event + aliases; không cast hai ID | Không thêm P/ACT | Thiếu correlation ở API phải IDENTITY_UNCERTAIN |
| SP57 | 5 | Same identity nhưng amount/ref mâu thuẫn | Duplicate/import conflict | ACK sau lưu evidence/conflict bền vững; review | Không overwrite evidence gốc | Security/REV; không thêm P | Không mặc định hai nguồn khác ID là hai transfers |
| SP58 | 5 | Event REVIEW_REQUIRED, version/reason đúng | Admin RECHECK | 202 RETRY_PENDING | Queue bền vững, cùng settlement pipeline | RC=1 | Request lặp version cũ409; không RC lần hai |
| SP59 | 5 | Review, reason và reference hợp lệ | Admin CLOSE_OFFLINE | 200 CLOSED | Giữ money/paidAt/SUCCESS; purchase CLOSED khi đủ điều kiện | CL=1 | Không fake refund; purchase ACTIVATED không đổi |
| SP60 | 5 | OWNER hoặc admin thiếu system boundary/quyền | Admin list/reconcile | 403 FORBIDDEN | Không write | Không RC/CL | Bắt buộc cả system SUPER_ADMIN và permission |
| SP61 | 5 | Event terminal hoặc version stale | RECHECK/CLOSE | 409 INVALID_PAYMENT_EVENT_TRANSITION / CONCURRENT_SUBSCRIPTION_UPDATE | Không write | Không thêm RC/CL | Refetch; không reopen terminal tùy ý |
| SP62 | 2/4 | Owner và admin cùng restaurant | Race create/assign/activate | Owner201/admin409 SUBSCRIPTION_PURCHASE_IN_PROGRESS hoặc admin thành công/owner409 SUBSCRIPTION_PENDING_EXISTS | Không open purchase xung đột PENDING/ACTIVE | Chỉ audit request thắng | Shared restaurant lock; package không lock trước restaurant |
| SP63 | 4/5 | Cancel và settlement cùng lúc | Hai connections tranh lock | Cancel200 + event review hoặc PAID + cancel409 | Không mất tiền hoặc tạo hai subscription | P/CAN theo kết quả commit | Barrier, không dựa sleep |
| SP64 | 4/5 | Expiry job và timely webhook cạnh tranh | Chạy đồng thời | ACK200, cuối cùng PAID/ACTIVATED nếu eligible | Không discard bank time; unique vẫn đúng | EXP nếu commit; P/ACT một lần | Recheck dưới lock |
| SP65 | 4 | Jan31, leap day, ranh giới timezone | Activate cycle1/12 tháng | GET200 | Calendar plusMonths clamp; end exclusive | ACT một lần | Replay không kéo kỳ; quotedAt khác capturedAt |
| SP66 | 2/4/5 | Bypass service bằng SQL | Duplicate open/attempt/ref/event hoặc link sai tenant | DB constraint reject | Không cross-tenant/expiry/amount drift | Không success | Deferred FK check lúc commit; immutable trigger |
| SP67 | 2–5 | Migration mới theo version thực tế | Flyway clean và upgrade | Migration thành công | Checksums cũ giữ; unique ACTIVE/PENDING giữ | N/A | Preflight duplicate fail rõ, không xóa dữ liệu |
| SP68 | 2–5 | Admin/order-payment API cũ | Regression toàn bộ contract | Giữ status/body/security, ngoại trừ planned admin guard | Không trộn SaaS với payment món ăn | Audit cũ đúng | Maven toàn bộ khi hiện thực/release |
| SP69 | 6 | OWNER mới trên web | Register→catalog→purchase→QR→activate | 201/200 theo spec | Entitlement cache mới; không lưu password | C/A/P/ACT đúng | Sandbox E2E; timeout retry key cũ |
| SP70 | 6 | Pending/PAID/ACTIVATED/review trên server | Reload, đổi thiết bị, login lại | GET onboarding/detail/current | Restore không POST tự động | Không thêm C/A | Xóa tenant cache lúc logout; countdown theo server |
| SP71 | 6 | SUBSCRIPTION_NOT_ACTIVE hoặc token lỗi | FE interceptor | Onboarding cho403; refresh đúng401 | Không logout vì chưa có gói | N/A | Không refresh loop; mutation retry giữ key |
| SP72 | 6 | Flag off lúc pending/PAID | Reload/poll/replay | GET200, replay200, ý định mới503 | Tiếp tục nhận/activate tiền cũ | Không audit do GET | Không mời trả tiền lại hoặc ẩn trang phục hồi |

## Tiêu chí nghiệm thu theo phase

- **Phase1**: đủ10 operations PLANNED; target phase, security, permission, tenant, cache, idempotency đầy đủ; OpenAPI3.1 parse/validator pass; mọi ref/example hợp schema; enum/null/money/date/page nhất quán. Chỉ docs/README thay đổi. Validation tài liệu báo riêng, không biến72case này thành passed.
- **Phase2**: SP01–26,34–36 phần core,62,66–68 phần core đạt. Kiểm tra source hiện tại trước migration; tất cả đường admin cấp ACTIVE/PENDING phối hợp restaurant lock. Chưa bật mua live trước Phase4/5.
- **Phase3**: SP27–34,66 phần attempt đạt. QR sandbox đúng reference/account/amount; không HTTP dưới lock; key replay/current khôi phục được; constraints và instructions đúng quote.
- **Phase4**: SP37–48,53,63,65,66 phần financial đạt. ACK sau commit, durable PAID retry, snapshot đúng quote. Inject lỗi audit/DB phải rollback activation và không mất tiền; webhook200 không đồng nghĩa activation đã pass.
- **Phase5**: SP44–64,67–68 phần tổng thể đạt. Đối soát nhiều trang và identity chéo nguồn đúng; review có closure evidence; có metrics cho expired lease/retry; không bỏ tiền đến muộn.
- **Phase6**: SP69–72 đạt trên web thật + sandbox; auth abstraction không lưu password; payment data không service-worker cache; khách không phải trả lại vì activation chậm.
- **Phase7**: thực sự chạy Maven toàn bộ, Flyway clean/upgrade, concurrency/failure injection, web E2E; lưu report và có on-call runbook. Không tuyên bố release chỉ dựa Phase1 docs.

Chỉ dùng tài khoản giả trong fixtures. SePay sandbox/live secret do môi trường cung cấp; không commit secret thật hoặc Authorization vào report. Phase1 không chạy runtime tests; Phase2 chỉ triển khai và kiểm tra phần trong phạm vi hiện tại.

## Xác minh thực tế Giai đoạn 2 — 2026-11-01

- Maven wrapper, JDK24 với javac `--release21`, PostgreSQL17.11, các schema test cô lập: **1376 tests, failures0, errors0, skipped0**. Command toàn bộ có `-Dspring.test.context.cache.maxSize=2` để không giữ quá nhiều Hikari pool/context cùng lúc. Tham số chỉ áp dụng lần chạy test; không đổi giới hạn kết nối server hoặc cấu hình pool runtime.
- `SubscriptionPurchaseIntegrationTest`: **34 executions passed**, gồm các case parameterized. `FlywayCleanMigrationIntegrationTest`: **20 passed**. Có test upgrade V40→V41 riêng giữ nguyên subscription snapshot/user permission cũ; không sửa V1–V40.
- Race hai create (cùng/khác key), race admin commit trong lúc buyer chờ restaurant lock, race package/global feature, rollback audit, tenant/role/permission, FK vòng, immutable history, pagination/query count đều có integration test PostgreSQL thật. Các latch/barrier và wait_event_type=Lock được dùng để xác nhận trạng thái cạnh tranh.
- Backend chạy tạm trên loopback/schema riêng: **10 HTTP response mẫu** của năm API, replay/cancel idempotent, request field lạ và unauthenticated response được kiểm chứng cùng header no-store/Vary; schema probe được dọn sau kiểm tra.
- OpenAPI purchase/admin: validate cấu trúc, internal refs và **189 static examples**;10 response HTTP thật cũng khớp schema. Purchase contract có đúng5 IMPLEMENTED +5 PLANNED.

Coverage theo ma trận: SP01–26, SP34–36 phần Phase2/fixture, SP62 và SP66–68 phần Phase2. SP12/SP35 dùng financial fixture hợp lệ để kiểm tra replay/read/cancel; không gọi payment/activation runtime. Test attempt/event constraints là kiểm tra schema, không phải QR/provider settlement. Các phần Phase3–6, webhook, chuyển tiền thật, activation từ purchase và web E2E vẫn **NOT RUN / chưa triển khai**.

Lần full-suite đầu gặp SQLSTATE53300 do quá nhiều test context giữ connection pool; đã khắc phục bằng tham số cache test và chạy lại toàn bộ đạt. Không che lỗi bằng skip hoặc sửa business/security.
