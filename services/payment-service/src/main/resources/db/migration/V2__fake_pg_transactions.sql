-- 가짜 PG 쪽 거래 원장. 실제로는 외부 PG 가 가진 기록이라 payments(우리 쪽 결과 기록)와 따로 커밋한다.
-- PG 승인 후 우리 쪽 기록 전에 죽어도 원장이 남아 있어, 재처리 때 같은 주문을 다시 승인하지 않는다
create table fake_pg_transactions (
    order_id       uuid primary key,
    user_id        uuid         not null,
    amount         bigint       not null,
    approved       boolean      not null,
    transaction_id varchar(100),
    reason         varchar(200),
    created_at     timestamptz  not null default now()
);
