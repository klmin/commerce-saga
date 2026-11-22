-- 시연용 초기 재고. 상품 관리(5단계 product-service) 전까지 쓴다
--   1~3: 충분, 4: 1개(경합·품절 시나리오), 5: 품절
insert into stocks (product_id, available, reserved, updated_at) values
    (1, 100, 0, now()),
    (2, 100, 0, now()),
    (3, 100, 0, now()),
    (4, 1, 0, now()),
    (5, 0, 0, now());
