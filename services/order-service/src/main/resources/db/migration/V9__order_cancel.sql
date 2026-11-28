-- 주문 취소 (ADR 0020). 사용자 취소 요청 시각은 화면 목록에도 보이도록 orders 에, 진행 판단은 Saga 에 둔다
alter table orders add column cancel_requested_at timestamptz;

alter table order_sagas add column cancel_requested boolean not null default false;
-- 완료된 주문을 취소하는 중이거나 그렇게 취소됨 (재고는 ReturnStock 으로 되돌린다)
alter table order_sagas add column after_completion boolean not null default false;
