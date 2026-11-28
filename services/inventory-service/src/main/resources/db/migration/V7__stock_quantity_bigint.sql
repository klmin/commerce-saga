-- 재고 수량을 bigint 로 (ADR 0020). 완료 후 취소의 재고 반환은 판매 확정분을 다시 들이므로, 관리자 입고 상한(가용 + 예약)만으로는
-- 반환이 쌓여 int 를 넘는 것을 막을 수 없다. 보상(반환)은 거절할 수 없으므로 저장 범위를 넓힌다
alter table stocks alter column available type bigint;
alter table stocks alter column reserved type bigint;
alter table stock_adjustments alter column available_after type bigint;
