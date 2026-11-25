-- PG 승인 요청을 보낸 적이 있는 주문 (PROGRESS 4-4절 ③). PG 를 부르기 전에 먼저 커밋한다.
-- 서킷 브레이커가 열려 이번 호출을 막았을 때, 이전 시도가 PG 에 닿았을 수 있는 주문(이 표에 있음)은
-- 거절로 확정하지 않고 UNKNOWN 으로 남겨 PG 조회로 확정한다 (ADR 0007)
create table pg_approve_attempts (
    order_id     uuid primary key,
    attempted_at timestamp with time zone not null
);
