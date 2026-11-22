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
