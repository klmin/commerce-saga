-- 후속 2 A-3-2: 가입을 auth.events(UserRegistered) 로 알린다. 사용자 저장 + outbox = 한 트랜잭션
-- Transactional Outbox (Debezium Outbox Event Router 호환, 다른 서비스와 같은 모양). relay 가 발행 후 삭제한다
create table outbox (
    id            uuid primary key,
    aggregatetype varchar(100) not null,
    aggregateid   varchar(100) not null,
    type          varchar(100) not null,
    payload       jsonb        not null,
    created_at    timestamptz  not null default now(),
    -- 넣는 시점의 trace 문맥(W3C traceparent). relay 가 Kafka 헤더 traceparent 로 싣는다 (ADR 0013)
    traceparent   varchar(55)
);

create index idx_outbox_created_at on outbox (created_at);
