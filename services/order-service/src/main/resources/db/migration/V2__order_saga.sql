-- 2단계: 포인트 사용, 주문 결과 상태(COMPLETED/CANCELLED), Saga 상태 머신

alter table orders rename column reject_reason to cancel_reason;
alter table orders add column point_amount bigint not null default 0;

-- 1단계 상태값 정리 (로컬 개발 데이터). 1단계의 CONFIRMED(재고 예약 성공)는 그때의 최종 성공이었다
update orders set status = 'COMPLETED' where status = 'CONFIRMED';
update orders set status = 'CANCELLED' where status = 'REJECTED';

create table order_sagas (
    order_id       uuid primary key references orders (id),
    user_id        bigint      not null,
    point_amount   bigint      not null,
    paid_amount    bigint      not null,
    state          varchar(30) not null,
    point_used     boolean     not null,
    failure_reason varchar(200),
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    version        bigint      not null
);

-- 진행 중인 Saga 조회(타임아웃 감시 등, 3단계)용
create index idx_order_sagas_state on order_sagas (state);

create table order_saga_history (
    order_id    uuid         not null references order_sagas (order_id),
    seq         int          not null,
    event       varchar(50)  not null,
    state       varchar(30)  not null,
    reason      varchar(200),
    occurred_at timestamptz  not null,
    primary key (order_id, seq)
);
