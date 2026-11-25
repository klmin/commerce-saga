-- 멱등 기록 정리 (PROGRESS 4-4절 ②): 보존 기간이 지난 Idempotency-Key 를 오래된 것부터 지운다
create index idx_order_idempotency_keys_created_at on order_idempotency_keys (created_at);
