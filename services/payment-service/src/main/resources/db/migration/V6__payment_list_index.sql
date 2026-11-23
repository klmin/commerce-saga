-- 후속 2 A-3-1: 내 결제 목록(커서 페이징). user_id 로 거르고 id(UUIDv7 = 생성 순서) 내림차순
-- 기존 (user_id) 인덱스는 이 인덱스의 앞부분이라 대신한다
create index idx_payments_user_id_id on payments (user_id, id);
drop index idx_payments_user_id;
