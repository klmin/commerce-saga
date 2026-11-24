# ADR 0015. 컨텍스트 맵과 용어집, 쓰지 않기로 한 DDD 전술 패턴

- 상태: 채택
- 날짜: 2026-11-25

## 배경

서비스 = bounded context 로 나눴고(ADR 0000·0001), 서비스 안은 헥사고날 + 애그리거트로 짰다(ADR 0002). 값 객체도 서비스마다 넣었다(PR #56~#59). 그런데 **서비스 사이의 관계**(누가 계약을 정하고 누가 따르는지)와 **같은 말이 서비스마다 뜻이 다른 곳**은 코드와 여러 ADR 에 흩어져 있었다. 또 도메인 이벤트·이벤트 소싱·JPA Auditing 처럼 "DDD 라면 보통 쓰는" 것들을 왜 쓰지 않았는지도 적혀 있지 않았다.

이 ADR 은 새 결정을 만들기보다 지금 구조를 DDD 용어로 정리하고, 쓰지 않기로 한 패턴과 그 이유를 남긴다.

## 컨텍스트 맵

```
                      (JWT, JWKS)
   auth ─────────────────────────────────▶ gateway / 각 서비스  : 신원은 auth 를 그대로 따름 (Conformist)
     │ auth.events (UserRegistered)
     ▼
   point ◀──── point.command / point.reply ────┐
                                               │
   inventory ◀── inventory.command / reply ── order (오케스트레이터)
                                               │
   payment ◀──── payment.command / reply ──────┘
     │ PaymentGatewayPort (ACL)
     ▼
   외부 PG (지금은 FakePaymentGateway)

   order ── order.events (OrderCompleted, OrderCancelled) ──▶ inventory(판매 확정), notification
```

| 관계 | 패턴 | 계약을 정하는 쪽 | 근거 |
|---|---|---|---|
| order → inventory·point·payment (커맨드·응답) | **Open Host Service + Published Language**. 참여 서비스가 자기 커맨드 토픽(`<수신 서비스>.command`)과 응답을 정하고, 오케스트레이터가 따른다 | 참여 서비스 | 참여 서비스는 order 를 모른다. 커맨드만 보고 처리하고 응답한다 (ADR 0003·0005) |
| order → inventory·notification (`order.events`) | **Published Language**. 발행자가 정한 과거형 사실을 소비자가 각자 해석 | order | 소비자는 0~N. 관심 없는 타입은 건너뛴다 (ADR 0012) |
| auth → point (`auth.events`) | **Published Language** | auth | 가입 사실만 알리고 포인트 계정은 point 가 만든다 (ADR 0008) |
| auth → gateway·서비스 (JWT `sub`) | **Conformist** | auth | 서비스는 사용자 모델을 따로 두지 않고 토큰의 `sub` 를 사용자 id 로 쓴다 |
| payment → 외부 PG | **Anti-Corruption Layer** | PG | `PaymentGatewayPort` 가 PG 개념(승인·거절·타임아웃·조회·환불)을 결제 도메인 말로 바꾼다. PG 를 바꿔도 도메인은 그대로 |
| 모든 서비스 ↔ `libs/messaging-contract` | **Shared Kernel 을 메시지 스키마로만 한정** | 각 토픽 소유 서비스 | 공유하는 것은 record(스키마)와 토픽 이름뿐. 도메인 클래스·값 객체는 공유하지 않는다. 변경은 필드 추가만 (ADR 0005) |

정리하면 **업스트림은 늘 "사실·능력을 가진 쪽"**(재고·포인트·결제·인증·주문 결과)이고, 오케스트레이터는 그 계약을 조합할 뿐 참여 서비스의 규칙을 알지 않는다.

## 용어집

같은 단어라도 컨텍스트마다 뜻이 다른 것은 굵게 표시했다.

| 용어 | 컨텍스트 | 뜻 | 코드 |
|---|---|---|---|
| 주문 | order | 사용자가 낸 구매 요청. PENDING → COMPLETED \| CANCELLED, 결론이 나면 되돌아가지 않는다 | `Order`, `OrderStatus` |
| 주문 Saga | order | 주문 하나의 분산 처리 진행 상태. 응답마다 다음 할 일을 정한다 | `OrderSaga`, `SagaState`, `SagaAction` |
| 보상 | order | 정방향으로 이미 한 단계를 역순으로 되돌리는 일 (결제 취소 → 포인트 환불 → 재고 해제) | `compensate`, `CANCELLING_PAYMENT`·`REFUNDING_POINT`·`RELEASING_STOCK` |
| 재전송 / 포기 | order | 응답이 없는 단계에서 같은 커맨드를 다시 보냄 / 정방향을 포기하고 보상으로 넘어감 (ADR 0007) | `TIMEOUT_RESEND`, `TIMEOUT_GIVE_UP` |
| 예약 | inventory | 주문 가능한 재고(available)를 주문에 잡아 둠(reserved) | `Stock.reserve`, `StockReservation` |
| 해제 | inventory | 보상: 잡아 둔 재고를 available 로 되돌림 | `Stock.release`, `ReleaseStock` |
| 확정 | inventory | 주문 완료: 잡아 둔 재고를 판매로 확정(reserved 에서 뺌) | `Stock.confirm`, `OrderCompleted` 소비 |
| 차감(사용) | point | 주문에 포인트를 씀. 잔액보다 많이 쓸 수 없다 | `PointAccount.use`, `UsePoint` |
| **환불** (포인트) | point | 보상: 차감한 포인트를 돌려줌 | `PointAccount.refund`, `RefundPoint` |
| 지급 | point | 주문과 무관하게 잔액이 늘어남 (가입 축하) | `PointGrant` |
| 승인 / 거절 / UNKNOWN | payment | PG 결과. UNKNOWN 은 응답 타임아웃이라 PG 조회로 확정한다 | `PaymentStatus` |
| **취소** (결제) | payment | 주문 쪽이 결제를 포기함. 승인됐으면(지금이든 나중이든) PG 환불로 이어진다 | `requestCancel`, `CancelPayment` |
| **환불** (결제) | payment | 승인된 결제를 PG 에서 되돌림. 늦은 승인 자동 환불 포함 | `Payment.refunded`, `PaymentGatewayPort.refund` |
| **취소** (주문) | order | 주문이 실패로 끝남. 사유는 처음 실패한 단계의 것 | `Order.cancel`, `OrderCancelled` |
| **금액** | order·payment | 원화(`Money`). 결제 금액 = 합계 − 사용 포인트 | `Money` |
| **포인트** | order·point | 포인트 양(`Points`). 1P = 1원 환산은 order 의 `Points.toMoney()` 한 곳 | `Points` |
| 사용자 id | 전체 | auth 가 발급한 id = JWT `sub`. 다른 컨텍스트의 식별자라 값만 가진다 | `UserId`(order·point) |
| 커맨드 / 응답 / 이벤트 | 메시징 | 명령형·수신자 하나 / 커맨드의 결과 / 과거형 사실·수신자 0~N | `*.command`, `*.reply`, `*.events` |

"환불"과 "취소"가 둘씩 있는 것이 가장 헷갈리기 쉽다. 로그·문서·리뷰에서는 **포인트 환불 / 결제 환불, 주문 취소 / 결제 취소** 처럼 컨텍스트를 붙여 쓴다.

## 쓰지 않기로 한 것

### 1. 도메인 이벤트 객체

애그리거트가 `OrderCancelled` 같은 이벤트를 쌓고 서비스가 꺼내 outbox 로 옮기는 방식은 쓰지 않는다. 지금은 애플리케이션 서비스가 출력 포트(`OrderEventPort.orderCancelled(order)`)를 직접 부르고, 어댑터가 outbox 에 넣는다.

- "이벤트를 낼지"는 이미 도메인이 정한다. `Order.cancel()` 이 실제로 바뀌었는지 `boolean` 으로 돌려주고, `OrderSaga` 가 다음 할 일을 `SagaAction` 으로 돌려준다
- 이벤트 종류가 적고(`order.events` 2종, `auth.events` 1종) 발행 지점이 한 곳(`SagaActionExecutor`)이라 호출을 빠뜨릴 위험이 낮다. 응답(`*.reply`)은 커맨드에 대한 답이라 도메인 이벤트 대상도 아니다
- 넣으면 이벤트 record·꺼내기·타입별 매핑만 늘어난다

**다시 검토할 때**: 한 동작이 조건에 따라 이벤트를 여럿 내거나, 같은 이벤트를 여러 유스케이스가 내거나, 같은 서비스 안의 다른 애그리거트가 이벤트에 반응해야 할 때.

### 2. 이벤트 소싱

상태 대신 이벤트 목록을 원본으로 저장하는 방식은 쓰지 않는다. outbox 와는 푸는 문제가 다르다(outbox 는 DB 변경과 메시지 발행의 원자성, 이벤트 소싱은 상태 저장 방식). 주문·재고·결제는 현재 상태가 중요하고, 이력이 필요한 곳은 이력 테이블로 충분하다(`order_saga_history`, `point_usages`·`point_grants`). 이벤트 소싱을 넣으면 프로젝션(CQRS)과 옛 이벤트를 영원히 읽어야 하는 스키마 관리 부담이 커진다.

### 3. JPA Auditing 과 등록자·수정자 컬럼

`@EnableJpaAuditing`·`@CreatedDate`·`created_by`/`updated_by` 는 쓰지 않는다.

- 시간은 도메인 규칙의 일부다(`OrderSaga.onTimeout` 이 `updatedAt` 으로 재전송을 판단). 도메인이 `Clock` 으로 채우고, JPA 가 따로 찍지 않는다
- 도메인 모델과 JPA 엔티티가 분리돼 있어(ADR 0002) Auditing 값은 도메인이 모른다. `JdbcClient` 로 저장하는 테이블에는 적용되지도 않는다
- 상태는 대부분 사람이 아니라 메시지(시스템)가 바꾼다. 주문의 등록자는 `user_id` 가 이미 맡고, 변경 이력은 `order_saga_history` 같은 도메인 이력이 더 많은 것("무엇이 왜")을 남긴다

**다시 검토할 때**: 사람이 데이터를 직접 고치는 기능이 생길 때. 후속 4(product-service)에서 관리자가 상품·가격을 바꾸면, 공통 컬럼 대신 가격 변경 이력 같은 **도메인 이력**으로 "누가·무엇을·왜"를 남기는 쪽을 먼저 본다.

### 4. 값 객체 공유

값 객체(`Money`·`Points`·`UserId` 등)는 `libs/` 로 옮겨 공유하지 않는다. 같은 이름이라도 컨텍스트마다 규칙이 다르다(order 의 `Quantity` 는 상한이 있는 주문 수량, inventory 의 `Quantity` 는 예약 수량, payment 는 `Money` 만 쓰고 결제 금액 > 0 규칙은 `Payment` 가 가진다). 공유하면 한 서비스의 규칙 변경이 다른 서비스 배포를 끌고 간다. DB 컬럼·메시지 계약·port.in Command·조회 DTO 는 원시 타입으로 두고 어댑터·서비스 입구에서 바꾼다. 메시지로 들어온 값은 값 객체로 바꾸기 **전에** 검사해 실패로 응답하거나 DLT 로 보낸다(바로 바꾸면 예외로 같은 메시지를 계속 재시도한다).

## 결과

- 새 서비스·새 메시지를 만들 때 이 맵의 어느 관계인지(누가 계약을 정하는지) 먼저 정하고, 용어집에 없는 말이 생기면 여기에 더한다
- 후속 4 의 product-service 는 상품 카탈로그·가격의 업스트림이 된다. 주문은 주문 시점 가격을 스냅샷으로 가지고, 검색(ES)은 product 테이블 CDC 의 하류 읽기 모델이다. 그때 이 맵에 더한다
