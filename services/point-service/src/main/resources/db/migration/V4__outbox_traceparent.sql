-- outbox 에 넣는 시점의 trace 문맥(W3C traceparent). relay 가 Kafka 헤더 traceparent 로 싣는다 (ADR 0013)
-- 없어도 되는 값이라 null 허용. 기존 행은 null 로 남고 헤더 없이 나간다
alter table outbox add column traceparent varchar(55);
