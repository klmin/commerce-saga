-- 가입으로 생기는 사용자 id 는 1000 부터. 다른 서비스의 시연용 시드(포인트 계정 1~4 등)와 겹치지 않게 한다.
-- 원래 db/dev-seed 에만 있던 구문이라, 시드를 빼고 띄우는 환경(EKS 등 AUTH_FLYWAY_LOCATIONS=classpath:db/migration)에서는
-- 첫 가입자가 id 1~4 를 받아 point-service 시드 계정(10,000P·0P)을 물려받고 가입 지급이 건너뛰어졌다.
-- 이미 1000 이상이면(가입자·시드가 있으면) 낮추지 않는다
select setval(
    pg_get_serial_sequence('users', 'id'),
    greatest(
        999,
        coalesce((select max(id) from users), 0),
        coalesce(pg_sequence_last_value(pg_get_serial_sequence('users', 'id')::regclass), 0)
    )
);
