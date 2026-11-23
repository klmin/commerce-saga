-- 후속 2 A-3-1: 내 주문 목록(커서 페이징), 주문 Idempotency-Key (시나리오 10)

-- 내 주문 목록: user_id 로 거르고 id(UUIDv7 = 생성 순서) 내림차순. 기존 (user_id) 인덱스는 이 인덱스의 앞부분이라 대신한다
create index idx_orders_user_id_id on orders (user_id, id);
drop index idx_orders_user_id;

-- 주문 Idempotency-Key. 같은 사용자·같은 키는 주문 하나 (결제 버튼 두 번, 새로고침, 응답 유실 후 재시도)
-- 키를 먼저 선점하고 주문을 나중에 넣으므로 FK 는 커밋 때 검사한다
create table order_idempotency_keys (
    user_id         bigint       not null,
    idempotency_key varchar(100) not null,
    request_hash    char(64)     not null,
    order_id        uuid         not null unique references orders (id) deferrable initially deferred,
    created_at      timestamptz  not null,
    primary key (user_id, idempotency_key)
);
