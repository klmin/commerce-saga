-- 서비스마다 DB와 계정을 따로 둔다. 다른 서비스 DB에는 접속 권한이 없다.
-- 비밀번호는 로컬 개발용 값이다. 컨테이너 볼륨을 처음 만들 때 한 번만 실행된다.

create role order_svc with login password 'order_svc';
create database order_db owner order_svc;
revoke all on database order_db from public;

create role inventory_svc with login password 'inventory_svc';
create database inventory_db owner inventory_svc;
revoke all on database inventory_db from public;
