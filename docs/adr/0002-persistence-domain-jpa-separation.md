# ADR 0002. 영속성: 도메인 모델과 JPA 엔티티 분리

- 상태: 채택
- 날짜: 2026-11-22

## 배경

헥사고날 구조에서 애그리거트를 어떻게 저장할지 정해야 한다. 후보:

- A. 도메인 모델과 JPA 엔티티를 분리하고 어댑터에서 매핑
- B. 도메인 모델에 JPA 어노테이션을 직접
- C. Spring Data JDBC / jOOQ 어댑터

## 결정

**A**. 도메인(`<feature>/domain`)은 Spring·JPA·Kafka에 의존하지 않고, `adapter/out/persistence`의 package-private JPA 엔티티로 매핑한다.

- ArchUnit(`HexagonalArchitectureTest`)이 도메인의 프레임워크 의존, 엔티티 노출을 막는다
- 애그리거트 내부 값 객체(`OrderLine`, `ReservationLine`)는 `@ElementCollection` + `@Embeddable`로 루트와 함께 저장·로딩한다. 리포지토리는 애그리거트 루트마다 하나
- 수정은 서비스에서 조회 → 도메인 메서드로 변경 → 포트로 넘기면 어댑터가 영속 엔티티에 값을 옮겨 더티 체킹으로 반영한다

이유:

- 3단계에서 relay를 스케줄러 → Debezium으로 바꿀 때 "도메인·애플리케이션은 수정 없음"을 보여주는 것이 이 프로젝트의 근거 중 하나다. 저장 기술도 같은 기준으로 분리해 둔다
- 템플릿(`hexagonal-spring-next-template`)과 같은 방식이라 규칙·테스트를 그대로 가져올 수 있다
- C(Spring Data JDBC)는 애그리거트 단위 저장 철학과 잘 맞지만, 조회는 jOOQ·JPA와 섞이게 되고 팀에 익숙한 쪽은 JPA다

## 타협: inventory 재고 예약은 한 트랜잭션에서 여러 애그리거트를 바꾼다

"한 트랜잭션 = 애그리거트 하나"가 지향점이지만, 재고 예약(`StockService.reserve`)은 한 트랜잭션에서

- 주문에 든 여러 상품의 `Stock`
- 주문별 결과 `StockReservation`
- 응답 outbox 행

을 함께 바꾼다. 주문 단위 "전부 아니면 전무" 예약이 요구사항이고, 상품별로 트랜잭션을 나누면 일부만 잡힌 상태를 다시 보상해야 해서 서비스 안에 작은 Saga가 하나 더 생긴다. 같은 서비스·같은 DB 안이므로 로컬 트랜잭션으로 묶는다.

동시성은 `Stock` 행을 productId 순서로 `SELECT ... FOR UPDATE`해서 맞춘다 (같은 상품을 동시에 예약하면 뒤 요청이 기다림, 순서 고정으로 교착 방지). DB의 `check (available >= 0)`가 마지막 방어선이다.

## 결과

- 좋은 점: 도메인 단위 테스트가 순수 자바로 빠르다. 저장 기술 변경이 어댑터 안에서 끝난다
- 나쁜 점: 엔티티·매퍼 보일러플레이트. 조회 성능이 필요한 화면(관리자 Saga 조회 등)은 jOOQ 등으로 DTO를 직접 조회한다 (CQRS-lite, 필요해질 때 추가)
