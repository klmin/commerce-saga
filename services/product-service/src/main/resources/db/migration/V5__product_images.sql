-- 상품 대표 이미지 (후속 9-2, ADR 0021). 파일은 RustFS 버킷에, 상품에는 key 만 (V4 의 image_key)

-- 관리 이력: 이미지 변경 종류와 전·후 key. 이 열 전의 이력은 null
alter table product_audit_log drop constraint product_audit_log_action_check;
alter table product_audit_log add constraint product_audit_log_action_check
    check (action in ('REGISTERED', 'DESCRIBED', 'PRICE_CHANGED', 'SALE_STATUS_CHANGED', 'IMAGE_CHANGED'));
alter table product_audit_log
    add column before_image_key varchar(200),
    add column after_image_key  varchar(200);

-- 카테고리별 시드 상품 (화면에 여러 카테고리가 보이게). inventory 재고 시드(V8)와 같은 고정 UUID (ADR 0018).
-- 가격은 검색 시나리오의 가격 범위(15,000~30,000) 밖, 이름은 검색 시나리오 검색어("마우")와 겹치지 않게
insert into products (id, name, description, price, on_sale, category_code, created_at, updated_at) values
    ('01900000-0000-7000-8000-000000000006', '전기포트', '1.7L 스테인리스, 자동 전원 차단', 32000, true, 'kitchen', now(), now()),
    ('01900000-0000-7000-8000-000000000007', '무선 청소기', '가벼운 스틱형, 1회 충전 40분', 189000, true, 'appliance', now(), now()),
    ('01900000-0000-7000-8000-000000000008', '머그컵 세트', '도자기 머그 2개', 12500, true, 'kitchen', now(), now()),
    ('01900000-0000-7000-8000-000000000009', '수건 세트', '면 100%, 4장', 13900, true, 'living', now(), now()),
    ('01900000-0000-7000-8000-000000000010', '후드 티셔츠', '기모 안감, 남녀공용', 49000, true, 'fashion', now(), now()),
    ('01900000-0000-7000-8000-000000000011', '핸드크림', '시어버터, 50ml', 8900, true, 'beauty', now(), now()),
    ('01900000-0000-7000-8000-000000000012', '요가 매트', '두께 8mm, 미끄럼 방지', 35000, true, 'sports', now(), now());

-- 시드 이미지: 앱이 시작할 때 저장소에 없으면 올린다(classpath seed-images, ProductImageSeeder). 같은 key 는 내용이 같다
update products set image_key = 'products/' || id || '/seed.svg'
where id between '01900000-0000-7000-8000-000000000001' and '01900000-0000-7000-8000-000000000012';
