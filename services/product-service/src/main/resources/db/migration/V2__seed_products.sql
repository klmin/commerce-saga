-- 지금까지 프론트 고정 목록(frontend/src/lib/products.ts)에 있던 상품 1~5. inventory 재고 시드(V2)와 같은 id
insert into products (id, name, description, price, on_sale, created_at, updated_at) values
    (1, '무선 키보드', '', 39000, true, now(), now()),
    (2, '무선 마우스', '', 25000, true, now(), now()),
    (3, 'USB-C 허브', '', 18000, true, now(), now()),
    (4, '한정판 키캡', '재고 1개로 시작 (동시 주문·품절 시연)', 12000, true, now(), now()),
    (5, '단종 모니터암', '재고 0개 (재고 예약 실패 → 주문 취소 시연)', 45000, true, now(), now());

-- 새로 등록하는 상품은 1000 부터 (시드와 겹치지 않게)
alter table products alter column id restart with 1000;
