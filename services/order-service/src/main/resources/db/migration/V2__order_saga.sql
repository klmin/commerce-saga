-- 2단계: 포인트 사용, 주문 결과 상태(COMPLETED/CANCELLED), Saga 상태 머신

alter table orders rename column reject_reason to cancel_reason;
alter table orders add column point_amount bigint not null default 0;

-- 1단계 상태값 정리 (로컬 개발 데이터). 1단계의 CONFIRMED(재고 예약 성공)는 그때의 최종 성공이었다
update orders set status = 'COMPLETED' where status = 'CONFIRMED';
update orders set status = 'CANCELLED' where status = 'REJECTED';

create table order_sagas (
    order_id       uuid primary key references orders (id),
    user_id        uuid        not null,
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

-- 업그레이드 시점에 진행 중(PENDING)이던 1단계 주문을 Saga 로 이관한다.
-- 1단계 주문은 ReserveStock 만 보낸 상태라 RESERVING_STOCK 에서 시작하고, 포인트는 쓰지 않았다(전액 결제).
-- 이렇게 해야 이관 후 도착하는 StockReserved/StockReservationFailed 응답이 버려지지 않고 이어서 진행된다
insert into order_sagas (order_id, user_id, point_amount, paid_amount, state, point_used, failure_reason,
                         created_at, updated_at, version)
select id, user_id, 0, total_amount, 'RESERVING_STOCK', false, null, created_at, now(), 0
from orders
where status = 'PENDING';

insert into order_saga_history (order_id, seq, event, state, reason, occurred_at)
select id, 0, 'STARTED', 'RESERVING_STOCK', 'migrated from phase 1', created_at
from orders
where status = 'PENDING';
