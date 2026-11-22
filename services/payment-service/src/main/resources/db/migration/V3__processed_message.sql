-- 멱등 컨슈머: 처리한 메시지 ID (Kafka 헤더 id = 보낸 쪽 outbox.id).
-- 비즈니스 변경과 같은 트랜잭션에서 INSERT 하고, 이미 있으면 그 메시지는 건너뛴다 (ADR 0006).
-- processed_at 은 보존 기간이 지난 행을 지울 때 쓴다 (토픽 보존 기간보다 길게 둔다).
create table processed_message (
    message_id   uuid        primary key,
    processed_at timestamptz not null
);

create index idx_processed_message_processed_at on processed_message (processed_at);
