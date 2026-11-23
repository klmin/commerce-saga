-- 3-D: 멈춘 Saga 정리 (타임아웃 재전송·보상, ADR 0007)

-- 현재 단계에서 타임아웃으로 커맨드를 다시 보낸 횟수. 단계가 바뀌면 0
alter table order_sagas add column step_resends int not null default 0;

-- 타임아웃 감시: 진행 중인 Saga 만, 오래 멈춘 순서로 찾는다 (끝난 Saga 는 계속 쌓이므로 인덱스에서 뺀다)
create index idx_order_sagas_in_progress_updated_at on order_sagas (updated_at)
    where state not in ('COMPLETED', 'CANCELLED');
