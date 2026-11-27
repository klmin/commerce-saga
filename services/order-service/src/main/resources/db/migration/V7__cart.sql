-- 장바구니 (후속 4-1c, ADR 0016). 사용자당 하나, 상품마다 한 줄. 담을 때 본 가격을 남겨 그 뒤 가격 변경을 보여 준다
create table cart_items (
    user_id     uuid        not null,
    product_id  uuid        not null,
    quantity    int         not null check (quantity between 1 and 1000),
    added_price bigint      not null check (added_price >= 0),
    added_at    timestamptz not null,
    updated_at  timestamptz not null,
    primary key (user_id, product_id)
);
