# 시나리오 4. 참여 서비스 다운 → 복구 후 이어서 진행, 오래 다운되면 역순 보상

참여 서비스(point-service)가 내려간 동안 들어온 주문이 어떻게 되는지 본다. 짧게 내려가면 복구 후 Saga가 이어서 완료되고, 오래 내려가면 order-service가 기다리다 포기하고 역순 보상한다. 두 경우 모두 포인트·재고는 정확히 맞는다. 정책은 [ADR 0007](../adr/0007-saga-timeout.md).

## 재현

1. 인프라와 네 서비스를 띄운다. order-service는 단계 타임아웃을 짧게 둔다 (CI `e2e`와 같은 값)
   `SAGA_STEP_TIMEOUT=10s SAGA_MAX_RESENDS=3 SAGA_TIMEOUT_SCAN_INTERVAL=1s` (기본값은 30s, 3, 5s)
2. `scripts/smoke-service-down.sh`: point-service를 스크립트가 직접 내리고(`pkill`) 다시 띄운다(`java -jar`)
   - **4a. 짧은 장애**: point-service 중단 → 사용자 2가 상품 3을 1개, 포인트 100으로 주문 → `UsePoint` 재전송이 Saga 기록에 남을 때까지 기다림 → point-service 기동 → 완료·포인트·재고 확인
   - **4b. 긴 장애**: point-service 중단 → 같은 주문 → 재전송을 다 쓰고 포기(`REFUNDING_POINT`)할 때까지 기다림 → point-service 기동 → 취소·포인트·재고 확인
   - 로컬 Windows(Git Bash)는 `pkill`·`pgrep`이 java 프로세스를 못 보므로 `POINT_STOP_CMD`·`POINT_START_CMD`로 내리고 띄우는 명령을 바꾼다

CI `e2e`가 두 relay 모드(scheduler, debezium)에서 시나리오 6 다음에 이 스크립트를 돈다.

## 기대 결과

| | 장애 중 | 복구 후 |
|---|---|---|
| 4a 짧은 장애 (재전송 횟수 안에 복구) | 주문 `PENDING`, Saga `USING_POINT`, `TIMEOUT_RESEND` 기록 | `COMPLETED`. 포인트 −100 **한 번** (재전송된 `UsePoint`는 주문별 사용 기록으로 걸러져 처음 결과만 다시 응답) |
| 4b 긴 장애 (재전송을 다 씀) | `TIMEOUT_GIVE_UP` → `REFUNDING_POINT`. 주문은 보상이 끝날 때까지 `PENDING` | `CANCELLED`(사유 `timeout: no reply in USING_POINT after N resends`). 포인트·재고 원래대로 |

4b가 맞게 끝나는 이유: 포기할 때 포인트가 차감됐는지 모르므로 **차감됐다고 보고** 환불부터 보낸다. point-service는 복구되면 같은 파티션(키 = orderId)의 커맨드를 보낸 순서대로 처리한다. 밀린 `UsePoint`(차감)가 먼저, `RefundPoint`(환불)가 나중이다. 그래서 포인트는 차감됐다가 바로 환불된다. 늦게 도착한 `PointUsed` 응답은 Saga가 이미 `REFUNDING_POINT`라 무시한다(단계가 되돌아가지 않음).

## 실제 결과

로컬(Windows, Docker Desktop, Debezium relay 모드) 2026-11-23. 로컬은 강제 종료밖에 못 해 재전송 횟수를 6으로 늘려 돌렸다(아래 "발견한 것" 참고):

