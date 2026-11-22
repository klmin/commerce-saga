create table point_accounts (
    user_id    bigint primary key,
    balance    bigint      not null check (balance >= 0),
    updated_at timestamptz not null
);

-- 주문별 포인트 사용 기록. 같은 주문의 커맨드가 다시 와도 이 행으로 판단해 두 번 차감·환불하지 않는다
create table point_usages (
    order_id   uuid primary key,
    user_id    bigint,                 -- 사용 전에 환불 요청이 온 기록은 사용자를 모른다
    amount     bigint       not null,
    status     varchar(20)  not null,
    reason     varchar(200),
    created_at timestamptz  not null,
    updated_at timestamptz  not null,
    version    bigint       not null
);

create index idx_point_usages_user_id on point_usages (user_id);

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
