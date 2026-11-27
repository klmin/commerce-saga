-- 주문별 결제 결과. order_id 유니크라 같은 주문을 두 번 결제 기록하지 않는다
create table payments (
    id             uuid primary key,
    order_id       uuid         not null unique,
    user_id        uuid         not null,
    amount         bigint       not null,
    status         varchar(20)  not null,
    transaction_id varchar(100),
    reason         varchar(200),
    created_at     timestamptz  not null
);

create index idx_payments_user_id on payments (user_id);

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
