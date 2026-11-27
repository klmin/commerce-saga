-- 로컬·CI 개발용 사용자 (ADR 0008). 운영에서는 AUTH_FLYWAY_LOCATIONS=classpath:db/migration 으로 이 위치를 빼고 띄운다.
-- 비밀번호는 모두 로컬 개발용 값 'local-dev-pass' (BCrypt). 사용자 id 는 고정 UUID 로 다른 서비스의 시드와 맞춘다 (ADR 0018)
-- (포인트: user1~3 = 10,000P, user4 = 0P). admin 은 관리자(결제 장애 주입 등 /api/admin/**).
--   user1 01900000-0000-7000-8001-000000000001   user2 01900000-0000-7000-8001-000000000002
--   user3 01900000-0000-7000-8001-000000000003   user4 01900000-0000-7000-8001-000000000004
--   admin 01900000-0000-7000-8001-000000000100
-- 가입자 id 는 앱이 만드는 UUIDv7(지금 시각)이라 이 고정 값(2024년 시각)과 겹치지 않는다.
-- repeatable 마이그레이션이라 내용이 바뀌면 다시 실행된다. 이미 있으면 건너뛴다(멱등)
insert into users (id, login_id, password_hash, status, created_at, updated_at)
values ('01900000-0000-7000-8001-000000000001', 'user1', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       ('01900000-0000-7000-8001-000000000002', 'user2', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       ('01900000-0000-7000-8001-000000000003', 'user3', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       ('01900000-0000-7000-8001-000000000004', 'user4', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now()),
       ('01900000-0000-7000-8001-000000000100', 'admin', '$2a$10$3YheoqMdrLkFoYBPMUqULudxLCpR4yZ11vKA4uipdMogHLs.kgP2a', 'ACTIVE', now(), now())
on conflict do nothing;

-- 역할은 id 와 아이디가 모두 시드와 같은 행에만 준다. 같은 아이디로 먼저 가입한 사용자가 있으면(아이디 유니크로)
-- 위 INSERT 는 건너뛰지만 여기서 그 사람에게 ADMIN 이 붙으면 안 된다
insert into user_roles (user_id, role)
select u.id, seed.role
from (values ('01900000-0000-7000-8001-000000000001'::uuid, 'user1', 'USER'),
             ('01900000-0000-7000-8001-000000000002'::uuid, 'user2', 'USER'),
             ('01900000-0000-7000-8001-000000000003'::uuid, 'user3', 'USER'),
             ('01900000-0000-7000-8001-000000000004'::uuid, 'user4', 'USER'),
             ('01900000-0000-7000-8001-000000000100'::uuid, 'admin', 'USER'),
             ('01900000-0000-7000-8001-000000000100'::uuid, 'admin', 'ADMIN')) as seed (id, login_id, role)
join users u on u.id = seed.id and u.login_id = seed.login_id
on conflict do nothing;
