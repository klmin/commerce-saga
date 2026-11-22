-- 사용자에게 보낸 알림 기록. (order_id, type) 유니크라 같은 이벤트가 다시 와도 알림은 한 번만 만든다
create table notifications (
    id         bigint generated always as identity primary key,
    order_id   uuid         not null,
    user_id    bigint       not null,
    type       varchar(50)  not null,
    message    varchar(500) not null,
    created_at timestamptz  not null default now(),
    unique (order_id, type)
);

create index idx_notifications_user_id on notifications (user_id, id desc);
