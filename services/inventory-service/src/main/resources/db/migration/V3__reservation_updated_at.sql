-- 예약 기록 상태 변경(RELEASED, CONFIRMED) 시각
alter table stock_reservations add column updated_at timestamptz;
update stock_reservations set updated_at = created_at;
alter table stock_reservations alter column updated_at set not null;
