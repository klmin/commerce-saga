create table stocks (
    product_id bigint primary key,
    available  int         not null check (available >= 0),
    reserved   int         not null check (reserved >= 0),
    updated_at timestamptz not null
);

-- 주문별 예약 결과. 같은 주문의 커맨드가 다시 와도 이 행으로 판단해 재고를 두 번 잡지 않는다
create table stock_reservations (
    order_id   uuid primary key,
    status     varchar(20)  not null,
    reason     varchar(200),
    created_at timestamptz  not null,
    version    bigint       not null
);

create table stock_reservation_lines (
    order_id   uuid   not null references stock_reservations (order_id),
    line_no    int    not null,
    product_id bigint not null,
    quantity   int    not null,
    primary key (order_id, line_no)
);

-- Transactional Outbox (Debezium Outbox Event Router 호환). relay 가 발행 후 삭제한다
create table outbox (
    id            uuid primary key,
    aggregatetype varchar(100) not null,
    aggregateid   varchar(100) not null,
    type          varchar(100) not null,
    payload       jsonb        not null,
    created_at    timestamptz  not null default now()
);

create index idx_outbox_created_at on outbox (created_at);
