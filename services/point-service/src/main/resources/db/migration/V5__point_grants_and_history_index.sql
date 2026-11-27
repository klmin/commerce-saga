-- 후속 2 F-2a: 포인트 내역
-- 지급 기록 (가입 축하 등). 계정을 만든 트랜잭션에서 함께 INSERT 한다. id 는 UUIDv7 (생성 순서)
create table point_grants (
    id         uuid        primary key,
    user_id    uuid        not null,
    amount     bigint      not null check (amount > 0),
    type       varchar(30) not null,
    created_at timestamptz not null
);

create index idx_point_grants_user_id_id on point_grants (user_id, id);

-- 내 포인트 내역(커서 페이징): user_id 로 거르고 order_id(UUIDv7 = 주문 순서) 내림차순
-- 기존 (user_id) 인덱스는 이 인덱스의 앞부분이라 대신한다
create index idx_point_usages_user_id_order_id on point_usages (user_id, order_id);
drop index idx_point_usages_user_id;
