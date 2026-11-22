-- 시연용 포인트 계정. 회원 가입(4단계 auth-service) 전까지 쓴다
--   사용자 1~3: 10,000P, 4: 0P(포인트 부족 시나리오)
insert into point_accounts (user_id, balance, updated_at) values
    (1, 10000, now()),
    (2, 10000, now()),
    (3, 10000, now()),
    (4, 0, now());
