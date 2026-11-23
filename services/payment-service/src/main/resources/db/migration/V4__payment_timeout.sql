-- 3-D-2: 결제 타임아웃 보정 (PG 조회), 결제 취소와 늦은 승인 자동 환불 (ADR 0007)

-- 주문 쪽 취소 요청. 승인 상태(지금 또는 PG 조회로 나중에)면 PG 환불 대상이다
alter table payments add column cancel_requested boolean not null default false;
alter table payments add column updated_at timestamptz;
update payments set updated_at = created_at;
alter table payments alter column updated_at set not null;

-- 보정 대상만: PG 결과를 모르는 결제, 취소 요청을 받았는데 승인 상태인 결제 (오래된 순으로 처리)
create index idx_payments_pending_reconcile on payments (updated_at)
    where status = 'UNKNOWN' or (status = 'APPROVED' and cancel_requested);

-- 가짜 PG 원장: 환불 여부 (PG 쪽 기록이라 결제 서비스 트랜잭션과 따로 커밋)
alter table fake_pg_transactions add column refunded boolean not null default false;
