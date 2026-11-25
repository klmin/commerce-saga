-- 서비스마다 DB와 계정을 따로 둔다. 다른 서비스 DB에는 접속 권한이 없다.
-- 비밀번호는 로컬 개발용 값이다.
-- compose 의 postgres-init 이 `up` 할 때마다 실행한다. 이미 있으면 건너뛰므로(멱등)
-- 서비스가 늘어도 기존 볼륨을 지우지 않고 DB가 추가된다.

select 'create role order_svc with login password ''order_svc'''
where not exists (select from pg_roles where rolname = 'order_svc')\gexec
select 'create database order_db owner order_svc'
where not exists (select from pg_database where datname = 'order_db')\gexec
revoke all on database order_db from public;

select 'create role inventory_svc with login password ''inventory_svc'''
where not exists (select from pg_roles where rolname = 'inventory_svc')\gexec
select 'create database inventory_db owner inventory_svc'
where not exists (select from pg_database where datname = 'inventory_db')\gexec
revoke all on database inventory_db from public;

select 'create role point_svc with login password ''point_svc'''
where not exists (select from pg_roles where rolname = 'point_svc')\gexec
select 'create database point_db owner point_svc'
where not exists (select from pg_database where datname = 'point_db')\gexec
revoke all on database point_db from public;

select 'create role payment_svc with login password ''payment_svc'''
where not exists (select from pg_roles where rolname = 'payment_svc')\gexec
select 'create database payment_db owner payment_svc'
where not exists (select from pg_database where datname = 'payment_db')\gexec
revoke all on database payment_db from public;

select 'create role notification_svc with login password ''notification_svc'''
where not exists (select from pg_roles where rolname = 'notification_svc')\gexec
select 'create database notification_db owner notification_svc'
where not exists (select from pg_database where datname = 'notification_db')\gexec
revoke all on database notification_db from public;

-- 회원·로그인 (후속 2). 가입 이벤트 outbox 가 있어 아래에서 replication 권한도 준다 (A-3-2)
select 'create role auth_svc with login password ''auth_svc'''
where not exists (select from pg_roles where rolname = 'auth_svc')\gexec
select 'create database auth_db owner auth_svc'
where not exists (select from pg_database where datname = 'auth_db')\gexec
revoke all on database auth_db from public;

-- 상품·가격 (후속 4-1, ADR 0016). outbox 는 없고, 검색용 테이블 CDC(products, ADR 0010) 때문에 아래에서 replication 권한을 준다
select 'create role product_svc with login password ''product_svc'''
where not exists (select from pg_roles where rolname = 'product_svc')\gexec
select 'create database product_db owner product_svc'
where not exists (select from pg_database where datname = 'product_db')\gexec
revoke all on database product_db from public;

-- Debezium outbox 커넥터(infra/connect)가 서비스 계정으로 자기 DB 의 WAL 을 읽는다 (logical replication).
-- 접속 DB 가 자기 DB 로만 허용되므로 다른 서비스 DB 의 변경은 읽을 수 없다. outbox 테이블 소유자라 publication 도 만들 수 있다.
-- 알림 서비스는 outbox 가 없어 주지 않는다. 이미 있는 볼륨에도 up 할 때마다 다시 적용된다(멱등)
alter role order_svc with replication;
alter role inventory_svc with replication;
alter role point_svc with replication;
alter role payment_svc with replication;
alter role auth_svc with replication;
-- product 는 outbox 가 아니라 products 테이블을 검색용으로 복제한다 (ADR 0010). 테이블 소유자라 publication 도 만들 수 있다
alter role product_svc with replication;
