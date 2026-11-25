-- 상품 관리 이력 (audit, 후속 4-3, ADR 0010 결정 12). 상품을 바꾼 트랜잭션에서 INSERT 만 한다.
-- 바꾼 전·후 값을 열로 남긴다(검색·CDC 와 무관, 이 테이블은 커넥터 column.include.list 밖).
-- changed_by = 관리자 사용자 id (JWT sub). 등록은 before_* 가 null
create table product_audit_log (
    id                 bigint generated always as identity primary key,
    product_id         bigint       not null references products (id),
    action             varchar(30)  not null
        check (action in ('REGISTERED', 'DESCRIBED', 'PRICE_CHANGED', 'SALE_STATUS_CHANGED')),
    before_name        varchar(100),
    before_description varchar(500),
    before_price       bigint,
    before_on_sale     boolean,
    after_name         varchar(100),
    after_description  varchar(500),
    after_price        bigint,
    after_on_sale      boolean,
    changed_by         bigint       not null,
    changed_at         timestamptz  not null
);

create index idx_audit_log_product on product_audit_log (product_id, changed_at desc, id desc);

-- 이 테이블 이전의 가격 변경을 옮긴다. 그때의 다른 값은 모르므로 가격만 채운다
insert into product_audit_log (product_id, action, before_price, after_price, changed_by, changed_at)
select product_id, 'PRICE_CHANGED', old_price, new_price, changed_by, changed_at
from product_price_history
order by changed_at, id;
