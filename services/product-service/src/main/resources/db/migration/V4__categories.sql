-- 상품 카테고리 (후속 9-1, ADR 0021). 고정 목록: 바꾸려면 마이그레이션. 키는 생성 id 가 아니라 정해 둔 코드
create table categories (
    code       varchar(30) primary key check (code ~ '^[a-z][a-z0-9-]{0,29}$'),
    name       varchar(30) not null,
    sort_order int         not null
);

insert into categories (code, name, sort_order) values
    ('digital', '디지털', 10),
    ('appliance', '가전', 20),
    ('kitchen', '주방', 30),
    ('living', '생활', 40),
    ('fashion', '패션', 50),
    ('beauty', '뷰티', 60),
    ('sports', '스포츠·레저', 70),
    ('etc', '기타', 999);

-- 기존 상품은 기타로 넣은 뒤 시드 상품만 고친다. 기본값은 지운다(앱이 늘 정해서 넣는다)
alter table products add column category_code varchar(30) not null default 'etc' references categories (code);
alter table products alter column category_code drop default;
update products set category_code = 'digital'
where id in ('01900000-0000-7000-8000-000000000001', '01900000-0000-7000-8000-000000000002',
             '01900000-0000-7000-8000-000000000003', '01900000-0000-7000-8000-000000000004',
             '01900000-0000-7000-8000-000000000005');
create index idx_products_category on products (category_code, id);

-- 대표 이미지의 저장소 key (9-2 에서 쓴다, RustFS). 없으면 화면이 카테고리 기본 그림
alter table products add column image_key varchar(200);

-- 관리 이력에 카테고리 전·후. 이 열 전의 이력은 null
alter table product_audit_log
    add column before_category_code varchar(30),
    add column after_category_code  varchar(30);
