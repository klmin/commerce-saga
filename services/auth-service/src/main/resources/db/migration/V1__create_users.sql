-- 사용자 id 는 UUIDv7. 가입할 때 auth-service 가 앱에서 만든다(DB 시퀀스 없음). 토큰 sub·다른 서비스의 userId 가 이 값 (ADR 0008·0018)
create table users (
    id            uuid         primary key,
    login_id      varchar(30)  not null,
    password_hash varchar(100) not null,
    status        varchar(20)  not null,
    created_at    timestamptz  not null,
    updated_at    timestamptz  not null,
    constraint uk_users_login_id unique (login_id)
);

-- 권한은 역할만 쓴다 (USER, ADMIN). OAuth scope 는 쓰지 않는다
create table user_roles (
    user_id uuid        not null references users (id),
    role    varchar(20) not null,
    primary key (user_id, role)
);
