-- 로컬·CI 개발용 사용자 (ADR 0008). 운영에서는 AUTH_FLYWAY_LOCATIONS=classpath:db/migration 으로 이 위치를 빼고 띄운다.
-- 비밀번호는 모두 로컬 개발용 값 'local-dev-pass' (BCrypt). 사용자 id 1~4 는 다른 서비스의 시드와 맞춘다
-- (포인트: 1~3 = 10,000P, 4 = 0P). 100 은 관리자(결제 장애 주입 등 /api/admin/**).
-- repeatable 마이그레이션이라 내용이 바뀌면 다시 실행된다. 이미 있으면 건너뛴다(멱등)
insert into users (id, login_id, password_hash, status, created_at, updated_at)
values (1, 'user1', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       (2, 'user2', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       (3, 'user3', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       (4, 'user4', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       (100, 'admin', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now())
on conflict do nothing;

-- 역할은 id 와 아이디가 모두 시드와 같은 행에만 준다. 이미 같은 id 의 다른(가입한) 사용자가 있으면
-- 위 INSERT 는 건너뛰지만 여기서 그 사람에게 ADMIN 이 붙으면 안 된다
insert into user_roles (user_id, role)
select u.id, seed.role
from (values (1, 'user1', 'USER'), (2, 'user2', 'USER'), (3, 'user3', 'USER'), (4, 'user4', 'USER'),
             (100, 'admin', 'USER'), (100, 'admin', 'ADMIN')) as seed (id, login_id, role)
join users u on u.id = seed.id and u.login_id = seed.login_id
on conflict do nothing;

-- 가입으로 생기는 사용자는 1000 부터 (시드 id 와 겹치지 않게)
select setval(pg_get_serial_sequence('users', 'id'), greatest(999, (select max(id) from users)));
