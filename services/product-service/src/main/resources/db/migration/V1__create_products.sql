-- 상품·가격 (후속 4-1, ADR 0016). 가격은 원 단위 정수. id 는 UUIDv7 로 앱이 저장 전에 만든다 (ADR 0018)
create table products (
    id          uuid         primary key,
    name        varchar(100) not null,
    description varchar(500) not null default '',
    price       bigint       not null check (price > 0 and price <= 100000000),
    on_sale     boolean      not null,
    created_at  timestamptz  not null,
    updated_at  timestamptz  not null
);

-- 가격 변경 이력 (audit). 가격을 바꾼 트랜잭션에서 INSERT 만 한다. changed_by = 관리자 사용자 id (JWT sub, UUIDv7)
create table product_price_history (
    id         bigint generated always as identity primary key,
    product_id uuid        not null references products (id),
    old_price  bigint      not null,
    new_price  bigint      not null,
    changed_by uuid        not null,
    changed_at timestamptz not null
);

create index idx_price_history_product on product_price_history (product_id, changed_at desc, id desc);
