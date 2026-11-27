-- 관리자 재고 조정(입고·차감) 이력 (ADR 0019). 재고 변경과 같은 트랜잭션에 남고, 지우지 않는다(추가 전용).
-- (adjusted_by, idempotency_key) 유니크 = 같은 관리자가 같은 키로 다시 보내면 한 번만 반영한다
create table stock_adjustments (
    id              bigint generated always as identity primary key,
    product_id      uuid         not null references stocks (product_id),
    delta           int          not null check (delta <> 0),
    available_after int          not null check (available_after >= 0),
    reason          varchar(200),
    adjusted_by     uuid         not null,
    idempotency_key varchar(100) not null,
    adjusted_at     timestamptz  not null,
    unique (adjusted_by, idempotency_key)
);

-- 상품별 최근 이력 조회
create index idx_stock_adjustments_product on stock_adjustments (product_id, id desc);
