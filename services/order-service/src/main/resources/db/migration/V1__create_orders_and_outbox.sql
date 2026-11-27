create table orders (
    id            uuid primary key,
    user_id       uuid         not null,
    status        varchar(20)  not null,
    total_amount  bigint       not null,
    reject_reason varchar(200),
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    version       bigint       not null
);

create index idx_orders_user_id on orders (user_id);

create table order_lines (
    order_id   uuid    not null references orders (id),
    line_no    int     not null,
    product_id uuid    not null,
    quantity   int     not null,
    unit_price bigint  not null,
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
