-- PG 승인 호출 예약 (PROGRESS 4-4절 ③). 결제 처리마다 PG 를 부르기 전에 calls 를 1 올리고 커밋한다.
-- 서킷 브레이커가 열려 이번 호출이 막혔을 때 거절로 확정해도 되는지 판단한다:
--   calls = 1(이 처리 하나뿐) 이면 sealed 로 봉인 → 이후 어떤 처리도 이 주문으로 PG 를 부르지 않는다 → 거절 확정
--   calls > 1 이면 다른 처리(재전달·Saga 재전송·동시 처리)가 PG 에 닿았을 수 있다 → UNKNOWN, 조회로 확정 (ADR 0007)
-- 예약(calls + 1)과 봉인은 같은 행의 조건부 갱신이라 서로 끼어들지 못한다
create table pg_approve_attempts (
    order_id     uuid primary key,
    calls        integer                  not null,
    sealed       boolean                  not null default false,
    attempted_at timestamp with time zone not null
);