```
== 4a. point-service 짧은 장애 → 재전송 → 복구 후 COMPLETED
  point-service 중단
  ok  장애 중 Saga 단계 = USING_POINT
  ok  장애 중 주문 상태 = PENDING
  point-service 복구
  ok  복구 후 주문 …62c25785 COMPLETED
  ok  포인트 잔액 (재전송해도 한 번만 차감) = 9800
  ok  상품 3 available = 98
== 4b. point-service 긴 장애 → 재전송 포기 → 역순 보상 → 복구 후 CANCELLED
  point-service 중단
  ok  재전송 6번 후 포기, REFUNDING_POINT
  ok  보상 중 주문 상태 (보상 끝날 때까지) = PENDING
  point-service 복구 (밀린 UsePoint → RefundPoint 순서로 처리)
  ok  복구 후 주문 …9fc5b95d CANCELLED
  ok  포인트 잔액 (차감 후 환불) = 9800
  ok  상품 3 available (해제) = 98
  ok  상품 3 reserved (해제) = 0
  Saga 기록:
    STARTED -> RESERVING_STOCK
    STOCK_RESERVED -> USING_POINT
    TIMEOUT_RESEND -> USING_POINT        (×6, 10초 간격)
    TIMEOUT_GIVE_UP -> REFUNDING_POINT
    POINT_REFUNDED -> RELEASING_STOCK
    STOCK_RELEASED -> CANCELLED
PASS
```

CI [2026-11-23 실행](https://github.com/klmin/commerce-saga/actions/runs/45136811780) (PR #24, 두 relay 모드 모두 PASS, `SAGA_STEP_TIMEOUT=10s`, `SAGA_MAX_RESENDS=3`, `pkill` 정상 종료). scheduler 모드:

```
== 4a. point-service 짧은 장애 → 재전송 → 복구 후 COMPLETED
  ok  장애 중 Saga 단계 = USING_POINT
  ok  장애 중 주문 상태 = PENDING
  ok  복구 후 주문 …1c9b885d COMPLETED
  ok  포인트 잔액 (재전송해도 한 번만 차감) = 9900
  ok  상품 3 available = 99
== 4b. point-service 긴 장애 → 재전송 포기 → 역순 보상 → 복구 후 CANCELLED
  ok  재전송 3번 후 포기, REFUNDING_POINT
  ok  보상 중 주문 상태 (보상 끝날 때까지) = PENDING
  ok  복구 후 주문 …b05441d3 CANCELLED
  ok  포인트 잔액 (차감 후 환불) = 9900
  ok  상품 3 available (해제) = 99
  ok  상품 3 reserved (해제) = 0
```

CI는 정상 종료라 재전송 횟수를 늘리지 않아도 4a가 재전송 3번 안에 복구됐다(로컬 강제 종료와의 차이).

4b에서 복구된 point-service의 로그를 보면, 밀린 `UsePoint`(처음 것은 차감, 재전송분은 "중복 포인트 사용 커맨드, 이전 결과 재응답")가 처리된 뒤 `RefundPoint`가 처리된다.

## 발견한 것: 강제 종료면 복구가 세션 타임아웃만큼 늦다

처음 로컬 실행(재전송 2번, 포기까지 약 30초)에서는 4a가 `COMPLETED`가 아니라 `CANCELLED`로 끝났다. point-service는 5초 만에 다시 떴지만, 파티션을 받기까지 **26초**가 더 걸렸다.

- 로컬에서는 `Stop-Process -Force`로 내렸다. 정상 종료가 아니라서 컨슈머가 그룹을 떠난다고 알리지 못한다
- 브로커는 죽은 멤버를 `session.timeout.ms`(기본 45초)가 지나야 빼고 리밸런스한다. 그동안 새로 뜬 인스턴스는 `(Re-)joining group`에서 기다린다
- 그사이 order-service가 재전송을 다 쓰고 보상으로 넘어갔다. 결과는 여전히 정확했다(포인트 차감 후 환불, 재고 해제, `CANCELLED`). 다만 복구가 됐는데도 주문이 취소된 것이다

그래서 다음과 같이 정했다.
- CI는 `pkill`(SIGTERM)로 내린다. Spring이 정상 종료하면서 컨슈머가 그룹을 떠나므로 바로 리밸런스된다
- 단계 타임아웃 × 재전송 횟수(기본 30초 × 3 = 90초 + 마지막 30초)는 **프로세스가 죽은 경우의 복구 시간(재기동 + 세션 타임아웃 45초)보다 길게** 둔다. 짧게 두면 복구될 장애도 보상으로 끝난다 (ADR 0007)
